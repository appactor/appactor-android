package com.appactor.android.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.GetBillingConfigParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.appactor.android.internal.logging.AppActorLogger
import com.appactor.android.models.AppActorError
import com.appactor.android.models.AppActorProductType
import com.appactor.android.models.AppActorStore
import com.appactor.android.models.AppActorStoreCapability
import com.appactor.android.models.AppActorStorefront
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.math.min

internal class GooglePlayBillingClientBridge(
    context: Context,
    private val billingClientFactory: (Context, PurchasesUpdatedListener) -> BillingClient = ::createBillingClient,
) : AppActorGoogleBillingClient {

    private companion object {
        const val BILLING_CONNECT_TIMEOUT_MS = 4_000L
        const val STOREFRONT_QUERY_TIMEOUT_MS = 1_500L
        const val RECONNECT_DELAY_START_MS = 1_000L
        const val RECONNECT_DELAY_MAX_MS = 60_000L
    }

    private val applicationContext = context.applicationContext
    private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectionMutex = Mutex()
    private val requestQueueMutex = Mutex()
    private val reconnectLock = Any()
    private val requestDrainLock = Any()
    private val purchaseUpdatesChannel = Channel<AppActorBillingPurchaseUpdate>(capacity = Channel.UNLIMITED)
    private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
        val resolvedProductType = pendingPurchaseProductType ?: AppActorProductType.Unknown
        val purchasePayloads = purchases.orEmpty().map { it.toPayload(resolvedProductType) }

        val continuation = purchaseContinuation
        purchaseContinuation = null
        pendingPurchaseProductType = null
        val handledByContinuation = resumePurchaseContinuationIfActive(
            continuation,
            billingResult.toLaunchResult(
                productType = resolvedProductType,
                purchases = purchases.orEmpty(),
            ),
        )

        if (purchasePayloads.isNotEmpty() && !handledByContinuation) {
            val sent = purchaseUpdatesChannel.trySend(
                AppActorBillingPurchaseUpdate(
                    purchaseTokens = purchasePayloads.mapTo(linkedSetOf()) { it.purchaseToken },
                    purchases = purchasePayloads,
                )
            )
            if (sent.isFailure && sent.exceptionOrNull() == null) {
                throw IllegalStateException("Billing purchase update was dropped before the SDK could process it.")
            }
        }
    }
    private val billingClient: BillingClient = billingClientFactory(applicationContext, purchasesUpdatedListener)

    @Volatile
    private var activeConnectionAttempt: CompletableDeferred<Unit>? = null

    @Volatile
    private var reconnectJob: Job? = null

    @Volatile
    private var requestDrainJob: Job? = null

    @Volatile
    private var reconnectDelayMs: Long = RECONNECT_DELAY_START_MS

    @Volatile
    private var purchaseContinuation: CancellableContinuation<AppActorBillingLaunchResult>? = null

    @Volatile
    private var pendingPurchaseProductType: AppActorProductType? = null
    @Volatile
    private var storefront: AppActorStorefront? = null
    @Volatile
    private var capabilities: Set<AppActorStoreCapability> = emptySet()
    private val pendingRequests = ArrayDeque<QueuedBillingRequest>()

    @OptIn(InternalCoroutinesApi::class)
    private fun resumePurchaseContinuationIfActive(
        continuation: CancellableContinuation<AppActorBillingLaunchResult>?,
        result: AppActorBillingLaunchResult,
    ): Boolean {
        if (continuation == null) return false
        val resumeToken = continuation.tryResume(result, null) ?: return false
        continuation.completeResume(resumeToken)
        return true
    }

	override suspend fun connect() {
        if (billingClient.isReady) {
            refreshConnectedState()
            return
        }
        awaitConnected(scheduleReconnectOnFailure = true)
    }

    override fun shutdown() {
        purchaseContinuation = null
        pendingPurchaseProductType = null
        storefront = null
        capabilities = emptySet()
        synchronized(requestDrainLock) {
            requestDrainJob?.cancel()
            requestDrainJob = null
        }
        synchronized(reconnectLock) {
            reconnectJob?.cancel()
            reconnectJob = null
        }
        activeConnectionAttempt?.cancel()
        activeConnectionAttempt = null
        failPendingRequests(AppActorError.Unknown("Billing bridge was shut down before pending requests completed."))
        purchaseUpdatesChannel.close()
        bridgeScope.cancel()
        billingClient.endConnection()
    }

    override fun isConnected(): Boolean = billingClient.isReady

    override fun currentStorefront(): AppActorStorefront? = storefront

    override fun currentCapabilities(): Set<AppActorStoreCapability> = capabilities

    override suspend fun queryProductDetails(
        products: List<AppActorBillingQueryProduct>,
    ): List<AppActorBillingProductDetailsPayload> {
        if (products.isEmpty()) return emptyList()
        return executeWhenReady {
            val queryProducts = products
                .distinctBy { "${it.productType}:${it.productId}" }
                .map { product ->
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(product.productId)
                        .setProductType(product.productType.toBillingProductType())
                        .build()
                }

            suspendCancellableCoroutine { continuation ->
                val params = QueryProductDetailsParams.newBuilder()
                    .setProductList(queryProducts)
                    .build()

                queryProductDetailsAsync(params) { billingResult, queryResult ->
                    logProductDetailsQueryResult(
                        products = products,
                        billingResult = billingResult,
                    )
                    if (billingResult.responseCode != BillingResponseCode.OK) {
                        continuation.resumeWith(
                            Result.failure(
                                billingResult.toBillingError("Failed to query product details.")
                            )
                        )
                        return@queryProductDetailsAsync
                    }

                    queryResult.unfetchedProductList.orEmpty().forEach { unfetchedProduct ->
                        AppActorLogger.info(
                            "[Billing] queryProductDetails unfetched productId=${unfetchedProduct.productId} " +
                                "productType=${unfetchedProduct.productType} statusCode=${unfetchedProduct.statusCode} " +
                                "serializedDocid=${unfetchedProduct.serializedDocid ?: "null"}"
                        )
                    }

                    continuation.resume(
                        queryResult.productDetailsList.orEmpty().map { productDetails ->
                            val requestedType = products.firstOrNull { product ->
                                product.productId == productDetails.productId &&
                                    product.productType.toBillingProductType() == productDetails.productType
                            }?.productType
                            productDetails.toPayload(requestedType)
                        }
                    )
                }
            }
        }
    }

    override suspend fun launchPurchase(
        activity: Activity,
        productDetails: ProductDetails?,
        productType: AppActorProductType,
        offerToken: String?,
        obfuscatedAccountId: String?,
        oldPurchaseToken: String?,
        replacementMode: Int?,
    ): AppActorBillingLaunchResult {
        connect()
        val resolvedProductDetails = productDetails ?: throw AppActorError.InvalidConfiguration(
            "Billing product details are unavailable for purchase launch."
        )

        return suspendCancellableCoroutine { continuation ->
            val productDetailsParamsBuilder = BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(resolvedProductDetails)

            if (!offerToken.isNullOrBlank()) {
                productDetailsParamsBuilder.setOfferToken(offerToken)
            }

            val paramsBuilder = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(listOf(productDetailsParamsBuilder.build()))

            if (!obfuscatedAccountId.isNullOrBlank()) {
                paramsBuilder.setObfuscatedAccountId(obfuscatedAccountId)
            }

            if (!oldPurchaseToken.isNullOrBlank()) {
                val subscriptionUpdateParams = BillingFlowParams.SubscriptionUpdateParams.newBuilder()
                    .setOldPurchaseToken(oldPurchaseToken)
                if (replacementMode != null) {
                    subscriptionUpdateParams.setSubscriptionReplacementMode(replacementMode)
                }
                paramsBuilder.setSubscriptionUpdateParams(subscriptionUpdateParams.build())
            }

            purchaseContinuation = continuation
            pendingPurchaseProductType = productType
            continuation.invokeOnCancellation {
                if (purchaseContinuation === continuation) {
                    purchaseContinuation = null
                    pendingPurchaseProductType = null
                }
            }
            val launchResult = billingClient.launchBillingFlow(activity, paramsBuilder.build())
            if (launchResult.responseCode != BillingResponseCode.OK) {
                purchaseContinuation = null
                pendingPurchaseProductType = null
                continuation.resume(launchResult.toLaunchResult(productType = productType))
            }
        }
    }

    override fun purchaseUpdates(): Flow<AppActorBillingPurchaseUpdate> {
        return purchaseUpdatesChannel.receiveAsFlow()
            .onStart {
                warmConnectionInBackground()
            }
    }

    override suspend fun queryPurchases(productType: AppActorProductType): List<AppActorBillingPurchasePayload> {
        if (productType != AppActorProductType.Subscription && productType != AppActorProductType.Consumable &&
            productType != AppActorProductType.NonConsumable
        ) {
            return emptyList()
        }

        return executeWhenReady {
            suspendCancellableCoroutine { continuation ->
                val params = QueryPurchasesParams.newBuilder()
                    .setProductType(productType.toBillingProductType())
                    .build()

                queryPurchasesAsync(params) { billingResult, purchases ->
                    if (billingResult.responseCode != BillingResponseCode.OK) {
                        continuation.resumeWith(
                            Result.failure(
                                billingResult.toBillingError("Failed to query purchases.")
                            )
                        )
                        return@queryPurchasesAsync
                    }

                    continuation.resume(
                        purchases.orEmpty().map { purchase ->
                            purchase.toPayload(productType = productType)
                        }
                    )
                }
            }
        }
    }

    // Returns active/owned purchases mapped to history payloads. Expired subscriptions
    // and consumed one-time products are NOT included — the backend is authoritative for
    // full history via RTDN.
    override suspend fun queryPurchaseHistory(
        productType: AppActorProductType,
    ): List<AppActorBillingPurchaseHistoryPayload> {
        if (productType != AppActorProductType.Subscription &&
            productType != AppActorProductType.Consumable &&
            productType != AppActorProductType.NonConsumable
        ) {
            return emptyList()
        }
        return queryPurchases(productType).map { it.toHistoryPayload() }
    }

    override suspend fun acknowledgePurchase(purchaseToken: String) {
        executeWhenReady {
            suspendCancellableCoroutine<Unit> { continuation ->
                val params = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchaseToken)
                    .build()

                acknowledgePurchase(params) { billingResult ->
                    if (billingResult.responseCode == BillingResponseCode.OK) {
                        continuation.resume(Unit)
                    } else {
                        continuation.resumeWith(
                            Result.failure(
                                billingResult.toBillingError("Failed to acknowledge purchase.")
                            )
                        )
                    }
                }
            }
        }
    }

    override suspend fun consumePurchase(purchaseToken: String) {
        executeWhenReady {
            suspendCancellableCoroutine<Unit> { continuation ->
                val params = ConsumeParams.newBuilder()
                    .setPurchaseToken(purchaseToken)
                    .build()

                consumeAsync(params) { billingResult, _ ->
                    if (billingResult.responseCode == BillingResponseCode.OK) {
                        continuation.resume(Unit)
                    } else {
                        continuation.resumeWith(
                            Result.failure(
                                billingResult.toBillingError("Failed to consume purchase.")
                            )
                        )
                    }
                }
            }
        }
    }

    private suspend fun queryStorefront(): AppActorStorefront? {
        if (!supportsFeature(BillingClient.FeatureType.BILLING_CONFIG)) {
            return null
        }
        return suspendCancellableCoroutine { continuation ->
            billingClient.getBillingConfigAsync(
                GetBillingConfigParams.newBuilder().build(),
            ) { billingResult, billingConfig ->
                if (billingResult.responseCode == BillingResponseCode.OK) {
                    continuation.resume(
                        AppActorStorefront(
                            store = AppActorStore.PlayStore,
                            countryCode = billingConfig?.countryCode?.takeIf(String::isNotBlank),
                        ),
                    )
                } else {
                    continuation.resume(null)
                }
            }
        }
    }

    private fun resolveCapabilities(): Set<AppActorStoreCapability> {
        if (!billingClient.isReady) {
            return emptySet()
        }
        return linkedSetOf<AppActorStoreCapability>().apply {
            add(AppActorStoreCapability.Purchases)
            add(AppActorStoreCapability.InAppProducts)
            if (supportsFeature(BillingClient.FeatureType.SUBSCRIPTIONS)) {
                add(AppActorStoreCapability.Subscriptions)
            }
            if (supportsFeature(BillingClient.FeatureType.BILLING_CONFIG)) {
                add(AppActorStoreCapability.Storefront)
            }
        }
    }

    private fun supportsFeature(feature: String): Boolean {
        return billingClient.isFeatureSupported(feature).responseCode == BillingResponseCode.OK
    }

    private suspend fun awaitConnected(
        scheduleReconnectOnFailure: Boolean,
    ) {
        if (billingClient.isReady) {
            refreshConnectedState()
            return
        }

        val connectionAttempt = connectionMutex.withLock {
            if (billingClient.isReady) {
                null
            } else {
                activeConnectionAttempt?.takeIf { !it.isCancelled } ?: CompletableDeferred<Unit>().also { deferred ->
                    activeConnectionAttempt = deferred
                    bridgeScope.launch {
                        runCatching { establishConnection() }
                            .onSuccess {
                                deferred.complete(Unit)
                            }
                            .onFailure { throwable ->
                                deferred.completeExceptionally(throwable)
                                if (scheduleReconnectOnFailure) {
                                    scheduleReconnect()
                                }
                            }
                        connectionMutex.withLock {
                            if (activeConnectionAttempt === deferred) {
                                activeConnectionAttempt = null
                            }
                        }
                    }
                }
            }
        }

        connectionAttempt?.await()
    }

    private suspend fun establishConnection() {
        withTimeout(BILLING_CONNECT_TIMEOUT_MS) {
            suspendCancellableCoroutine<Unit> { continuation ->
                billingClient.startConnection(
                    object : com.android.billingclient.api.BillingClientStateListener {
                        override fun onBillingSetupFinished(billingResult: BillingResult) {
                            if (!continuation.isActive) return
                            if (billingResult.responseCode == BillingResponseCode.OK) {
                                continuation.resume(Unit)
                            } else {
                                continuation.resumeWith(
                                    Result.failure(
                                        billingResult.toBillingError("Billing setup failed.")
                                    )
                                )
                            }
                        }

                        override fun onBillingServiceDisconnected() {
                            capabilities = emptySet()
                            storefront = null
                            if (continuation.isActive) {
                                continuation.resumeWith(
                                    Result.failure(
                                        AppActorError.Network("Billing service disconnected.")
                                    )
                                )
                            } else {
                                scheduleReconnect()
                            }
                        }
                    }
                )
            }
        }

        refreshConnectedState()
    }

    private fun warmConnectionInBackground() {
        if (billingClient.isReady) return
        bridgeScope.launch {
            runCatching {
                awaitConnected(scheduleReconnectOnFailure = true)
            }
        }
    }

    private fun scheduleReconnect() {
        synchronized(reconnectLock) {
            if (reconnectJob?.isActive == true || billingClient.isReady) {
                return
            }
            reconnectJob = bridgeScope.launch {
                var currentDelayMs = reconnectDelayMs
                try {
                    while (isActive && !billingClient.isReady) {
                        delay(currentDelayMs)
                        val connected = runCatching {
                            awaitConnected(scheduleReconnectOnFailure = false)
                        }.isSuccess
                        if (connected) {
                            reconnectDelayMs = RECONNECT_DELAY_START_MS
                            return@launch
                        }
                        currentDelayMs = min(currentDelayMs * 2, RECONNECT_DELAY_MAX_MS)
                        reconnectDelayMs = currentDelayMs
                    }
                } finally {
                    synchronized(reconnectLock) {
                        reconnectJob = null
                    }
                }
            }
        }
    }

    private suspend fun refreshConnectedState() {
        capabilities = resolveCapabilities()
        storefront = if (supportsFeature(BillingClient.FeatureType.BILLING_CONFIG)) {
            runCatching {
                withTimeout(STOREFRONT_QUERY_TIMEOUT_MS) {
                    queryStorefront()
                }
            }.getOrNull()
        } else {
            null
        }
        reconnectDelayMs = RECONNECT_DELAY_START_MS
        drainQueuedRequestsInBackground()
    }

    private suspend fun <T> executeWhenReady(
        operation: suspend BillingClient.() -> T,
    ): T {
        val queuedRequest = requestQueueMutex.withLock {
            if (billingClient.isReady && pendingRequests.isEmpty() && requestDrainJob?.isActive != true) {
                null
            } else {
                DeferredBillingRequest(operation).also { pendingRequests.addLast(it) }
            }
        }

        if (queuedRequest == null) {
            return billingClient.operation()
        }

        drainQueuedRequestsInBackground()
        return queuedRequest.await()
    }

    private fun drainQueuedRequestsInBackground() {
        synchronized(requestDrainLock) {
            if (requestDrainJob?.isActive == true) {
                return
            }
            requestDrainJob = bridgeScope.launch {
                try {
                    while (isActive) {
                        val request = requestQueueMutex.withLock {
                            if (pendingRequests.isEmpty()) null else pendingRequests.removeFirst()
                        } ?: return@launch

                        if (!billingClient.isReady) {
                            val connected = runCatching {
                                awaitConnected(scheduleReconnectOnFailure = true)
                            }.isSuccess
                            if (!connected || !billingClient.isReady) {
                                requestQueueMutex.withLock {
                                    pendingRequests.addFirst(request)
                                }
                                return@launch
                            }
                        }

                        request.execute(billingClient)
                    }
                } finally {
                    synchronized(requestDrainLock) {
                        requestDrainJob = null
                    }
                    bridgeScope.launch {
                        val hasPending = requestQueueMutex.withLock { pendingRequests.isNotEmpty() }
                        if (billingClient.isReady && hasPending) {
                            drainQueuedRequestsInBackground()
                        }
                    }
                }
            }
        }
    }

    private fun failPendingRequests(error: Throwable) {
        val pending = runCatching {
            runBlocking {
                requestQueueMutex.withLock {
                    pendingRequests.toList().also { pendingRequests.clear() }
                }
            }
        }.getOrDefault(emptyList())
        pending.forEach { it.cancel(error) }
    }
}

