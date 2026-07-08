package com.appactor.android.billing

import android.app.Activity
import android.content.Context
import com.appactor.android.internal.logging.AppActorLogger
import com.appactor.android.models.AppActorError
import com.appactor.android.models.AppActorProductType
import com.appactor.android.models.AppActorStore
import com.appactor.android.models.AppActorStoreCapability
import com.appactor.android.models.AppActorStorefront
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class GooglePlayStoreAdapter(
    context: Context,
    private val billingClient: AppActorGoogleBillingClient = GooglePlayBillingClientBridge(context),
) : AppActorStoreAdapter {

    private val resolvedProductsByKey = ConcurrentHashMap<String, ResolvedProduct>()

    override suspend fun connect() {
        billingClient.connect()
    }

    override fun shutdown() {
        resolvedProductsByKey.clear()
        billingClient.shutdown()
    }

    override fun isConnected(): Boolean = billingClient.isConnected()

    override fun currentStorefront(): AppActorStorefront? = billingClient.currentStorefront()

    override fun currentCapabilities(): Set<AppActorStoreCapability> = billingClient.currentCapabilities()

    override fun canMakePurchases(requiredCapabilities: Set<AppActorStoreCapability>): Boolean {
        val capabilities = currentCapabilities()
        return isConnected() &&
            capabilities.contains(AppActorStoreCapability.Purchases) &&
            capabilities.containsAll(requiredCapabilities)
    }

    override suspend fun queryProductDetails(
        requests: List<AppActorStoreProductRequest>,
    ): List<AppActorStoreProduct> {
        if (requests.isEmpty()) return emptyList()
        connect()

        val payloadsByProductId = queryProductDetailsPayloads(requests)

        return requests.mapNotNull { request ->
            val payloads = payloadsByProductId[request.productId].orEmpty()
            val resolved = resolveProduct(payloads, request)
            if (resolved == null) {
                logUnresolvedRequest(request, payloads)
                return@mapNotNull null
            }
            resolvedProductsByKey[request.cacheKey()] = resolved
            resolved.product
        }
    }

    override suspend fun launchPurchase(
        activity: Activity,
        request: AppActorStoreProductRequest,
    ): AppActorStorePurchaseLaunchResult {
        connect()
        val resolved = resolvedProductsByKey[request.cacheKey()]
            ?: queryProductDetails(listOf(request))
                .firstOrNull()
                ?.let { resolvedProductsByKey[request.cacheKey()] }
            ?: throw AppActorError.InvalidConfiguration(
                "No Google Play product details available for ${request.productId}."
            )

        val launchResult = billingClient.launchPurchase(
            activity = activity,
            productDetails = resolved.nativeProductDetails,
            productType = resolved.product.productType,
            offerToken = resolved.offerToken,
            obfuscatedAccountId = request.obfuscatedAccountId,
            oldPurchaseToken = request.oldPurchaseToken,
            replacementMode = request.replacementMode,
        )
        val resolvedRequest = resolved.toRequest(obfuscatedAccountId = request.obfuscatedAccountId)
        return when (launchResult) {
            is AppActorBillingLaunchResult.Purchased -> AppActorStorePurchaseLaunchResult.Purchased(
                purchases = launchResult.purchases.map { it.toStorePurchase(resolvedRequest) }
            )

            is AppActorBillingLaunchResult.Pending -> AppActorStorePurchaseLaunchResult.Pending(
                purchases = launchResult.purchases.map { it.toStorePurchase(resolvedRequest) }
            )

            AppActorBillingLaunchResult.Cancelled -> AppActorStorePurchaseLaunchResult.Cancelled
            is AppActorBillingLaunchResult.Failed -> AppActorStorePurchaseLaunchResult.Failed(launchResult.error)
        }
    }

    override fun purchaseUpdates(): Flow<List<AppActorStorePurchase>> {
        return billingClient.purchaseUpdates().map { update ->
            val activeByToken = runCatching {
                queryActivePurchases().groupBy { it.purchaseToken }
            }.getOrDefault(emptyMap())
            val resolvedFromActive = update.purchaseTokens
                .flatMap { token -> activeByToken[token].orEmpty() }
                .distinctBy { it.purchaseToken to it.productId }
            if (resolvedFromActive.isNotEmpty()) {
                resolvedFromActive
            } else {
                update.purchases.flatMap { payload ->
                    payload.products.map { productId ->
                        val resolvedRequest = runCatching {
                            resolveDirectPurchaseRequest(
                                AppActorStoreProductRequest(
                                    productId = productId,
                                    productType = payload.productType,
                                    obfuscatedAccountId = payload.obfuscatedAccountId,
                                )
                            )
                        }.getOrElse {
                            resolvedProductsByKey.entries
                                .firstOrNull { (_, resolved) -> resolved.product.productId == productId }
                                ?.value
                                ?.toRequest(obfuscatedAccountId = payload.obfuscatedAccountId)
                        } ?: AppActorStoreProductRequest(
                            productId = productId,
                            productType = payload.productType,
                            obfuscatedAccountId = payload.obfuscatedAccountId,
                        )
                        payload.toStorePurchase(resolvedRequest)
                    }
                }
            }
        }
    }

    override suspend fun resolveDirectPurchaseRequest(
        request: AppActorStoreProductRequest,
    ): AppActorStoreProductRequest {
        connect()
        val productId = request.productId

        val payloads = queryProductDetailsPayloads(listOf(request))[productId].orEmpty()

        val subscriptionPayload = payloads.firstOrNull { it.productType == AppActorProductType.Subscription }
        val inAppPayload = payloads.firstOrNull { it.productType != AppActorProductType.Subscription }

        when (request.resolutionKind()) {
            ProductResolutionKind.Subscription -> {
                val payload = subscriptionPayload
                    ?: throw AppActorError.InvalidConfiguration(
                        "No Play subscription target found for $productId."
                    )
                val resolved = resolveSubscriptionPayload(payload, request)
                    ?: throw AppActorError.InvalidConfiguration(
                        "No matching Play subscription offer found for $productId " +
                            "(basePlanId=${request.basePlanId}, offerId=${request.offerId})."
                    )
                val resolvedRequest = request.copy(
                    productType = AppActorProductType.Subscription,
                    basePlanId = resolved.product.basePlanId,
                    offerId = resolved.product.offerId,
                )
                cacheResolvedProduct(request, resolvedRequest, resolved)
                return resolvedRequest
            }

            ProductResolutionKind.OneTime -> {
                val payload = inAppPayload
                    ?: throw AppActorError.InvalidConfiguration(
                        "No matching Play one-time target found for $productId."
                    )
                validateExplicitOneTimeProductType(request)
                val resolved = resolveOneTimePayload(payload, request)
                cacheResolvedProduct(request, request, resolved)
                return request
            }

            ProductResolutionKind.Ambiguous -> Unit
        }

        if (subscriptionPayload != null && inAppPayload != null) {
            throw AppActorError.InvalidConfiguration(
                "Ambiguous Play target for $productId. The product exists as both SUBS and INAPP."
            )
        }

        if (subscriptionPayload != null) {
            val offers = subscriptionPayload.subscriptionOffers
            if (offers.size != 1) {
                throw AppActorError.InvalidConfiguration(
                    "Ambiguous Play target for $productId. " +
                        "A single subscription base plan/offer is required."
                )
            }
            val offer = offers.single()
            val resolvedRequest = request.copy(
                productId = productId,
                productType = AppActorProductType.Subscription,
                basePlanId = offer.basePlanId,
                offerId = offer.offerId,
            )
            val resolved = resolveSubscriptionPayload(subscriptionPayload, resolvedRequest)
                ?: throw AppActorError.InvalidConfiguration("No Play subscription offer found for $productId.")
            cacheResolvedProduct(request, resolvedRequest, resolved)
            return resolvedRequest
        }

        if (inAppPayload != null) {
            val oneTimeProductType = resolveKnownOneTimeProductType(productId)
                ?: throw AppActorError.InvalidConfiguration(
                    "Direct one-time purchase for $productId requires offerings metadata to determine whether it is consumable or non-consumable."
                )
            val resolvedRequest = request.copy(
                productId = productId,
                productType = oneTimeProductType,
            )
            val resolved = resolveOneTimePayload(inAppPayload, resolvedRequest)
            cacheResolvedProduct(request, resolvedRequest, resolved)
            return resolvedRequest
        }

        throw AppActorError.InvalidConfiguration("No Play product details found for $productId.")
    }

    override suspend fun queryActivePurchases(): List<AppActorStorePurchase> {
        connect()
        val subscriptions = billingClient.queryPurchases(AppActorProductType.Subscription)
        val inAppPurchases = billingClient.queryPurchases(AppActorProductType.NonConsumable)
        return (subscriptions + inAppPurchases).flatMap { payload ->
            payload.products.map { productId ->
                val matchedRequest = resolvedProductsByKey.entries
                    .firstOrNull { (_, resolved) -> resolved.product.productId == productId }
                    ?.value
                val inferredRequest = matchedRequest?.toRequest(obfuscatedAccountId = payload.obfuscatedAccountId)
                    ?: runCatching {
                        resolveDirectPurchaseRequest(
                            recoveryRequest(
                                productId = productId,
                                payloadProductType = payload.productType,
                                obfuscatedAccountId = payload.obfuscatedAccountId,
                            )
                        )
                    }.getOrNull()
                val resolvedRequest = inferredRequest
                    ?: recoveryRequest(
                        productId = productId,
                        payloadProductType = payload.productType,
                        obfuscatedAccountId = payload.obfuscatedAccountId,
                    )
                payload.toStorePurchase(resolvedRequest)
            }
        }
    }

    override suspend fun queryPurchaseHistory(): List<AppActorStorePurchaseHistoryRecord> {
        connect()
        val subscriptions = billingClient.queryPurchaseHistory(AppActorProductType.Subscription)
        val inAppHistory = billingClient.queryPurchaseHistory(AppActorProductType.NonConsumable)
        return (subscriptions + inAppHistory).flatMap { payload ->
            payload.products.map { productId ->
                val matchedRequest = resolvedProductsByKey.entries
                    .firstOrNull { (_, resolved) -> resolved.product.productId == productId }
                    ?.value
                val inferredRequest = matchedRequest?.toRequest(obfuscatedAccountId = payload.obfuscatedAccountId)
                    ?: runCatching {
                        resolveDirectPurchaseRequest(
                            recoveryRequest(
                                productId = productId,
                                payloadProductType = payload.productType,
                                obfuscatedAccountId = payload.obfuscatedAccountId,
                            )
                        )
                    }.getOrNull()
                    ?: recoveryRequest(
                        productId = productId,
                        payloadProductType = payload.productType,
                        obfuscatedAccountId = payload.obfuscatedAccountId,
                    )
                payload.toHistoryRecord(inferredRequest)
            }
        }
    }

    override suspend fun acknowledgePurchase(purchaseToken: String) {
        billingClient.acknowledgePurchase(purchaseToken)
    }

    override suspend fun consumePurchase(purchaseToken: String) {
        billingClient.consumePurchase(purchaseToken)
    }

    private fun resolveProduct(
        payloads: List<AppActorBillingProductDetailsPayload>,
        request: AppActorStoreProductRequest,
    ): ResolvedProduct? {
        val subscriptionPayload = payloads.firstOrNull { it.productType == AppActorProductType.Subscription }
        val inAppPayload = payloads.firstOrNull { it.productType != AppActorProductType.Subscription }

        return when (request.resolutionKind()) {
            ProductResolutionKind.Subscription -> subscriptionPayload?.let { payload ->
                resolveSubscriptionPayload(payload, request)
            }

            ProductResolutionKind.OneTime -> inAppPayload?.let { payload ->
                resolveOneTimePayload(payload, request)
            }

            ProductResolutionKind.Ambiguous -> resolveAmbiguousPayload(
                subscriptionPayload = subscriptionPayload,
                inAppPayload = inAppPayload,
                request = request,
            )
        }
    }

    private suspend fun queryProductDetailsPayloads(
        requests: List<AppActorStoreProductRequest>,
    ): Map<String, List<AppActorBillingProductDetailsPayload>> {
        val requestsByProductId = requests.groupBy { it.productId }
        val uniqueProductIds = requests.map { it.productId }.distinct()
        val subscriptionPayloads = billingClient.queryProductDetails(
            uniqueProductIds.map { productId ->
                AppActorBillingQueryProduct(
                    productId = productId,
                    productType = AppActorProductType.Subscription,
                )
            }
        )
        val subscriptionProductIds = subscriptionPayloads.mapTo(linkedSetOf()) { it.productId }
        val inAppQueryProductIds = uniqueProductIds.filter { productId ->
            !subscriptionProductIds.contains(productId) ||
                requestsByProductId[productId].orEmpty().any { request ->
                    request.resolutionKind() != ProductResolutionKind.Subscription
                }
        }
        val inAppPayloads = if (inAppQueryProductIds.isEmpty()) {
            emptyList()
        } else {
            billingClient.queryProductDetails(
                inAppQueryProductIds.map { productId ->
                    AppActorBillingQueryProduct(
                        productId = productId,
                        productType = AppActorProductType.NonConsumable,
                    )
                }
            )
        }

        return (subscriptionPayloads + inAppPayloads).groupBy { it.productId }
    }

    private fun resolveAmbiguousPayload(
        subscriptionPayload: AppActorBillingProductDetailsPayload?,
        inAppPayload: AppActorBillingProductDetailsPayload?,
        request: AppActorStoreProductRequest,
    ): ResolvedProduct? {
        return when {
            subscriptionPayload != null && inAppPayload != null -> {
                AppActorLogger.warn(
                    "[Billing] Ambiguous Play product request matched both SUBS and INAPP " +
                        "for productId=${request.productId}; refusing to guess."
                )
                null
            }

            subscriptionPayload != null -> resolveSingleSubscriptionPayload(subscriptionPayload, request)
            inAppPayload != null -> resolveOneTimePayload(inAppPayload, request)
            else -> null
        }
    }

    private fun resolveSubscriptionPayload(
        payload: AppActorBillingProductDetailsPayload,
        request: AppActorStoreProductRequest,
    ): ResolvedProduct? {
        val resolvedOffer = resolveSubscriptionOffer(payload, request) ?: run {
            AppActorLogger.warn(
                "[Billing] No matching Play subscription offer for productId=${request.productId} " +
                    "basePlanId=${request.basePlanId ?: "null"} offerId=${request.offerId ?: "null"}"
            )
            return null
        }
        return ResolvedProduct(
            product = AppActorStoreProduct(
                productId = payload.productId,
                productType = AppActorProductType.Subscription,
                basePlanId = resolvedOffer.basePlanId,
                offerId = resolvedOffer.offerId,
                localizedPrice = resolvedOffer.pricing?.formattedPrice,
                priceAmountMicros = resolvedOffer.pricing?.priceAmountMicros,
                currencyCode = resolvedOffer.pricing?.currencyCode,
                title = payload.title,
                displayName = payload.displayName,
                description = payload.description,
            ),
            nativeProductDetails = payload.nativeProductDetails,
            offerToken = resolvedOffer.offerToken,
        )
    }

    private fun resolveSingleSubscriptionPayload(
        payload: AppActorBillingProductDetailsPayload,
        request: AppActorStoreProductRequest,
    ): ResolvedProduct? {
        val offers = payload.subscriptionOffers
        if (offers.size != 1) {
            AppActorLogger.warn(
                "[Billing] Ambiguous Play subscription match for productId=${request.productId} " +
                    "with no package hint; offers=${offers.size}."
            )
            return null
        }
        val resolvedOffer = offers.single()
        return ResolvedProduct(
            product = AppActorStoreProduct(
                productId = payload.productId,
                productType = AppActorProductType.Subscription,
                basePlanId = resolvedOffer.basePlanId,
                offerId = resolvedOffer.offerId,
                localizedPrice = resolvedOffer.pricing?.formattedPrice,
                priceAmountMicros = resolvedOffer.pricing?.priceAmountMicros,
                currencyCode = resolvedOffer.pricing?.currencyCode,
                title = payload.title,
                displayName = payload.displayName,
                description = payload.description,
            ),
            nativeProductDetails = payload.nativeProductDetails,
            offerToken = resolvedOffer.offerToken,
        )
    }

    private fun resolveOneTimePayload(
        payload: AppActorBillingProductDetailsPayload,
        request: AppActorStoreProductRequest,
    ): ResolvedProduct {
        return ResolvedProduct(
            product = AppActorStoreProduct(
                productId = payload.productId,
                productType = request.productType,
                localizedPrice = payload.oneTimeOffer?.formattedPrice,
                priceAmountMicros = payload.oneTimeOffer?.priceAmountMicros,
                currencyCode = payload.oneTimeOffer?.currencyCode,
                title = payload.title,
                displayName = payload.displayName,
                description = payload.description,
            ),
            nativeProductDetails = payload.nativeProductDetails,
            offerToken = null,
        )
    }

    private fun resolveSubscriptionOffer(
        payload: AppActorBillingProductDetailsPayload,
        request: AppActorStoreProductRequest,
    ): AppActorBillingSubscriptionOfferPayload? {
        val offers = payload.subscriptionOffers
        if (offers.isEmpty()) return null

        // Auto-selection (RevenueCat parity): when the request does not pin an explicit offer,
        // pick the best eligible offer Play returned for the requested base plan — longest free
        // trial, else cheapest intro price, else the base plan itself. Offers tagged
        // "aa-ignore-offer" in Play Console are skipped. The explicit-offer path below is
        // unchanged (it serves pinned-offer configs), including its base-plan fallback and
        // ambiguity fail-fast semantics.
        if (!request.basePlanId.isNullOrBlank() && request.offerId.isNullOrBlank()) {
            val autoSelected = SubscriptionOfferSelector.selectBestOffer(
                offers.filter { offer -> offer.basePlanId == request.basePlanId }
            )
            if (autoSelected != null) {
                if (!autoSelected.offerId.isNullOrBlank()) {
                    AppActorLogger.info(
                        "[Billing] Auto-selected Play subscription offer for productId=${request.productId} " +
                            "basePlanId=${request.basePlanId} offerId=${autoSelected.offerId}"
                    )
                }
                return autoSelected
            }
            // No trial, no intro price, and no base-plan pseudo-offer under this base plan —
            // fall through to the legacy tiers (single-match select, ambiguity fail-fast).
        }

        val resolved = selectUniqueOffer(
            payload = payload,
            request = request,
            candidates = offers.filter { offer ->
                offer.basePlanId == request.basePlanId && offer.offerId == request.offerId
            },
            ambiguityDescription = "matching base plan and offer"
        ) ?: selectUniqueOffer(
            payload = payload,
            request = request,
            candidates = offers.filter { offer ->
                offer.basePlanId == request.basePlanId &&
                    request.offerId.isNullOrBlank() &&
                    offer.offerId.isNullOrBlank()
            },
            ambiguityDescription = "matching base plan with no offer id"
        ) ?: selectUniqueOffer(
            payload = payload,
            request = request,
            candidates = offers.filter { offer ->
                offer.basePlanId == request.basePlanId && request.offerId.isNullOrBlank()
            },
            ambiguityDescription = "matching base plan"
        ) ?: selectUniqueOffer(
            payload = payload,
            request = request,
            candidates = offers.filter { offer ->
                request.basePlanId.isNullOrBlank() && request.offerId == offer.offerId
            },
            ambiguityDescription = "matching offer id"
        )
        if (resolved != null) return resolved

        // Base-plan fallback: the request named a specific offer (e.g. a free trial / intro offer)
        // that Play did not return for this user. The common cause is a returning / ineligible user
        // for whom Play omits the offer from ProductDetails. Rather than fail the purchase and leave
        // the user unable to subscribe, degrade to the base-plan token for the SAME base plan and let
        // Play charge the standard price — matching RevenueCat and Adapty. This only falls back to the
        // base plan (offerId == null); it never substitutes a *different* offer. A misconfigured /
        // typo'd offerId also lands here (silent base-price charge), so we log a warning.
        if (!request.offerId.isNullOrBlank()) {
            val baseFallback = selectUniqueOffer(
                payload = payload,
                request = request,
                candidates = offers.filter { offer ->
                    offer.basePlanId == request.basePlanId && offer.offerId.isNullOrBlank()
                },
                ambiguityDescription = "base plan fallback (requested offer unavailable)"
            )
            if (baseFallback != null) {
                AppActorLogger.warn(
                    "[Billing] Requested Play offer unavailable for productId=${request.productId} " +
                        "basePlanId=${request.basePlanId ?: "null"} offerId=${request.offerId ?: "null"}; " +
                        "falling back to the base plan (standard price)."
                )
                return baseFallback
            }
        }

        return null
    }

    private fun selectUniqueOffer(
        payload: AppActorBillingProductDetailsPayload,
        request: AppActorStoreProductRequest,
        candidates: List<AppActorBillingSubscriptionOfferPayload>,
        ambiguityDescription: String,
    ): AppActorBillingSubscriptionOfferPayload? {
        return when (candidates.size) {
            0 -> null
            1 -> candidates.single()
            else -> throw AppActorError.InvalidConfiguration(
                "Ambiguous Play subscription purchase for ${payload.productId}. Expected a single offer for $ambiguityDescription " +
                    "(basePlanId=${request.basePlanId}, offerId=${request.offerId})."
            )
        }
    }

    private fun resolveKnownOneTimeProductType(
        productId: String,
    ): AppActorProductType? {
        val matches = resolvedProductsByKey.values
            .map { it.product }
            .filter { it.productId == productId }
            .map { it.productType }
            .filter { it == AppActorProductType.Consumable || it == AppActorProductType.NonConsumable }
            .distinct()

        return when (matches.size) {
            1 -> matches.single()
            else -> null
        }
    }

    private fun validateExplicitOneTimeProductType(
        request: AppActorStoreProductRequest,
    ) {
        val knownType = resolveKnownOneTimeProductType(request.productId) ?: return
        if (knownType != request.productType) {
            throw AppActorError.InvalidConfiguration(
                "Conflicting Play one-time target for ${request.productId}. " +
                    "Expected $knownType but direct purchase requested ${request.productType}."
            )
        }
    }

    private fun cacheResolvedProduct(
        originalRequest: AppActorStoreProductRequest,
        resolvedRequest: AppActorStoreProductRequest,
        resolvedProduct: ResolvedProduct,
    ) {
        resolvedProductsByKey[resolvedRequest.cacheKey()] = resolvedProduct
        if (originalRequest.cacheKey() != resolvedRequest.cacheKey()) {
            resolvedProductsByKey[originalRequest.cacheKey()] = resolvedProduct
        }
    }

    private fun logUnresolvedRequest(
        request: AppActorStoreProductRequest,
        payloads: List<AppActorBillingProductDetailsPayload>,
    ) {
        val payloadTypes = payloads.mapTo(linkedSetOf()) { it.productType.name }
        AppActorLogger.warn(
            "[Billing] Unresolved Play product request productId=${request.productId} " +
                "resolutionHint=${request.resolutionKind().name.lowercase()} " +
                "productType=${request.productType.wireValue} " +
                "basePlanId=${request.basePlanId ?: "null"} offerId=${request.offerId ?: "null"} " +
                "returnedTypes=${payloadTypes.joinToString(",").ifBlank { "<none>" }}"
        )
    }

    private data class ResolvedProduct(
        val product: AppActorStoreProduct,
        val nativeProductDetails: com.android.billingclient.api.ProductDetails?,
        val offerToken: String?,
    ) {
        fun toRequest(obfuscatedAccountId: String? = null): AppActorStoreProductRequest {
            return AppActorStoreProductRequest(
                productId = product.productId,
                productType = product.productType,
                basePlanId = product.basePlanId,
                offerId = product.offerId,
                priceAmountMicros = product.priceAmountMicros,
                currencyCode = product.currencyCode,
                obfuscatedAccountId = obfuscatedAccountId,
            )
        }
    }
}

private enum class ProductResolutionKind {
    Subscription,
    OneTime,
    Ambiguous,
}

private fun AppActorStoreProductRequest.resolutionKind(): ProductResolutionKind {
    return when {
        !basePlanId.isNullOrBlank() || !offerId.isNullOrBlank() -> ProductResolutionKind.Subscription
        productType == AppActorProductType.Subscription -> ProductResolutionKind.Subscription
        productType == AppActorProductType.Consumable || productType == AppActorProductType.NonConsumable ->
            ProductResolutionKind.OneTime
        else -> ProductResolutionKind.Ambiguous
    }
}

private fun AppActorStoreProductRequest.cacheKey(): String {
    return listOf(productType.name, productId, basePlanId.orEmpty(), offerId.orEmpty()).joinToString("|")
}

private fun AppActorStoreProductRequest.fallbackRequestKey(): String {
    return AppActorStoreProductRequest(
        productId = productId,
        productType = productType,
    ).cacheKey()
}

private fun recoveryRequest(
    productId: String,
    payloadProductType: AppActorProductType,
    obfuscatedAccountId: String?,
): AppActorStoreProductRequest {
    return AppActorStoreProductRequest(
        productId = productId,
        productType = if (payloadProductType == AppActorProductType.Subscription) {
            AppActorProductType.Subscription
        } else {
            AppActorProductType.Unknown
        },
        obfuscatedAccountId = obfuscatedAccountId,
    )
}