private interface QueuedBillingRequest {
    suspend fun execute(client: BillingClient)
    fun cancel(error: Throwable)
}

private class DeferredBillingRequest<T>(
    private val operation: suspend BillingClient.() -> T,
) : QueuedBillingRequest {
    private val result = CompletableDeferred<T>()

    override suspend fun execute(client: BillingClient) {
        runCatching { client.operation() }
            .onSuccess { result.complete(it) }
            .onFailure { result.completeExceptionally(it) }
    }

    override fun cancel(error: Throwable) {
        result.completeExceptionally(error)
    }

    suspend fun await(): T = result.await()
}

private fun createBillingClient(
    applicationContext: Context,
    purchasesUpdatedListener: PurchasesUpdatedListener,
): BillingClient {
    return BillingClient.newBuilder(applicationContext)
        .setListener(purchasesUpdatedListener)
        .enableAutoServiceReconnection()
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .enablePrepaidPlans()
                .build()
        )
        .build()
}

private fun logProductDetailsQueryResult(
    products: List<AppActorBillingQueryProduct>,
    billingResult: BillingResult,
) {
    val requestedTypes = products.mapTo(linkedSetOf()) { it.productType.toBillingProductType() }
    val requestedProductIds = products.map { it.productId }.distinct()
    AppActorLogger.info(
        "[Billing] queryProductDetails types=${requestedTypes.joinToString(",")} " +
            "productIds=${requestedProductIds.joinToString(",")} " +
            "responseCode=${billingResult.responseCode} " +
            "debugMessage=${billingResult.debugMessage.ifBlank { "<empty>" }}"
    )
}