private fun AppActorBillingPurchasePayload.toStorePurchase(
    request: AppActorStoreProductRequest,
): AppActorStorePurchase {
    return AppActorStorePurchase(
        productId = request.productId,
        productType = request.productType,
        purchaseToken = purchaseToken,
        orderId = orderId,
        purchaseTimeMillis = purchaseTimeMillis,
        purchaseState = purchaseState,
        basePlanId = request.basePlanId,
        offerId = request.offerId,
        priceAmountMicros = request.priceAmountMicros,
        currencyCode = request.currencyCode,
        isAcknowledged = isAcknowledged,
        isAutoRenewing = isAutoRenewing,
        obfuscatedAccountId = obfuscatedAccountId ?: request.obfuscatedAccountId,
        rawPurchaseData = rawPurchaseData,
        purchaseSignature = purchaseSignature,
    )
}

private fun AppActorBillingPurchaseHistoryPayload.toHistoryRecord(
    request: AppActorStoreProductRequest,
): AppActorStorePurchaseHistoryRecord {
    return AppActorStorePurchaseHistoryRecord(
        productId = request.productId,
        productType = request.productType,
        purchaseToken = purchaseToken,
        orderId = orderId,
        purchaseTimeMillis = purchaseTimeMillis,
        basePlanId = request.basePlanId,
        offerId = request.offerId,
        priceAmountMicros = request.priceAmountMicros,
        currencyCode = request.currencyCode,
        isAutoRenewing = isAutoRenewing,
        obfuscatedAccountId = obfuscatedAccountId ?: request.obfuscatedAccountId,
        rawPurchaseData = rawPurchaseData,
        purchaseSignature = purchaseSignature,
    )
}