private fun ProductDetails.toPayload(
    requestedType: AppActorProductType?,
): AppActorBillingProductDetailsPayload {
    val oneTimePricing = oneTimePurchaseOfferDetails?.let { offer ->
        AppActorStorePricing(
            formattedPrice = offer.formattedPrice,
            priceAmountMicros = offer.priceAmountMicros,
            currencyCode = offer.priceCurrencyCode,
        )
    }
    val subscriptionOffers = subscriptionOfferDetails.orEmpty().map { offer ->
        val pricingPhaseList = offer.pricingPhases.pricingPhaseList
        AppActorBillingSubscriptionOfferPayload(
            basePlanId = offer.basePlanId,
            offerId = offer.offerId,
            offerToken = offer.offerToken,
            pricing = pricingPhaseList.lastOrNull()?.let { phase ->
                AppActorStorePricing(
                    formattedPrice = phase.formattedPrice,
                    priceAmountMicros = phase.priceAmountMicros,
                    currencyCode = phase.priceCurrencyCode,
                )
            },
            offerTags = offer.offerTags,
            pricingPhases = pricingPhaseList.map { phase ->
                AppActorBillingPricingPhasePayload(
                    billingPeriod = phase.billingPeriod,
                    priceAmountMicros = phase.priceAmountMicros,
                )
            },
        )
    }
    return AppActorBillingProductDetailsPayload(
        productId = productId,
        productType = requestedType ?: when (productType) {
            BillingClient.ProductType.SUBS -> AppActorProductType.Subscription
            BillingClient.ProductType.INAPP -> AppActorProductType.Unknown
            else -> AppActorProductType.Unknown
        },
        title = title,
        displayName = name,
        description = description,
        oneTimeOffer = oneTimePricing,
        subscriptionOffers = subscriptionOffers,
        nativeProductDetails = this,
    )
}

private fun Purchase.toPayload(productType: AppActorProductType): AppActorBillingPurchasePayload {
    val state = when (purchaseState) {
        Purchase.PurchaseState.PURCHASED -> AppActorStorePurchaseState.Purchased
        Purchase.PurchaseState.PENDING -> AppActorStorePurchaseState.Pending
        else -> AppActorStorePurchaseState.Unknown
    }
    return AppActorBillingPurchasePayload(
        products = products,
        productType = productType,
        purchaseToken = purchaseToken,
        orderId = orderId,
        purchaseTimeMillis = purchaseTime,
        purchaseState = state,
        isAcknowledged = isAcknowledged,
        isAutoRenewing = isAutoRenewing,
        obfuscatedAccountId = accountIdentifiers?.obfuscatedAccountId,
        rawPurchaseData = originalJson,
        purchaseSignature = signature,
    )
}

private fun BillingResult.toLaunchResult(
    productType: AppActorProductType,
    purchases: List<Purchase> = emptyList(),
): AppActorBillingLaunchResult {
    return when {
        responseCode == BillingResponseCode.OK && purchases.any { it.purchaseState == Purchase.PurchaseState.PURCHASED } -> {
            AppActorBillingLaunchResult.Purchased(purchases.map { it.toPayload(productType) })
        }

        responseCode == BillingResponseCode.OK &&
            purchases.any { it.purchaseState == Purchase.PurchaseState.PENDING } -> {
            AppActorBillingLaunchResult.Pending(purchases.map { it.toPayload(productType) })
        }

        responseCode == BillingResponseCode.USER_CANCELED -> AppActorBillingLaunchResult.Cancelled
        else -> AppActorBillingLaunchResult.Failed(
            error = toBillingError("Billing flow failed.")
        )
    }
}

private fun BillingResult.toBillingError(defaultMessage: String): AppActorError {
    val message = debugMessage.takeIf { it.isNotBlank() } ?: defaultMessage
    return when (responseCode) {
        BillingResponseCode.USER_CANCELED -> AppActorError.Unknown(message)
        BillingResponseCode.SERVICE_DISCONNECTED,
        BillingResponseCode.SERVICE_UNAVAILABLE,
        BillingResponseCode.NETWORK_ERROR,
        BillingResponseCode.ERROR -> AppActorError.Network(message)

        BillingResponseCode.ITEM_UNAVAILABLE -> AppActorError.InvalidOffer(message)
        BillingResponseCode.ITEM_ALREADY_OWNED -> AppActorError.PurchaseIneligible(message)
        BillingResponseCode.BILLING_UNAVAILABLE,
        BillingResponseCode.FEATURE_NOT_SUPPORTED,
        BillingResponseCode.DEVELOPER_ERROR -> AppActorError.InvalidConfiguration(message)

        else -> AppActorError.Unknown(message)
    }
}

private fun AppActorProductType.toBillingProductType(): String {
    return when (this) {
        AppActorProductType.Subscription -> BillingClient.ProductType.SUBS
        AppActorProductType.Consumable,
        AppActorProductType.NonConsumable -> BillingClient.ProductType.INAPP
        AppActorProductType.Unknown -> BillingClient.ProductType.INAPP
    }
}
