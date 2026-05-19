package com.appactor.android.pipeline

import android.app.Activity
import com.appactor.android.backend.client.AppActorBackendClient
import com.appactor.android.internal.logging.AppActorLogger
import com.appactor.android.backend.client.AppActorBackendException
import com.appactor.android.backend.dto.AppActorCustomerEnvelopeDTO
import com.appactor.android.backend.dto.AppActorGoogleBatchResultDTO
import com.appactor.android.backend.dto.AppActorGoogleReceiptResponseDTO
import com.appactor.android.backend.dto.AppActorGoogleRestorePurchaseDTO
import com.appactor.android.backend.dto.AppActorGoogleRestoreRequestDTO
import com.appactor.android.backend.dto.AppActorGoogleSyncRequestDTO
import com.appactor.android.billing.AppActorStoreAdapter
import com.appactor.android.billing.AppActorStorePurchase
import com.appactor.android.billing.AppActorStorePurchaseLaunchResult
import com.appactor.android.cache.AppActorOfflineProductCatalogStore
import com.appactor.android.managers.AppActorCustomerManager
import com.appactor.android.managers.AppActorOfferingsManager
import com.appactor.android.models.AppActorResolvedPurchaseTarget
import com.appactor.android.models.AppActorConfiguration
import com.appactor.android.models.AppActorCustomerInfo
import com.appactor.android.models.AppActorEntitlementInfo
import com.appactor.android.models.AppActorEntitlementKeyResolver
import com.appactor.android.models.AppActorEnvironment
import com.appactor.android.models.AppActorError
import com.appactor.android.models.AppActorOwnershipType
import com.appactor.android.models.AppActorPackage
import com.appactor.android.models.AppActorPeriodType
import com.appactor.android.models.AppActorProductType
import com.appactor.android.models.AppActorPurchaseInfo
import com.appactor.android.models.AppActorPurchaseResult
import com.appactor.android.models.AppActorReceiptPipelineEvent
import com.appactor.android.models.appActorGoogleObfuscatedAccountId
import com.appactor.android.models.AppActorSubscriptionReplacementMode
import com.appactor.android.models.AppActorStore
import com.appactor.android.models.AppActorSubscriptionStatus
import com.appactor.android.models.AppActorVerificationResult
import com.appactor.android.models.toResolvedPurchaseTarget
import com.appactor.android.storage.AppActorIdentityStore
import com.appactor.android.storage.AppActorAtomicJsonReceiptQueueStore
import com.appactor.android.storage.AppActorPostedLedgerStore
import com.appactor.android.storage.AppActorReceiptQueueItem
import com.appactor.android.storage.AppActorReceiptQueuePhase
import com.appactor.android.storage.AppActorReceiptQueueStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.appactor.android.models.appActorPublicReceiptId
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal data class AppActorPurchaseUpdateProcessingResult(
    val customerInfo: AppActorCustomerInfo?,
    val appUserId: String,
)

internal class AppActorPaymentProcessor(
    private val configuration: AppActorConfiguration,
    private val backendClient: AppActorBackendClient,
    private val storeAdapter: AppActorStoreAdapter,
    private val queueStore: AppActorReceiptQueueStore,
    private val postedLedgerStore: AppActorPostedLedgerStore,
    private val customerManager: AppActorCustomerManager,
    private val identityStore: AppActorIdentityStore,
    private val offeringsManager: AppActorOfferingsManager,
    private val offlineProductCatalogStore: AppActorOfflineProductCatalogStore,
    private val packageName: String,
    private val onPipelineEvent: (AppActorReceiptPipelineEvent) -> Unit = {},
    private val dateProviderMillis: () -> Long = { System.currentTimeMillis() },
    private val backgroundScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    private val purchaseMutex = Mutex()
    // Keep receipt reconciliation single-flight so purchase updates, startup sync,
    // restore, and foreground drains can't race each other.
    private val pipelineMutex = Mutex()
    private var retryWakeJob: Job? = null
    private var scheduledRetryAtMillis: Long? = null
    // Key: purchaseToken, Value: legacy "productId|timestampMillis" or
    // "productId|recordedAtMillis|attemptStartedAtMillis|attemptId".
    private val pendingPurchaseTokens = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val foregroundPurchaseProductExpiries = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val foregroundPurchaseProductContexts = java.util.concurrent.ConcurrentHashMap<String, AppActorClientPurchaseContext>()
    private val pendingPrefs = configuration.applicationContext.getSharedPreferences(
        PENDING_PREFS_NAME, android.content.Context.MODE_PRIVATE,
    )

    init {
        // Restore persisted pending purchase state from previous session
        val now = dateProviderMillis()
        pendingPrefs.all.forEach { (token, value) ->
            val entry = value as? String ?: return@forEach
            val pendingEntry = PendingPurchaseEntry.parse(entry) ?: return@forEach
            if (now - pendingEntry.recordedAtMillis < PENDING_EXPIRY_MILLIS) {
                pendingPurchaseTokens[token] = entry
            }
        }
    }

    @Volatile
    var onDeferredPurchaseResolved: ((productId: String, customerInfo: AppActorCustomerInfo) -> Unit)? = null

    // Identity transition buffering — prevents wrong-user attribution
    // when purchases arrive between drainAll() and identity switch.
    private val transitionMutex = Mutex()
    private var identityTransitionAppUserId: String? = null
    private val identityTransitionBuffer = mutableListOf<BufferedPurchase>()
    private val maxTransitionBufferSize = 50

    private data class BufferedPurchase(
        val purchase: AppActorStorePurchase,
        val capturedAppUserId: String,
        val purchaseUpdateContext: PurchaseUpdateContext,
    )

    private data class PurchaseUpdateContext(
        val sourceIntent: String,
        val clientPurchaseContext: AppActorClientPurchaseContext,
    )

    suspend fun beginIdentityTransition() {
        transitionMutex.withLock {
            identityTransitionAppUserId = identityStore.currentAppUserId
                ?: identityStore.ensureAppUserId()
        }
    }

    suspend fun endIdentityTransition(): List<AppActorPurchaseUpdateProcessingResult> {
        val buffered = transitionMutex.withLock {
            val items = identityTransitionBuffer.toList()
            identityTransitionBuffer.clear()
            identityTransitionAppUserId = null
            items
        }
        val currentAppUserId = identityStore.currentAppUserId
        return buffered.groupBy { it.capturedAppUserId }
            .mapNotNull { (userId, items) ->
                processPurchaseUpdatesInternal(
                    purchases = items.map { it.purchase },
                    appUserIdOverride = userId,
                    purchaseUpdateContextOverrides = items.associate { it.purchase.purchaseToken to it.purchaseUpdateContext },
                    emitDeferredPurchaseCallback = currentAppUserId == userId,
                )
            }
    }

    suspend fun purchase(
        activity: Activity,
        appActorPackage: AppActorPackage,
        appUserIdOverride: String? = null,
    ): AppActorPurchaseResult {
        if (!purchaseMutex.tryLock()) {
            throw AppActorError.InvalidConfiguration("Only one purchase can be in-flight at a time.")
        }

        try {
            require(appActorPackage.store == AppActorStore.PlayStore) {
                "Android purchases require a Play Store package."
            }
            val appUserId = appUserIdOverride
                ?.takeIf { it.isNotBlank() }
                ?: identityStore.currentAppUserId
                ?: identityStore.ensureAppUserId()
            return purchaseWithTarget(
                activity = activity,
                target = appActorPackage.toResolvedPurchaseTarget(appUserId),
                appUserIdOverride = appUserId,
            )
        } finally {
            purchaseMutex.unlock()
        }
    }

    suspend fun purchase(
        activity: Activity,
        params: com.appactor.android.models.AppActorPurchaseParams,
        appUserIdOverride: String? = null,
    ): AppActorPurchaseResult {
        if (!purchaseMutex.tryLock()) {
            throw AppActorError.InvalidConfiguration("Only one purchase can be in-flight at a time.")
        }
        try {
            val appUserId = appUserIdOverride
                ?.takeIf { it.isNotBlank() }
                ?: identityStore.currentAppUserId
                ?: identityStore.ensureAppUserId()
            return purchaseWithTarget(
                activity = activity,
                target = params.toResolvedPurchaseTarget(appUserId),
                appUserIdOverride = appUserId,
            )
        } finally {
            purchaseMutex.unlock()
        }
    }

    suspend fun processPurchaseUpdates(
        purchases: List<AppActorStorePurchase>,
        appUserIdOverride: String? = null,
    ): AppActorCustomerInfo? {
        return processPurchaseUpdatesInternal(
            purchases = purchases,
            appUserIdOverride = appUserIdOverride,
        )?.customerInfo
    }

    suspend fun processLivePurchaseUpdates(
        purchases: List<AppActorStorePurchase>,
    ): AppActorPurchaseUpdateProcessingResult? {
        return processPurchaseUpdatesInternal(
            purchases = purchases,
            appUserIdOverride = null,
        )
    }

    private suspend fun processPurchaseUpdatesInternal(
        purchases: List<AppActorStorePurchase>,
        appUserIdOverride: String? = null,
        purchaseUpdateContextOverrides: Map<String, PurchaseUpdateContext> = emptyMap(),
        emitDeferredPurchaseCallback: Boolean = true,
    ): AppActorPurchaseUpdateProcessingResult? {
        if (purchases.isEmpty()) return null
        if (appUserIdOverride == null) {
            val overflow = transitionMutex.withLock {
                val userId = identityTransitionAppUserId ?: return@withLock null
                val overflowPurchases = mutableListOf<BufferedPurchase>()
                purchases.forEach { purchase ->
                    val updateContext = purchaseUpdateContextOverrides[purchase.purchaseToken]
                        ?: resolvePurchaseUpdateContext(purchase)
                    if (identityTransitionBuffer.size < maxTransitionBufferSize) {
                        identityTransitionBuffer.add(BufferedPurchase(purchase, userId, updateContext))
                    } else {
                        overflowPurchases.add(BufferedPurchase(purchase, userId, updateContext))
                        AppActorLogger.warn("[PaymentProcessor] Transition buffer full ($maxTransitionBufferSize), purchase ${purchase.productId} will be processed immediately with captured identity")
                    }
                }
                overflowPurchases
            }
            if (overflow == null) {
                // Fall through — no identity transition active.
            } else if (overflow.isEmpty()) {
                return null
	            } else {
	                return processPurchaseUpdatesInternal(
	                    purchases = overflow.map { it.purchase },
	                    appUserIdOverride = overflow.first().capturedAppUserId,
	                    purchaseUpdateContextOverrides = overflow.associate { it.purchase.purchaseToken to it.purchaseUpdateContext },
	                    emitDeferredPurchaseCallback = emitDeferredPurchaseCallback,
	                )
            }
        }
        var processedAppUserId: String? = null
        val result = pipelineMutex.withLock {
            val appUserId = appUserIdOverride
                ?.takeIf { it.isNotBlank() }
                ?: identityStore.currentAppUserId
                ?: identityStore.ensureAppUserId()
            processedAppUserId = appUserId
            val productEntitlements = ensureProductEntitlements()
            var latestCustomer: AppActorCustomerInfo? = null
            purchases.forEach { purchase ->
                val updateContext = purchaseUpdateContextOverrides[purchase.purchaseToken]
                    ?: resolvePurchaseUpdateContext(purchase)
                when (
                    val outcome = enqueueAndProcess(
                        purchase = purchase,
                        productEntitlements = productEntitlements,
                        appUserIdOverride = appUserId,
                        sourceIntent = updateContext.sourceIntent,
                        clientPurchaseContext = updateContext.clientPurchaseContext,
                    )
                ) {
                    is ProcessingOutcome.Success -> {
                        latestCustomer = outcome.customerInfo
                        fireDeferredPurchaseCallbackIfNeeded(
                            purchase = purchase,
                            customerInfo = outcome.customerInfo,
                            emitCallback = emitDeferredPurchaseCallback,
                        )
                    }
                    is ProcessingOutcome.AlreadyPosted -> {
                        latestCustomer = outcome.customerInfo
                        fireDeferredPurchaseCallbackIfNeeded(
                            purchase = purchase,
                            customerInfo = outcome.customerInfo,
                            emitCallback = emitDeferredPurchaseCallback,
                        )
                    }
                    is ProcessingOutcome.Queued,
                    is ProcessingOutcome.PermanentFailure -> Unit
                }
            }
            latestCustomer
        }
        scheduleNextRetryWake()
        return AppActorPurchaseUpdateProcessingResult(
            customerInfo = result,
            appUserId = processedAppUserId ?: return null,
        )
    }

    private suspend fun purchaseWithTarget(
        activity: Activity,
        target: AppActorResolvedPurchaseTarget,
        appUserIdOverride: String? = null,
    ): AppActorPurchaseResult {
        val appUserId = appUserIdOverride
            ?.takeIf { it.isNotBlank() }
            ?: identityStore.currentAppUserId
            ?: identityStore.ensureAppUserId()
        val resolvedRequest = if (target.requiresStoreResolution) {
            storeAdapter.resolveDirectPurchaseRequest(target.request)
        } else {
            target.request
        }
        val foregroundProductId = resolvedRequest.productId
        val clientPurchaseContext = AppActorClientPurchaseContext.purchaseAttempt(dateProviderMillis())
        var keepForegroundMarker = false
        markForegroundPurchaseProduct(foregroundProductId, clientPurchaseContext = clientPurchaseContext)
        try {
            return when (val launchResult = storeAdapter.launchPurchase(activity, resolvedRequest)) {
                is AppActorStorePurchaseLaunchResult.Cancelled -> AppActorPurchaseResult.Cancelled
                is AppActorStorePurchaseLaunchResult.Pending -> {
                    val now = dateProviderMillis()
                    launchResult.purchases.forEach { purchase ->
                        pendingPurchaseTokens[purchase.purchaseToken] = clientPurchaseContext.toPendingEntry(
                            productId = purchase.productId,
                            recordedAtMillis = now,
                        )
                    }
                    persistPendingState()
                    AppActorPurchaseResult.Pending
                }
                is AppActorStorePurchaseLaunchResult.Failed -> throw when (launchResult.error) {
                    is AppActorError -> launchResult.error
                    else -> AppActorError.Network(
                        description = "Purchase failed.",
                        throwable = launchResult.error,
                    )
                }

	                is AppActorStorePurchaseLaunchResult.Purchased -> {
	                    val completedContext = clientPurchaseContext.withDeliverySource(
	                        AppActorClientDeliverySource.PurchaseFlow,
	                        observedAtMillis = dateProviderMillis(),
	                    )
	                    val purchaseResult = pipelineMutex.withLock {
                        val productEntitlements = ensureProductEntitlements()
                        val primaryPurchase = launchResult.purchases.firstOrNull { purchase ->
                            target.matches(purchase)
                        } ?: launchResult.purchases.first()
                        var primaryOutcome: ProcessingOutcome? = null
                        launchResult.purchases.forEach { purchase ->
                            val isPrimary = purchase.purchaseToken == primaryPurchase.purchaseToken
                            val outcome = enqueueAndProcess(
                                purchase = purchase,
                                productEntitlements = productEntitlements,
	                                appUserIdOverride = appUserId,
	                                offeringId = if (isPrimary) target.offeringId else null,
	                                packageId = if (isPrimary) target.packageId else null,
	                                clientPurchaseContext = completedContext,
	                            )
                            if (purchase.purchaseToken == primaryPurchase.purchaseToken) {
                                primaryOutcome = outcome
                            }
                        }

                        when (val outcome = requireNotNull(primaryOutcome)) {
                            is ProcessingOutcome.Success -> AppActorPurchaseResult.Success(
                                customerInfo = outcome.customerInfo,
                                purchaseInfo = primaryPurchase.toPurchaseInfo(configuration),
                            )

                            is ProcessingOutcome.Queued -> {
                                val offline = buildOfflineCustomerInfo(
                                    purchase = primaryPurchase,
                                    appUserId = appUserId,
                                    productEntitlements = productEntitlements,
                                ) ?: customerManager.cachedInfo(appUserId)
                                if (offline != null) {
                                    AppActorPurchaseResult.Success(
                                        customerInfo = offline,
                                        purchaseInfo = primaryPurchase.toPurchaseInfo(configuration),
                                    )
                                } else {
                                    throw AppActorError.Network(
                                        description = "Purchase succeeded but receipt is queued for retry.",
                                    )
                                }
                            }

                            is ProcessingOutcome.PermanentFailure -> throw AppActorError.Unknown(
                                description = outcome.message ?: "Receipt post failed permanently.",
                            )

                            is ProcessingOutcome.AlreadyPosted -> AppActorPurchaseResult.Success(
                                customerInfo = outcome.customerInfo,
                                purchaseInfo = primaryPurchase.toPurchaseInfo(configuration),
                            )
                        }
                    }
                    scheduleNextRetryWake()
                    purchaseResult
                }
            }
        } catch (error: CancellationException) {
            markForegroundPurchaseProduct(
                productId = foregroundProductId,
                ttlMillis = FOREGROUND_PURCHASE_CANCELLATION_GRACE_MILLIS,
            )
            keepForegroundMarker = true
            throw error
        } finally {
            if (!keepForegroundMarker) {
                clearForegroundPurchaseProduct(foregroundProductId)
            }
        }
    }

    suspend fun syncCurrentPurchases(
        limit: Int = 20,
        appUserIdOverride: String? = null,
        refreshEntitlementsIfMissing: Boolean = true,
    ): AppActorCustomerInfo? {
        val result = pipelineMutex.withLock {
            syncCurrentPurchasesLocked(
                limit = limit,
                appUserIdOverride = appUserIdOverride,
                refreshEntitlementsIfMissing = refreshEntitlementsIfMissing,
            )
        }
        scheduleNextRetryWake(limit)
        return result
    }

    private suspend fun syncCurrentPurchasesLocked(
        limit: Int = 20,
        appUserIdOverride: String? = null,
        excludedPurchaseTokens: Set<String> = emptySet(),
        refreshEntitlementsIfMissing: Boolean = true,
    ): AppActorCustomerInfo? {
        val appUserId = appUserIdOverride
            ?.takeIf { it.isNotBlank() }
            ?: identityStore.currentAppUserId
            ?: identityStore.ensureAppUserId()
        val productEntitlements = ensureProductEntitlements(
            refreshIfMissing = refreshEntitlementsIfMissing,
        )
        var latestCustomer: AppActorCustomerInfo? = null
        val syncCandidates = mutableListOf<AppActorStorePurchase>()

        val allProcessedPurchases = mutableListOf<AppActorStorePurchase>()

        storeAdapter.queryActivePurchases().forEach { purchase ->
            val normalized = normalizePurchaseForPosting(purchase)
            if (excludedPurchaseTokens.contains(normalized.purchaseToken)) {
                return@forEach
            }
            allProcessedPurchases += normalized
            val pendingUpdateContext = consumePendingPurchaseUpdateContext(normalized)
            if (pendingUpdateContext != null) {
                when (val outcome = enqueueAndProcess(
                    normalized,
                    productEntitlements,
                    appUserId,
                    sourceIntent = pendingUpdateContext.sourceIntent,
                    clientPurchaseContext = pendingUpdateContext.clientPurchaseContext,
                )) {
                    is ProcessingOutcome.Success -> {
                        latestCustomer = outcome.customerInfo
                        fireDeferredPurchaseCallbackIfNeeded(normalized, outcome.customerInfo)
                    }
                    is ProcessingOutcome.AlreadyPosted -> {
                        latestCustomer = outcome.customerInfo
                        fireDeferredPurchaseCallbackIfNeeded(normalized, outcome.customerInfo)
                    }
                    is ProcessingOutcome.Queued,
                    is ProcessingOutcome.PermanentFailure -> Unit
                }
                return@forEach
            }
            if (normalized.productType == AppActorProductType.Unknown) {
                when (val outcome = enqueueAndProcess(
                    normalized,
                    productEntitlements,
                    appUserId,
                    sourceIntent = SOURCE_INTENT_SYNC,
                    clientPurchaseContext = AppActorClientPurchaseContext.foregroundSync(dateProviderMillis()),
                )) {
                    is ProcessingOutcome.Success -> latestCustomer = outcome.customerInfo
                    is ProcessingOutcome.AlreadyPosted -> latestCustomer = outcome.customerInfo
                    is ProcessingOutcome.Queued,
                    is ProcessingOutcome.PermanentFailure -> Unit
                }
            } else {
                syncCandidates += normalized
            }
        }

        if (syncCandidates.isNotEmpty()) {
            try {
                val syncContext = AppActorClientPurchaseContext.foregroundSync(dateProviderMillis())
                val response = backendClient.postGoogleSync(
                    AppActorGoogleSyncRequestDTO(
                        appUserId = appUserId,
                        obfuscatedAccountId = appActorGoogleObfuscatedAccountId(appUserId),
                        obfuscatedProfileId = null,
                        sourceIntent = SOURCE_INTENT_SYNC,
                        source = "foreground_sync",
                        observedAt = isoNow(),
                        clientPurchaseAttemptStartedAt = syncContext.clientPurchaseAttemptStartedAt,
                        clientObservedAt = syncContext.clientObservedAt,
                        clientDeliverySource = syncContext.clientDeliverySource.wireValue,
                        clientPurchaseAttemptId = syncContext.clientPurchaseAttemptId,
                        sdkOriginated = syncContext.sdkOriginated,
                        sdkVersion = syncContext.sdkVersion,
                        purchases = syncCandidates.map { it.toRestorePurchaseDTO() },
                    )
                )
                val body = requireNotNull(response.body) { "Google sync response body was null." }
                val resolvedAppUserId = adoptResolvedAppUserId(
                    requestedAppUserId = appUserId,
                    resolvedAppUserId = body.appUserId,
                )
                customerManager.seedEnvelope(
                    appUserId = resolvedAppUserId,
                    envelope = AppActorCustomerEnvelopeDTO(
                        requestId = body.requestId,
                        appUserId = resolvedAppUserId,
                        customer = body.customer,
                    ),
                    eTag = null,
                    verified = response.signatureVerified,
                )
                val successfulPurchaseKeys = successfulBatchPurchaseKeys(
                    purchases = syncCandidates,
                    successCount = body.syncedCount,
                    results = body.results,
                )
                finalizeRestoredActivePurchases(
                    purchases = syncCandidates.filter { purchase ->
                        successfulPurchaseKeys.contains(batchPurchaseKey(purchase))
                    },
                    appUserId = resolvedAppUserId,
                )
                enqueueFailedBatchPurchases(
                    syncCandidates,
                    successfulPurchaseKeys,
                    productEntitlements,
                    resolvedAppUserId,
                    SOURCE_INTENT_SYNC,
                    syncContext,
                )
                latestCustomer = customerManager.cachedInfo(resolvedAppUserId)
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                syncCandidates.forEach { purchase ->
                    when (
                        val outcome = enqueueAndProcess(
                            purchase,
                            productEntitlements,
                            appUserId,
                            sourceIntent = SOURCE_INTENT_SYNC,
                            clientPurchaseContext = AppActorClientPurchaseContext.foregroundSync(dateProviderMillis()),
                        )
                    ) {
                        is ProcessingOutcome.Success -> latestCustomer = outcome.customerInfo
                        is ProcessingOutcome.AlreadyPosted -> latestCustomer = outcome.customerInfo
                        is ProcessingOutcome.Queued,
                        is ProcessingOutcome.PermanentFailure -> Unit
                    }
                }
            }
        }

        val drained = drainAllLocked(limit)
        if (drained != null) {
            latestCustomer = drained
        }

        // Fire deferred purchase callbacks for any pending purchases that were resolved during sync
        if (latestCustomer != null && pendingPurchaseTokens.isNotEmpty()) {
            allProcessedPurchases.forEach { purchase ->
                fireDeferredPurchaseCallbackIfNeeded(purchase, latestCustomer!!)
            }
        }

        return latestCustomer
    }

    suspend fun restorePurchases(
        maxPurchases: Int = 500,
        appUserIdOverride: String? = null,
    ): AppActorCustomerInfo {
        val result = pipelineMutex.withLock {
            restorePurchasesLocked(maxPurchases = maxPurchases, appUserIdOverride = appUserIdOverride)
        }
        scheduleNextRetryWake()
        return result
    }

    private suspend fun restorePurchasesLocked(
        maxPurchases: Int = 500,
        appUserIdOverride: String? = null,
    ): AppActorCustomerInfo {
        val batchSize = maxPurchases.coerceAtLeast(1)
        var currentAppUserId = appUserIdOverride
            ?.takeIf { it.isNotBlank() }
            ?: identityStore.currentAppUserId
            ?: identityStore.ensureAppUserId()
        val activePurchases = storeAdapter.queryActivePurchases()
        val historyPurchases = storeAdapter.queryPurchaseHistory()
        if (activePurchases.isEmpty() && historyPurchases.isEmpty()) {
            return customerManager.getCustomerInfo(currentAppUserId, forceRefresh = true)
        }

        val productEntitlements = ensureProductEntitlements()
        val restorePlan = buildRestorePlan(
            activePurchases = activePurchases,
            historyPurchases = historyPurchases,
            productEntitlements = productEntitlements,
        )
        if (restorePlan.bulkCandidates.isEmpty()) {
            val syncCustomer = if (restorePlan.followUpSyncRequired) {
                syncCurrentPurchasesLocked(limit = batchSize, appUserIdOverride = currentAppUserId)
            } else {
                null
            }
            currentAppUserId = identityStore.currentAppUserId ?: currentAppUserId
            if (syncCustomer != null) {
                return syncCustomer
            }
            return customerManager.getCustomerInfo(
                currentAppUserId,
                forceRefresh = true,
                persistIdentityState = false,
            )
        }

        val restoreBatches = restorePlan.bulkCandidates.chunked(batchSize)
        val shouldRunFollowUpSync = restorePlan.followUpSyncRequired

        var latestBatchCustomer: AppActorCustomerInfo? = null
        for (batchIndex in restoreBatches.indices) {
            val batch = restoreBatches[batchIndex]
            try {
                val restoreContext = AppActorClientPurchaseContext.restoreFlow(dateProviderMillis())
                val activeCandidates = batch
                    .filter { it.isActive }
                    .map { it.purchase }
                val response = backendClient.postGoogleRestore(
                    AppActorGoogleRestoreRequestDTO(
                        appUserId = currentAppUserId,
                        obfuscatedAccountId = appActorGoogleObfuscatedAccountId(currentAppUserId),
                        obfuscatedProfileId = null,
                        sourceIntent = SOURCE_INTENT_RESTORE,
                        source = "user_restore",
                        observedAt = isoNow(),
                        clientPurchaseAttemptStartedAt = restoreContext.clientPurchaseAttemptStartedAt,
                        clientObservedAt = restoreContext.clientObservedAt,
                        clientDeliverySource = restoreContext.clientDeliverySource.wireValue,
                        clientPurchaseAttemptId = restoreContext.clientPurchaseAttemptId,
                        sdkOriginated = restoreContext.sdkOriginated,
                        sdkVersion = restoreContext.sdkVersion,
                        purchases = batch.map { it.purchase.toRestorePurchaseDTO() },
                    )
                )
                val body = requireNotNull(response.body) { "Google restore response body was null." }
                val resolvedAppUserId = adoptResolvedAppUserId(
                    requestedAppUserId = currentAppUserId,
                    resolvedAppUserId = body.appUserId,
                )
                currentAppUserId = resolvedAppUserId
                customerManager.seedEnvelope(
                    appUserId = resolvedAppUserId,
                    envelope = AppActorCustomerEnvelopeDTO(
                        requestId = body.requestId,
                        appUserId = resolvedAppUserId,
                        customer = body.customer,
                    ),
                    eTag = null,
                    verified = response.signatureVerified,
                )
                val successfulPurchaseKeys = successfulBatchPurchaseKeys(
                    purchases = activeCandidates,
                    successCount = body.restoredCount,
                    results = body.results,
                )
                finalizeRestoredActivePurchases(
                    purchases = activeCandidates.filter { purchase ->
                        successfulPurchaseKeys.contains(batchPurchaseKey(purchase))
                    },
                    appUserId = resolvedAppUserId,
                )
                enqueueFailedBatchPurchases(
                    activeCandidates,
                    successfulPurchaseKeys,
                    productEntitlements,
                    resolvedAppUserId,
                    SOURCE_INTENT_RESTORE,
                    restoreContext,
                )
                latestBatchCustomer = customerManager.cachedInfo(resolvedAppUserId)
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                val fallbackCustomer = runCatching {
                    syncCurrentPurchasesLocked(limit = batchSize, appUserIdOverride = currentAppUserId)
                    customerManager.getCustomerInfo(
                        currentAppUserId,
                        forceRefresh = true,
                        persistIdentityState = false,
                    )
                }
                val remainingHistoryRestore = restoreBatches
                    .subList(batchIndex, restoreBatches.size)
                    .any { remainingBatch -> remainingBatch.any { !it.isActive } }
                if (remainingHistoryRestore) {
                    fallbackCustomer.exceptionOrNull()?.let(throwable::addSuppressed)
                    throw restoreFailure(
                        throwable,
                        "Failed to restore full Google Play purchase history."
                    )
                }
                return fallbackCustomer.getOrElse { syncThrowable ->
                    throw restoreFailure(
                        syncThrowable,
                        "Failed to restore Google Play purchases."
                    )
                }
            }
        }
        val followUpCustomer = if (shouldRunFollowUpSync) {
            syncCurrentPurchasesLocked(
                limit = batchSize,
                appUserIdOverride = currentAppUserId,
                excludedPurchaseTokens = restorePlan.restoredActivePurchaseTokens,
            )
        } else {
            null
        }
        return followUpCustomer
            ?: latestBatchCustomer
            ?: customerManager.getCustomerInfo(
                currentAppUserId,
                forceRefresh = true,
                persistIdentityState = false,
            )
    }

    suspend fun drainReadyQueue(limit: Int = 20): AppActorCustomerInfo? {
        val result = pipelineMutex.withLock {
            drainReadyQueueLocked(limit)
        }
        scheduleNextRetryWake(limit)
        return result
    }

    private suspend fun drainReadyQueueLocked(limit: Int = 20): AppActorCustomerInfo? {
        val now = dateProviderMillis()
        if (activeRateLimitCooldown(now) != null) return null
        val claimed = queueStore.claimReady(limit = limit, nowMillis = now)
        if (claimed.isEmpty()) return null

        val productEntitlements = ensureProductEntitlements()
        var latestCustomer: AppActorCustomerInfo? = null
        claimed.forEach { item ->
            when (val outcome = processClaimedItem(item, productEntitlements)) {
                is ProcessingOutcome.Success -> latestCustomer = outcome.customerInfo
                is ProcessingOutcome.AlreadyPosted -> latestCustomer = outcome.customerInfo
                is ProcessingOutcome.Queued,
                is ProcessingOutcome.PermanentFailure -> Unit
            }
        }
        return latestCustomer
    }

    suspend fun drainAll(
        limit: Int = 20,
    ): AppActorCustomerInfo? {
        val result = pipelineMutex.withLock {
            drainAllLocked(limit)
        }
        scheduleNextRetryWake(limit)
        return result
    }

    /**
     * Re-enqueues dead-lettered receipts whose product type has been resolved,
     * giving them a fresh retry cycle. Called once per session at startup so
     * that transient backend failures from a previous session get another chance
     * when conditions may have changed (network restored, backend fixed, etc.).
     */
    suspend fun retryDeadLetteredItems(
        limit: Int = 20,
    ): AppActorCustomerInfo? {
        val items = queueStore.consumeDeadLettered()
        if (items.isEmpty()) return null
        val now = dateProviderMillis()
        val revived = items.map { item ->
            item.copy(
                phase = AppActorReceiptQueuePhase.NeedsPost,
                retryCount = 0,
                nextRetryAtMillis = now,
                claimedAtMillis = null,
                lastUpdatedAtMillis = now,
                lastError = null,
                clientDeliverySource = if (item.clientDeliverySource != null) {
                    AppActorClientDeliverySource.QueueRetry.wireValue
                } else {
                    null
                },
            )
        }
        queueStore.upsertAll(revived)
        revived.forEach { item ->
            onPipelineEvent(
                AppActorReceiptPipelineEvent.RetryScheduled(
                    key = appActorPublicReceiptId(item.key),
                    productId = item.productId,
                    retryCount = 0,
                    nextRetryAtMillis = now,
                    errorCode = "launch_retry",
                    appUserId = item.appUserId,
                    orderId = item.orderId,
                )
            )
        }
        val result = pipelineMutex.withLock {
            drainAllLocked(limit)
        }
        scheduleNextRetryWake(limit)
        return result
    }

    private suspend fun drainAllLocked(
        limit: Int = 20,
    ): AppActorCustomerInfo? {
        var latestCustomer: AppActorCustomerInfo? = null
        while (true) {
            val drained = drainReadyQueueLocked(limit)
            if (drained != null) {
                latestCustomer = drained
            }
            if (drained == null || !hasReadyWork()) {
                break
            }
        }
        return latestCustomer
    }

    private suspend fun enqueueAndProcess(
        purchase: AppActorStorePurchase,
        productEntitlements: Map<String, List<String>>,
        appUserIdOverride: String? = null,
        offeringId: String? = null,
        packageId: String? = null,
        sourceIntent: String = SOURCE_INTENT_PURCHASE,
        clientPurchaseContext: AppActorClientPurchaseContext? = null,
    ): ProcessingOutcome {
        val normalizedPurchase = normalizePurchaseForPosting(purchase)
        val item = makeQueueItem(
            normalizedPurchase,
            appUserIdOverride,
            offeringId,
            packageId,
            sourceIntent,
            clientPurchaseContext,
        )
        val existing = queueStore.get(item.key)
        if (existing?.phase == AppActorReceiptQueuePhase.DeadLettered) {
            val revived = reviveRecoverableDeadLetter(
                existing = existing,
                incoming = item,
                productEntitlements = productEntitlements,
            )
            if (revived != null) {
                queueStore.update(revived)
                onPipelineEvent(
                    AppActorReceiptPipelineEvent.RetryScheduled(
                        key = appActorPublicReceiptId(revived.key),
                        productId = revived.productId,
                        retryCount = revived.retryCount,
                        nextRetryAtMillis = revived.nextRetryAtMillis,
                        errorCode = "revived_unknown_product_type",
                        appUserId = revived.appUserId,
                        orderId = revived.orderId,
                    )
                )
                return processClaimedItem(
                    revived.copy(
                        phase = AppActorReceiptQueuePhase.Posting,
                        claimedAtMillis = dateProviderMillis(),
                    ),
                    productEntitlements,
                )
            }
            onPipelineEvent(
                AppActorReceiptPipelineEvent.DeadLettered(
                    key = appActorPublicReceiptId(existing.key),
                    productId = existing.productId,
                    retryCount = existing.retryCount,
                    lastError = existing.lastError,
                    appUserId = existing.appUserId,
                    orderId = existing.orderId,
                )
            )
            return ProcessingOutcome.PermanentFailure(
                code = "dead_lettered",
                message = existing.lastError,
            )
        }
        queueStore.upsert(item)
        val mergedItem = queueStore.get(item.key) ?: item
        return processClaimedItem(mergedItem.copy(phase = AppActorReceiptQueuePhase.Posting), productEntitlements)
    }

    private suspend fun reviveRecoverableDeadLetter(
        existing: AppActorReceiptQueueItem,
        incoming: AppActorReceiptQueueItem,
        productEntitlements: Map<String, List<String>>,
    ): AppActorReceiptQueueItem? {
        if (existing.productType != AppActorProductType.Unknown.wireValue) {
            return null
        }

        val now = dateProviderMillis()
        val adoptClientContext = shouldAdoptDeadLetterClientPurchaseContext(existing, incoming)
        val baseline = existing.copy(
            appUserId = incoming.appUserId,
            environment = incoming.environment,
            purchaseState = incoming.purchaseState,
            orderId = incoming.orderId ?: existing.orderId,
            obfuscatedAccountId = incoming.obfuscatedAccountId ?: existing.obfuscatedAccountId,
            rawPurchaseData = incoming.rawPurchaseData ?: existing.rawPurchaseData,
            purchaseSignature = incoming.purchaseSignature ?: existing.purchaseSignature,
            isAutoRenewing = incoming.isAutoRenewing ?: existing.isAutoRenewing,
            priceAmountMicros = incoming.priceAmountMicros ?: existing.priceAmountMicros,
            currencyCode = incoming.currencyCode ?: existing.currencyCode,
            offeringId = incoming.offeringId ?: existing.offeringId,
            packageId = incoming.packageId ?: existing.packageId,
            sourceIntent = mergeDeadLetterSourceIntent(existing, incoming),
            clientPurchaseAttemptStartedAt = if (adoptClientContext) {
                incoming.clientPurchaseAttemptStartedAt
            } else {
                existing.clientPurchaseAttemptStartedAt
            },
            clientObservedAt = if (adoptClientContext) incoming.clientObservedAt else existing.clientObservedAt,
            clientDeliverySource = if (adoptClientContext) incoming.clientDeliverySource else existing.clientDeliverySource,
            clientPurchaseAttemptId = if (adoptClientContext) incoming.clientPurchaseAttemptId else existing.clientPurchaseAttemptId,
            sdkOriginated = if (adoptClientContext) incoming.sdkOriginated else existing.sdkOriginated,
            sdkVersion = if (adoptClientContext) incoming.sdkVersion else existing.sdkVersion,
            isAcknowledged = existing.isAcknowledged || incoming.isAcknowledged,
            retryCount = 0,
            nextRetryAtMillis = 0L,
            claimedAtMillis = null,
            phase = AppActorReceiptQueuePhase.NeedsPost,
            lastUpdatedAtMillis = now,
            lastError = null,
        )

        val resolved = if (incoming.productType != AppActorProductType.Unknown.wireValue) {
            baseline.copy(
                productType = incoming.productType,
                basePlanId = incoming.basePlanId,
                offerId = incoming.offerId,
            )
        } else {
            normalizeQueueItemForPosting(baseline, productEntitlements)
        }

        return resolved.takeIf { it.productType != AppActorProductType.Unknown.wireValue }
    }

    private fun mergeDeadLetterSourceIntent(
        existing: AppActorReceiptQueueItem,
        incoming: AppActorReceiptQueueItem,
    ): String {
        val priority = mapOf(
            SOURCE_INTENT_QUEUE to 0,
            SOURCE_INTENT_SYNC to 1,
            SOURCE_INTENT_RESTORE to 2,
            SOURCE_INTENT_PURCHASE to 3,
        )
        val existingPriority = priority[existing.sourceIntent] ?: 0
        val incomingPriority = priority[incoming.sourceIntent] ?: 0
        return if (incomingPriority > existingPriority) incoming.sourceIntent else existing.sourceIntent
    }

    private fun shouldAdoptDeadLetterClientPurchaseContext(
        existing: AppActorReceiptQueueItem,
        incoming: AppActorReceiptQueueItem,
    ): Boolean {
        val incomingHasAttempt = incoming.clientPurchaseAttemptStartedAt != null &&
            !incoming.clientPurchaseAttemptId.isNullOrBlank()
        val incomingHasAnyContext = incoming.clientDeliverySource != null ||
            incoming.clientPurchaseAttemptStartedAt != null ||
            incoming.clientPurchaseAttemptId != null ||
            incoming.clientObservedAt != null
        if (!incomingHasAnyContext) return false

        val existingHasAnyContext = existing.clientDeliverySource != null ||
            existing.clientPurchaseAttemptStartedAt != null ||
            existing.clientPurchaseAttemptId != null ||
            existing.clientObservedAt != null
        if (!existingHasAnyContext) {
            return incomingHasAttempt || incoming.clientDeliverySource != AppActorClientDeliverySource.TransactionUpdates.wireValue
        }

        val existingHasAttempt = existing.clientPurchaseAttemptStartedAt != null &&
            !existing.clientPurchaseAttemptId.isNullOrBlank()
        if (incomingHasAttempt && !existingHasAttempt) return true
        return incomingHasAttempt &&
            incoming.clientDeliverySource == AppActorClientDeliverySource.PurchaseFlow.wireValue &&
            existing.clientDeliverySource != AppActorClientDeliverySource.PurchaseFlow.wireValue
    }

    private suspend fun buildRestorePlan(
        activePurchases: List<AppActorStorePurchase>,
        historyPurchases: List<com.appactor.android.billing.AppActorStorePurchaseHistoryRecord>,
        productEntitlements: Map<String, List<String>>,
    ): RestorePlan {
        val normalizedHistory = LinkedHashMap<String, AppActorStorePurchase>()
        historyPurchases.forEach { historyRecord ->
            val normalized = normalizeHistoryPurchaseForRestore(historyRecord, productEntitlements)
            normalizedHistory[restoreDedupKey(normalized)] = normalized
        }

        val normalizedActive = LinkedHashMap<String, AppActorStorePurchase>()
        activePurchases.forEach { activePurchase ->
            val normalized = normalizePurchaseForPosting(activePurchase)
            normalizedActive[restoreDedupKey(normalized)] = normalized
        }

        val orderedKeys = linkedSetOf<String>().apply {
            addAll(normalizedHistory.keys)
            addAll(normalizedActive.keys)
        }
        val bulkCandidates = mutableListOf<RestoreCandidate>()
        var followUpSyncRequired = false

        orderedKeys.forEach { key ->
            val active = normalizedActive[key]
            val history = normalizedHistory[key]
            if (active != null) {
                val merged = mergeRestorePurchases(active = active, history = history)
                if (merged.productType == AppActorProductType.Unknown) {
                    followUpSyncRequired = true
                } else {
                    bulkCandidates += RestoreCandidate(
                        purchase = merged,
                        isActive = true,
                    )
                }
            } else if (history != null && history.productType != AppActorProductType.Unknown) {
                bulkCandidates += RestoreCandidate(
                    purchase = history,
                    isActive = false,
                )
            } else if (history != null) {
                // History-only purchase with Unknown type — cannot be sent in bulk
                // restore. Defer to follow-up sync which will pick it up if the
                // product metadata becomes available.
                followUpSyncRequired = true
            }
        }

        return RestorePlan(
            bulkCandidates = bulkCandidates,
            followUpSyncRequired = followUpSyncRequired,
            restoredActivePurchaseTokens = bulkCandidates
                .asSequence()
                .filter { it.isActive }
                .map { it.purchase.purchaseToken }
                .toSet(),
        )
    }

    private suspend fun normalizeHistoryPurchaseForRestore(
        historyRecord: com.appactor.android.billing.AppActorStorePurchaseHistoryRecord,
        productEntitlements: Map<String, List<String>>,
    ): AppActorStorePurchase {
        val historyPurchase = historyRecord.toStorePurchase()
        if (historyPurchase.productType != AppActorProductType.Unknown) {
            return historyPurchase
        }
        return resolveUnknownOneTimePurchase(
            purchase = historyPurchase,
            productEntitlements = productEntitlements,
        ) ?: historyPurchase
    }

    private fun mergeRestorePurchases(
        active: AppActorStorePurchase,
        history: AppActorStorePurchase?,
    ): AppActorStorePurchase {
        if (history == null) return active
        return active.copy(
            productType = if (active.productType != AppActorProductType.Unknown) {
                active.productType
            } else {
                history.productType
            },
            orderId = active.orderId ?: history.orderId,
            basePlanId = active.basePlanId ?: history.basePlanId,
            offerId = active.offerId ?: history.offerId,
            priceAmountMicros = active.priceAmountMicros ?: history.priceAmountMicros,
            currencyCode = active.currencyCode ?: history.currencyCode,
            isAutoRenewing = active.isAutoRenewing ?: history.isAutoRenewing,
            obfuscatedAccountId = active.obfuscatedAccountId ?: history.obfuscatedAccountId,
            rawPurchaseData = active.rawPurchaseData ?: history.rawPurchaseData,
            purchaseSignature = active.purchaseSignature ?: history.purchaseSignature,
        )
    }

    private suspend fun finalizeRestoredActivePurchases(
        purchases: List<AppActorStorePurchase>,
        appUserId: String,
    ) {
        purchases.forEach { purchase ->
            val finishItem = buildRestoreFinishItem(
                purchase = purchase,
                appUserId = appUserId,
            )
            postedLedgerStore.markPosted(finishItem.key)
            queueStore.update(finishItem)
            val finished = finalizePostedPurchase(finishItem)
            if (finished) {
                queueStore.remove(finishItem.key)
            } else {
                queueStore.update(
                    finishItem.copy(
                        nextRetryAtMillis = dateProviderMillis(),
                        lastUpdatedAtMillis = dateProviderMillis(),
                    )
                )
            }
        }
    }

    /**
     * Enqueue purchases that failed in a batch sync/restore individually so they
     * enter the normal pipeline and are eventually finalized at Google. Without
     * this, partial-success batches leave purchases unacknowledged and Google
     * auto-refunds them after 3 days.
     */
    private suspend fun enqueueFailedBatchPurchases(
        candidates: List<AppActorStorePurchase>,
        successfulKeys: Set<String>,
        productEntitlements: Map<String, List<String>>,
        appUserId: String,
        sourceIntent: String,
        clientPurchaseContext: AppActorClientPurchaseContext,
    ) {
        candidates.filter { !successfulKeys.contains(batchPurchaseKey(it)) }
            .forEach {
                enqueueAndProcess(
                    it,
                    productEntitlements,
                    appUserId,
                    sourceIntent = sourceIntent,
                    clientPurchaseContext = clientPurchaseContext,
                )
            }
    }

    private fun adoptResolvedAppUserId(
        requestedAppUserId: String,
        resolvedAppUserId: String?,
    ): String {
        val finalAppUserId = resolvedAppUserId?.takeIf { it.isNotBlank() } ?: requestedAppUserId
        if (finalAppUserId != requestedAppUserId) {
            customerManager.clearCache(requestedAppUserId)
            identityStore.setAppUserId(finalAppUserId)
        }
        return finalAppUserId
    }

    private fun buildRestoreFinishItem(
        purchase: AppActorStorePurchase,
        appUserId: String,
    ): AppActorReceiptQueueItem {
        val now = dateProviderMillis()
        val restoreItem = makeQueueItem(
            purchase = purchase,
            appUserIdOverride = appUserId,
            sourceIntent = SOURCE_INTENT_RESTORE,
        ).copy(
            shouldAcknowledge = purchase.productType == AppActorProductType.Subscription ||
                purchase.productType == AppActorProductType.NonConsumable,
            shouldConsume = purchase.productType == AppActorProductType.Consumable,
            phase = AppActorReceiptQueuePhase.NeedsFinish,
            nextRetryAtMillis = 0L,
            claimedAtMillis = null,
            lastUpdatedAtMillis = now,
            lastError = null,
        )
        val existing = queueStore.get(restoreItem.key) ?: return restoreItem
        return existing.copy(
            appUserId = restoreItem.appUserId,
            packageName = restoreItem.packageName,
            environment = restoreItem.environment,
            productId = restoreItem.productId,
            productType = if (restoreItem.productType != AppActorProductType.Unknown.wireValue) {
                restoreItem.productType
            } else {
                existing.productType
            },
            purchaseToken = restoreItem.purchaseToken,
            purchaseTime = restoreItem.purchaseTime,
            purchaseState = restoreItem.purchaseState,
            orderId = restoreItem.orderId ?: existing.orderId,
            basePlanId = restoreItem.basePlanId ?: existing.basePlanId,
            offerId = restoreItem.offerId ?: existing.offerId,
            priceAmountMicros = restoreItem.priceAmountMicros ?: existing.priceAmountMicros,
            currencyCode = restoreItem.currencyCode ?: existing.currencyCode,
            isAutoRenewing = restoreItem.isAutoRenewing ?: existing.isAutoRenewing,
            obfuscatedAccountId = restoreItem.obfuscatedAccountId ?: existing.obfuscatedAccountId,
            sourceIntent = existing.sourceIntent,
            idempotencyKey = restoreItem.idempotencyKey,
            rawPurchaseData = restoreItem.rawPurchaseData ?: existing.rawPurchaseData,
            purchaseSignature = restoreItem.purchaseSignature ?: existing.purchaseSignature,
            clientPurchaseAttemptStartedAt = restoreItem.clientPurchaseAttemptStartedAt ?: existing.clientPurchaseAttemptStartedAt,
            clientObservedAt = restoreItem.clientObservedAt ?: existing.clientObservedAt,
            clientDeliverySource = restoreItem.clientDeliverySource ?: existing.clientDeliverySource,
            clientPurchaseAttemptId = restoreItem.clientPurchaseAttemptId ?: existing.clientPurchaseAttemptId,
            sdkOriginated = restoreItem.sdkOriginated ?: existing.sdkOriginated,
            sdkVersion = restoreItem.sdkVersion ?: existing.sdkVersion,
            isAcknowledged = existing.isAcknowledged || restoreItem.isAcknowledged,
            shouldAcknowledge = existing.shouldAcknowledge || restoreItem.shouldAcknowledge,
            shouldConsume = existing.shouldConsume || restoreItem.shouldConsume,
            retryCount = existing.retryCount,
            nextRetryAtMillis = 0L,
            createdAtMillis = existing.createdAtMillis,
            lastUpdatedAtMillis = now,
            claimedAtMillis = null,
            phase = AppActorReceiptQueuePhase.NeedsFinish,
            lastError = null,
        )
    }

    private suspend fun processClaimedItem(
        item: AppActorReceiptQueueItem,
        productEntitlements: Map<String, List<String>>,
    ): ProcessingOutcome {
        val normalizedItem = normalizeQueueItemForPosting(item, productEntitlements)
        if (normalizedItem.productType == AppActorProductType.Unknown.wireValue) {
            return scheduleRetryOrDeadLetter(
                item = normalizedItem,
                retryAfterSeconds = null,
                errorCode = "unknown_product_type",
                errorMessage = "Unable to resolve Google Play one-time product type for ${normalizedItem.productId}.",
            )
        }

        if (normalizedItem != item) {
            queueStore.update(normalizedItem)
        }

        if (postedLedgerStore.isPosted(normalizedItem.key)) {
            return finishAlreadyPostedItem(normalizedItem, productEntitlements)
        }

        return try {
            val response = backendClient.postGoogleReceipt(
                AppActorReceiptRequestBuilder.buildGoogleReceiptRequest(normalizedItem)
            )
            val body = requireNotNull(response.body) { "Google receipt response body was null." }
            when (val result = body.toPipelineStatus()) {
                is ReceiptPipelineStatus.Success -> {
                    val customerDTO = requireNotNull(body.customer) {
                        "Receipt response success was missing customer info."
                    }
                    postedLedgerStore.markPosted(normalizedItem.key)
                    customerManager.seedEnvelope(
                        appUserId = normalizedItem.appUserId,
                        envelope = AppActorCustomerEnvelopeDTO(
                            requestId = result.requestId,
                            appUserId = normalizedItem.appUserId,
                            customer = customerDTO,
                        ),
                        eTag = null,
                        verified = response.signatureVerified,
                    )
                    val customerInfo = customerManager.cachedInfo(normalizedItem.appUserId)
                        ?: throw IllegalStateException("Receipt response success was missing customer info.")
                    val finishItem = normalizedItem.copy(
                        shouldAcknowledge = result.acknowledgePurchase,
                        shouldConsume = result.consumePurchase,
                        phase = AppActorReceiptQueuePhase.NeedsFinish,
                        claimedAtMillis = null,
                        lastUpdatedAtMillis = dateProviderMillis(),
                    )
                    val finished = finalizePostedPurchase(finishItem)
                    if (finished) {
                        queueStore.remove(normalizedItem.key)
                    } else {
                        queueStore.update(finishItem.copy(nextRetryAtMillis = dateProviderMillis()))
                    }
                    onPipelineEvent(
                        AppActorReceiptPipelineEvent.PostedOk(
                            key = appActorPublicReceiptId(normalizedItem.key),
                            productId = normalizedItem.productId,
                            requestId = result.requestId,
                            appUserId = normalizedItem.appUserId,
                            orderId = normalizedItem.orderId,
                        )
                    )
                    ProcessingOutcome.Success(customerInfo = customerInfo)
                }

                is ReceiptPipelineStatus.RetryableError -> {
                    scheduleRetryOrDeadLetter(
                        item = normalizedItem,
                        retryAfterSeconds = result.retryAfterSeconds,
                        errorCode = result.errorCode,
                        errorMessage = result.errorMessage,
                    )
                }

                is ReceiptPipelineStatus.PermanentError -> {
                    deadLetter(
                        item = normalizedItem,
                        code = result.errorCode,
                        message = result.errorMessage,
                    )
                    ProcessingOutcome.PermanentFailure(
                        code = result.errorCode,
                        message = result.errorMessage,
                    )
                }
            }
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            if (throwable is AppActorBackendException.Http && throwable.statusCode in 400..499) {
                deadLetter(
                    item = normalizedItem,
                    code = throwable.error?.code ?: throwable.statusCode.toString(),
                    message = throwable.error?.message ?: throwable.message,
                )
                ProcessingOutcome.PermanentFailure(
                    code = throwable.error?.code,
                    message = throwable.error?.message ?: throwable.message,
                )
            } else {
                scheduleRetryOrDeadLetter(
                    item = normalizedItem,
                    retryAfterSeconds = null,
                    errorCode = (throwable as? AppActorBackendException.Http)?.error?.code,
                    errorMessage = throwable.message,
                )
            }
        }
    }

    private suspend fun finishAlreadyPostedItem(
        item: AppActorReceiptQueueItem,
        productEntitlements: Map<String, List<String>>,
    ): ProcessingOutcome {
        val finished = finalizePostedPurchase(item)
        if (finished) {
            queueStore.remove(item.key)
        } else {
            scheduleRetryOrDeadLetter(
                item = item,
                retryAfterSeconds = null,
                errorCode = "finish_failed",
                errorMessage = "Failed to finalize an already-posted purchase.",
            )
        }

        val authoritative = runCatching {
            customerManager.getCustomerInfo(
                item.appUserId,
                forceRefresh = true,
                persistIdentityState = false,
            )
        }.getOrNull()
            ?: customerManager.cachedInfo(item.appUserId)
            ?: buildOfflineCustomerInfo(
                purchase = item.toStorePurchase(),
                appUserId = item.appUserId,
                productEntitlements = productEntitlements,
            )
            ?: AppActorCustomerInfo.empty

        onPipelineEvent(
            AppActorReceiptPipelineEvent.DuplicateSkipped(
                key = appActorPublicReceiptId(item.key),
                productId = item.productId,
                appUserId = item.appUserId,
            )
        )

        return ProcessingOutcome.AlreadyPosted(authoritative)
    }

    private suspend fun finalizePostedPurchase(item: AppActorReceiptQueueItem): Boolean {
        return runCatching {
            if (item.shouldConsume) {
                storeAdapter.consumePurchase(item.purchaseToken)
            } else if (item.shouldAcknowledge && !item.isAcknowledged) {
                storeAdapter.acknowledgePurchase(item.purchaseToken)
            }
            true
        }.getOrElse { false }
    }

    private suspend fun scheduleRetryOrDeadLetter(
        item: AppActorReceiptQueueItem,
        retryAfterSeconds: Double?,
        errorCode: String?,
        errorMessage: String?,
    ): ProcessingOutcome {
        val now = dateProviderMillis()
        val nextRetryCount = item.retryCount + 1
        val lastError = buildLastError(errorCode, errorMessage, nextRetryCount)
        val nextRetryAt = AppActorRetryPolicy.nextRetryAtMillis(
            nowMillis = now,
            retryCount = nextRetryCount,
            retryAfterSeconds = retryAfterSeconds,
        )
        if (errorCode == "RATE_LIMIT" || errorCode == "RATE_LIMIT_EXCEEDED") {
            queueStore.setRateLimitCooldownMillis(nextRetryAt)
        }
        val updated = item.copy(
            retryCount = nextRetryCount,
            nextRetryAtMillis = nextRetryAt,
            claimedAtMillis = null,
            phase = if (postedLedgerStore.isPosted(item.key)) {
                AppActorReceiptQueuePhase.NeedsFinish
            } else {
                AppActorReceiptQueuePhase.NeedsPost
            },
            lastUpdatedAtMillis = now,
            lastError = lastError,
        )
        queueStore.update(updated)
        onPipelineEvent(
            AppActorReceiptPipelineEvent.RetryScheduled(
                key = appActorPublicReceiptId(updated.key),
                productId = updated.productId,
                retryCount = updated.retryCount,
                nextRetryAtMillis = updated.nextRetryAtMillis,
                errorCode = errorCode,
                appUserId = updated.appUserId,
                orderId = updated.orderId,
            )
        )
        scheduleNextRetryWake()
        return ProcessingOutcome.Queued
    }

    private suspend fun deadLetter(
        item: AppActorReceiptQueueItem,
        code: String?,
        message: String?,
    ) {
        val finalized = finalizeDeadLetteredPurchase(item)
        if (finalized) {
            postedLedgerStore.markPosted(item.key)
        }
        val updated = item.copy(
            phase = AppActorReceiptQueuePhase.DeadLettered,
            claimedAtMillis = null,
            nextRetryAtMillis = 0L,
            lastUpdatedAtMillis = dateProviderMillis(),
            lastError = buildDeadLetterError(
                code = code,
                message = message,
                retryCount = item.retryCount,
                finalized = finalized,
            ),
        )
        queueStore.update(updated)
        onPipelineEvent(
            AppActorReceiptPipelineEvent.PermanentlyRejected(
                key = appActorPublicReceiptId(updated.key),
                productId = updated.productId,
                code = code,
                message = message,
                appUserId = updated.appUserId,
                orderId = updated.orderId,
            )
        )
        onPipelineEvent(
            AppActorReceiptPipelineEvent.DeadLettered(
                key = appActorPublicReceiptId(updated.key),
                productId = updated.productId,
                retryCount = updated.retryCount,
                lastError = updated.lastError,
                appUserId = updated.appUserId,
                orderId = updated.orderId,
            )
        )
        scheduleNextRetryWake()
    }

    private suspend fun finalizeDeadLetteredPurchase(item: AppActorReceiptQueueItem): Boolean {
        val shouldConsume = item.shouldConsume || item.productType == AppActorProductType.Consumable.wireValue
        val shouldAcknowledge = item.shouldAcknowledge ||
            (item.productType == AppActorProductType.Subscription.wireValue ||
                item.productType == AppActorProductType.NonConsumable.wireValue)

        if (!shouldConsume && (!shouldAcknowledge || item.isAcknowledged)) {
            return item.productType != AppActorProductType.Unknown.wireValue
        }

        return runCatching {
            if (shouldConsume) {
                storeAdapter.consumePurchase(item.purchaseToken)
            } else if (!item.isAcknowledged) {
                storeAdapter.acknowledgePurchase(item.purchaseToken)
            }
            true
        }.getOrElse { false }
    }

    private fun buildLastError(
        code: String?,
        message: String?,
        retryCount: Int,
    ): String? {
        val resolved = listOfNotNull(code?.takeIf { it.isNotBlank() }, message?.takeIf { it.isNotBlank() })
            .joinToString(": ")
            .ifBlank { null }
        return when {
            resolved == null -> null
            else -> resolved
        }
    }

    private fun buildDeadLetterError(
        code: String?,
        message: String?,
        retryCount: Int,
        finalized: Boolean,
    ): String? {
        val base = buildLastError(code, message, retryCount)
        return when {
            base == null && finalized -> "dead-letter finalized locally"
            base == null -> "dead-letter finalization failed"
            finalized -> "$base (finalized locally)"
            else -> "$base (local finalization failed)"
        }
    }

    private fun fireDeferredPurchaseCallbackIfNeeded(
        purchase: AppActorStorePurchase,
        customerInfo: AppActorCustomerInfo,
        emitCallback: Boolean = true,
    ) {
        var resolvedProductId: String? = null
        pendingPurchaseTokens.compute(purchase.purchaseToken) { _, entry ->
            if (entry == null) return@compute null
            val pendingEntry = PendingPurchaseEntry.parse(entry) ?: return@compute null
            if (dateProviderMillis() - pendingEntry.recordedAtMillis > PENDING_EXPIRY_MILLIS) {
                null // Expired — stale entry from abandoned pending purchase
            } else {
                resolvedProductId = pendingEntry.productId
                null // Remove — resolved
            }
        }
        if (resolvedProductId != null) {
            persistPendingState()
            if (emitCallback) {
                onDeferredPurchaseResolved?.invoke(resolvedProductId!!, customerInfo)
            }
        }
    }

    private fun markForegroundPurchaseProduct(
        productId: String,
        ttlMillis: Long = FOREGROUND_PURCHASE_EXPIRY_MILLIS,
        clientPurchaseContext: AppActorClientPurchaseContext? = null,
    ) {
        val now = dateProviderMillis()
        foregroundPurchaseProductExpiries.entries.removeIf { it.value <= now }
        foregroundPurchaseProductExpiries[productId] = now + ttlMillis
        if (clientPurchaseContext != null) {
            foregroundPurchaseProductContexts[productId] = clientPurchaseContext
        }
    }

    private fun clearForegroundPurchaseProduct(productId: String) {
        foregroundPurchaseProductExpiries.remove(productId)
        foregroundPurchaseProductContexts.remove(productId)
    }

    private fun consumeRecentForegroundPurchaseContext(productId: String): AppActorClientPurchaseContext? {
        val expiresAt = foregroundPurchaseProductExpiries[productId] ?: return null
        if (dateProviderMillis() >= expiresAt) {
            foregroundPurchaseProductExpiries.remove(productId)
            foregroundPurchaseProductContexts.remove(productId)
            return null
        }
        foregroundPurchaseProductExpiries.remove(productId)
        return foregroundPurchaseProductContexts.remove(productId)
    }

    private fun resolvePurchaseUpdateContext(purchase: AppActorStorePurchase): PurchaseUpdateContext {
        val pendingContext = consumePendingPurchaseUpdateContext(purchase)
        if (pendingContext != null) {
            return pendingContext
        }

        val foregroundContext = consumeRecentForegroundPurchaseContext(purchase.productId)
        if (foregroundContext != null) {
            return PurchaseUpdateContext(
                sourceIntent = SOURCE_INTENT_PURCHASE,
                clientPurchaseContext = foregroundContext.withDeliverySource(
                    AppActorClientDeliverySource.TransactionUpdates,
                    observedAtMillis = dateProviderMillis(),
                ),
            )
        }

        return PurchaseUpdateContext(
            sourceIntent = SOURCE_INTENT_QUEUE,
            clientPurchaseContext = AppActorClientPurchaseContext.transactionUpdates(dateProviderMillis()),
        )
    }

    private fun consumePendingPurchaseUpdateContext(purchase: AppActorStorePurchase): PurchaseUpdateContext? {
        val rawEntry = pendingPurchaseTokens[purchase.purchaseToken] ?: return null
        val entry = PendingPurchaseEntry.parse(rawEntry)
        val now = dateProviderMillis()
        if (entry != null && now - entry.recordedAtMillis <= PENDING_EXPIRY_MILLIS) {
            return PurchaseUpdateContext(
                sourceIntent = SOURCE_INTENT_PURCHASE,
                clientPurchaseContext = AppActorClientPurchaseContext.fromPendingEntry(entry, observedAtMillis = now)
                    ?: AppActorClientPurchaseContext.transactionUpdates(now),
            )
        }
        pendingPurchaseTokens.remove(purchase.purchaseToken)
        persistPendingState()
        return null
    }

    private fun persistPendingState() {
        pendingPrefs.edit().apply {
            clear()
            pendingPurchaseTokens.forEach { (token, entry) -> putString(token, entry) }
            apply()
        }
    }

    private suspend fun ensureProductEntitlements(
        refreshIfMissing: Boolean = true,
    ): Map<String, List<String>> {
        val existing = offeringsManager.currentProductEntitlements()
        if (existing.isNotEmpty() || !refreshIfMissing) {
            return existing
        }

        runCatching { offeringsManager.getOfferings(forceRefresh = false) }
        return offeringsManager.currentProductEntitlements()
    }

    private suspend fun normalizePurchaseForPosting(
        purchase: AppActorStorePurchase,
    ): AppActorStorePurchase {
        if (purchase.productType != AppActorProductType.Unknown) {
            return purchase
        }
        return resolveUnknownOneTimePurchase(
            purchase = purchase,
            productEntitlements = ensureProductEntitlements(),
        ) ?: purchase
    }

    private suspend fun normalizeQueueItemForPosting(
        item: AppActorReceiptQueueItem,
        productEntitlements: Map<String, List<String>>,
    ): AppActorReceiptQueueItem {
        if (item.productType != AppActorProductType.Unknown.wireValue) {
            return item
        }
        val resolvedPurchase = resolveUnknownOneTimePurchase(
            purchase = item.toStorePurchase(),
            productEntitlements = productEntitlements,
        ) ?: return item

        return item.copy(
            productType = resolvedPurchase.productType.wireValue,
            basePlanId = resolvedPurchase.basePlanId,
            offerId = resolvedPurchase.offerId,
            priceAmountMicros = resolvedPurchase.priceAmountMicros ?: item.priceAmountMicros,
            currencyCode = resolvedPurchase.currencyCode ?: item.currencyCode,
            obfuscatedAccountId = resolvedPurchase.obfuscatedAccountId ?: item.obfuscatedAccountId,
            isAcknowledged = resolvedPurchase.isAcknowledged,
        )
    }

    private suspend fun resolveUnknownOneTimePurchase(
        purchase: AppActorStorePurchase,
        productEntitlements: Map<String, List<String>>,
    ): AppActorStorePurchase? {
        val resolvedType = resolveKnownOneTimeProductType(
            productId = purchase.productId,
            productEntitlements = productEntitlements,
        ) ?: return null

        return purchase.copy(productType = resolvedType)
    }

    private fun resolveKnownOneTimeProductType(
        productId: String,
        productEntitlements: Map<String, List<String>>,
    ): AppActorProductType? {
        offeringsManager.currentOneTimeProductType(productId)
            ?.let { return it }

        offlineProductCatalogStore.load()
            ?.oneTimeProductType(productId)
            ?.let { return it }

        val keyMatches = productEntitlements.keys
            .asSequence()
            .filter { key -> key == "android:$productId" }
            .mapNotNull { _ ->
                AppActorProductType.NonConsumable
            }
            .distinct()
            .toList()

        return if (keyMatches.size == 1) {
            keyMatches.single()
        } else {
            null
        }
    }

    private fun hasReadyWork(nowMillis: Long = dateProviderMillis()): Boolean {
        if (activeRateLimitCooldown(nowMillis) != null) return false
        val stalePostingThreshold = nowMillis - AppActorAtomicJsonReceiptQueueStore.STALE_CLAIM_THRESHOLD_MILLIS
        return queueStore.snapshot().any { item ->
            when (item.phase) {
                AppActorReceiptQueuePhase.NeedsPost -> item.nextRetryAtMillis <= nowMillis
                AppActorReceiptQueuePhase.Posting -> (item.claimedAtMillis ?: 0L) <= stalePostingThreshold
                AppActorReceiptQueuePhase.NeedsFinish -> item.nextRetryAtMillis <= nowMillis
                AppActorReceiptQueuePhase.DeadLettered -> false
            }
        }
    }

    private fun nextReadyAtMillis(nowMillis: Long = dateProviderMillis()): Long? {
        val stalePostingThreshold = nowMillis - AppActorAtomicJsonReceiptQueueStore.STALE_CLAIM_THRESHOLD_MILLIS
        val itemNextReady = queueStore.snapshot()
            .mapNotNull { item ->
                when (item.phase) {
                    AppActorReceiptQueuePhase.DeadLettered -> null

                    AppActorReceiptQueuePhase.NeedsPost,
                    AppActorReceiptQueuePhase.NeedsFinish -> item.nextRetryAtMillis

                    AppActorReceiptQueuePhase.Posting -> {
                        val claimedAt = item.claimedAtMillis ?: return@mapNotNull nowMillis
                        if (claimedAt <= stalePostingThreshold) {
                            nowMillis
                        } else {
                            claimedAt + AppActorAtomicJsonReceiptQueueStore.STALE_CLAIM_THRESHOLD_MILLIS
                        }
                    }
                }
            }
            .minOrNull()

        val cooldown = activeRateLimitCooldown(nowMillis)
        return when {
            itemNextReady == null -> cooldown
            cooldown == null -> itemNextReady
            cooldown > nowMillis && itemNextReady < cooldown -> cooldown
            else -> minOf(itemNextReady, cooldown)
        }
    }

    private fun activeRateLimitCooldown(nowMillis: Long = dateProviderMillis()): Long? {
        val cooldown = queueStore.getRateLimitCooldownMillis() ?: return null
        if (cooldown <= nowMillis) {
            queueStore.setRateLimitCooldownMillis(null)
            return null
        }
        return cooldown
    }

    private fun scheduleNextRetryWake(limit: Int = 20) {
        val now = dateProviderMillis()
        val nextReadyAt = nextReadyAtMillis(now) ?: run {
            retryWakeJob?.cancel()
            retryWakeJob = null
            scheduledRetryAtMillis = null
            return
        }

        if (nextReadyAt <= now) {
            val runningImmediateDrain = scheduledRetryAtMillis == null && retryWakeJob?.isActive == true
            if (runningImmediateDrain) {
                return
            }
            retryWakeJob?.cancel()
            scheduledRetryAtMillis = null
            retryWakeJob = backgroundScope.launch {
                pipelineMutex.withLock {
                    drainAllLocked(limit)
                }
                retryWakeJob = null
                scheduleNextRetryWake(limit)
            }
            return
        }

        if (scheduledRetryAtMillis == nextReadyAt && retryWakeJob?.isActive == true) {
            return
        }

        retryWakeJob?.cancel()
        scheduledRetryAtMillis = nextReadyAt
        val delayMillis = maxOf(nextReadyAt - now, 250L)
        retryWakeJob = backgroundScope.launch {
            delay(delayMillis)
            pipelineMutex.withLock {
                drainAllLocked(limit)
            }
            retryWakeJob = null
            scheduledRetryAtMillis = null
            scheduleNextRetryWake(limit)
        }
    }

    private fun buildOfflineCustomerInfo(
        purchase: AppActorStorePurchase,
        appUserId: String,
        productEntitlements: Map<String, List<String>>,
    ): AppActorCustomerInfo? {
        val keys = entitlementKeysForPurchase(purchase, productEntitlements)
        if (keys.isEmpty()) return null

        val entitlements = linkedMapOf<String, AppActorEntitlementInfo>().apply {
            keys.forEach { key ->
                put(
                    key,
                    AppActorEntitlementInfo(
                        identifier = key,
                        isActive = true,
                        status = "active",
                        productIdentifier = purchase.productId,
                        grantedBy = "purchase",
                        ownershipType = AppActorOwnershipType.Purchased,
                        periodType = AppActorPeriodType.Normal,
                        willRenew = purchase.productType == AppActorProductType.Subscription,
                        subscriptionStatus = AppActorSubscriptionStatus.Active,
                        store = AppActorStore.PlayStore,
                        basePlanId = purchase.basePlanId,
                        offerId = purchase.offerId,
                        isSandbox = configuration.environment == AppActorEnvironment.Sandbox,
                        purchaseDate = purchase.purchaseDateString(),
                        startsAt = purchase.purchaseDateString(),
                        latestPurchaseDate = purchase.purchaseDateString(),
                    )
                )
            }
        }

        return AppActorCustomerInfo(
            entitlements = entitlements,
            appUserId = appUserId,
            snapshotDate = purchase.purchaseDateString(),
            requestDate = purchase.purchaseDateString(),
            isComputedOffline = true,
            productEntitlements = productEntitlements,
            verification = AppActorVerificationResult.VerifiedOnDevice,
        )
    }

    private fun entitlementKeysForPurchase(
        purchase: AppActorStorePurchase,
        productEntitlements: Map<String, List<String>>,
    ): List<String> {
        return AppActorEntitlementKeyResolver.entitlementKeysForPurchase(
            purchase = purchase,
            productEntitlements = productEntitlements,
        )
    }

    private fun makeQueueItem(
        purchase: AppActorStorePurchase,
        appUserIdOverride: String? = null,
        offeringId: String? = null,
        packageId: String? = null,
        sourceIntent: String = SOURCE_INTENT_PURCHASE,
        clientPurchaseContext: AppActorClientPurchaseContext? = null,
    ): AppActorReceiptQueueItem {
        val appUserId = appUserIdOverride?.takeIf { it.isNotBlank() }
            ?: identityStore.currentAppUserId
            ?: identityStore.ensureAppUserId()
        val now = dateProviderMillis()
        return AppActorReceiptQueueItem(
            key = AppActorReceiptQueueItem.makeKey(
                purchaseToken = purchase.purchaseToken,
                productId = purchase.productId,
                basePlanId = purchase.basePlanId,
            ),
            appUserId = appUserId,
            packageName = packageName,
            environment = AppActorReceiptRequestBuilder.environmentWireValue(configuration.environment),
            productId = purchase.productId,
            productType = purchase.productType.wireValue,
            purchaseToken = purchase.purchaseToken,
            purchaseTime = purchase.purchaseTimeMillis.toString(),
            purchaseState = when (purchase.purchaseState) {
                com.appactor.android.billing.AppActorStorePurchaseState.Purchased -> "PURCHASED"
                com.appactor.android.billing.AppActorStorePurchaseState.Pending -> "PENDING"
                com.appactor.android.billing.AppActorStorePurchaseState.Unknown -> "UNKNOWN"
            },
            orderId = purchase.orderId,
            basePlanId = purchase.basePlanId,
            offerId = purchase.offerId,
            priceAmountMicros = purchase.priceAmountMicros,
            currencyCode = purchase.currencyCode,
            isAutoRenewing = purchase.isAutoRenewing,
            obfuscatedAccountId = purchase.obfuscatedAccountId,
            sourceIntent = sourceIntent,
            idempotencyKey = "google:${purchase.productId}:${purchase.basePlanId.orEmpty()}:${purchase.purchaseToken}",
            rawPurchaseData = purchase.rawPurchaseData,
            purchaseSignature = purchase.purchaseSignature,
            countryCode = storeAdapter.currentStorefront()?.countryCode,
            clientPurchaseAttemptStartedAt = clientPurchaseContext?.clientPurchaseAttemptStartedAt,
            clientObservedAt = clientPurchaseContext?.clientObservedAt,
            clientDeliverySource = clientPurchaseContext?.clientDeliverySource?.wireValue,
            clientPurchaseAttemptId = clientPurchaseContext?.clientPurchaseAttemptId,
            sdkOriginated = clientPurchaseContext?.sdkOriginated,
            sdkVersion = clientPurchaseContext?.sdkVersion,
            isAcknowledged = purchase.isAcknowledged,
            createdAtMillis = now,
            lastUpdatedAtMillis = now,
            phase = AppActorReceiptQueuePhase.NeedsPost,
            lastError = null,
            offeringId = offeringId,
            packageId = packageId,
        )
    }

    private sealed interface ProcessingOutcome {
        data class Success(val customerInfo: AppActorCustomerInfo) : ProcessingOutcome
        data object Queued : ProcessingOutcome
        data class PermanentFailure(
            val code: String?,
            val message: String?,
        ) : ProcessingOutcome

        data class AlreadyPosted(
            val customerInfo: AppActorCustomerInfo,
        ) : ProcessingOutcome
    }

    private data class RestoreCandidate(
        val purchase: AppActorStorePurchase,
        val isActive: Boolean,
    )

    private data class RestorePlan(
        val bulkCandidates: List<RestoreCandidate>,
        val followUpSyncRequired: Boolean,
        val restoredActivePurchaseTokens: Set<String>,
    )

    sealed interface ReceiptPipelineStatus {
        val requestId: String?

        data class Success(
            override val requestId: String?,
            val acknowledgePurchase: Boolean,
            val consumePurchase: Boolean,
        ) : ReceiptPipelineStatus

        data class RetryableError(
            override val requestId: String?,
            val errorCode: String?,
            val errorMessage: String?,
            val retryAfterSeconds: Double?,
        ) : ReceiptPipelineStatus

        data class PermanentError(
            override val requestId: String?,
            val errorCode: String?,
            val errorMessage: String?,
        ) : ReceiptPipelineStatus
    }

    private companion object {
        const val PENDING_PREFS_NAME = "com.appactor.android.pending_purchases"
        const val PENDING_EXPIRY_MILLIS: Long = 7 * 24 * 60 * 60 * 1_000L // 7 days
        const val FOREGROUND_PURCHASE_EXPIRY_MILLIS: Long = 10 * 60 * 1_000L // 10 minutes
        const val FOREGROUND_PURCHASE_CANCELLATION_GRACE_MILLIS: Long = 30 * 1_000L // 30 seconds
        const val SOURCE_INTENT_PURCHASE = "purchase"
        const val SOURCE_INTENT_RESTORE = "restore"
        const val SOURCE_INTENT_SYNC = "sync"
        const val SOURCE_INTENT_QUEUE = "queue"
    }
}

private fun restoreFailure(
    throwable: Throwable,
    defaultMessage: String,
): AppActorError {
    return when (throwable) {
        is AppActorError -> throwable
        is AppActorBackendException.Network -> AppActorError.Network(defaultMessage, throwable)
        else -> AppActorError.Unknown(defaultMessage, throwable)
    }
}

private fun AppActorGoogleReceiptResponseDTO.toPipelineStatus(): AppActorPaymentProcessor.ReceiptPipelineStatus {
    return when (status) {
        "ok" -> AppActorPaymentProcessor.ReceiptPipelineStatus.Success(
            requestId = requestId,
            acknowledgePurchase = acknowledgePurchase ?: false,
            consumePurchase = consumePurchase ?: false,
        )

        "retryable_error" -> AppActorPaymentProcessor.ReceiptPipelineStatus.RetryableError(
            requestId = requestId,
            errorCode = error?.code,
            errorMessage = error?.message,
            retryAfterSeconds = retryAfterSeconds,
        )

        else -> AppActorPaymentProcessor.ReceiptPipelineStatus.PermanentError(
            requestId = requestId,
            errorCode = error?.code,
            errorMessage = error?.message,
        )
    }
}

private fun AppActorStorePurchase.toPurchaseInfo(
    configuration: AppActorConfiguration,
): AppActorPurchaseInfo {
    return AppActorPurchaseInfo(
        store = AppActorStore.PlayStore,
        productId = productId,
        transactionId = orderId ?: purchaseToken,
        originalTransactionId = purchaseToken,
        purchaseDate = purchaseDateString(),
        isSandbox = configuration.environment == AppActorEnvironment.Sandbox,
    )
}

private fun AppActorReceiptQueueItem.toStorePurchase(): AppActorStorePurchase {
    return AppActorStorePurchase(
        productId = productId,
        productType = AppActorProductType.fromWireValue(productType),
        purchaseToken = purchaseToken,
        orderId = orderId,
        purchaseTimeMillis = purchaseTime.toLongOrNull() ?: 0L,
        purchaseState = when (purchaseState) {
            "PURCHASED" -> com.appactor.android.billing.AppActorStorePurchaseState.Purchased
            "PENDING" -> com.appactor.android.billing.AppActorStorePurchaseState.Pending
            else -> com.appactor.android.billing.AppActorStorePurchaseState.Unknown
        },
        basePlanId = basePlanId,
        offerId = offerId,
        priceAmountMicros = priceAmountMicros,
        currencyCode = currencyCode,
        isAcknowledged = isAcknowledged,
        isAutoRenewing = isAutoRenewing,
        obfuscatedAccountId = obfuscatedAccountId,
        rawPurchaseData = rawPurchaseData,
        purchaseSignature = purchaseSignature,
    )
}

private fun AppActorStorePurchase.purchaseDateString(): String {
    val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    formatter.timeZone = TimeZone.getTimeZone("UTC")
    return formatter.format(Date(purchaseTimeMillis))
}

private fun AppActorStorePurchase.toRestorePurchaseDTO(): AppActorGoogleRestorePurchaseDTO {
    return AppActorGoogleRestorePurchaseDTO(
        productId = productId,
        productType = productType.wireValue,
        purchaseToken = purchaseToken,
        purchaseTime = purchaseTimeMillis.toString(),
        orderId = orderId,
        basePlanId = basePlanId,
        offerId = offerId,
        priceAmountMicros = priceAmountMicros,
        currency = currencyCode,
        isAutoRenewing = isAutoRenewing,
    )
}

private fun isoNow(): String = java.time.Instant.now().toString()

private fun com.appactor.android.billing.AppActorStorePurchaseHistoryRecord.toStorePurchase(): AppActorStorePurchase {
    return AppActorStorePurchase(
        productId = productId,
        productType = productType,
        purchaseToken = purchaseToken,
        orderId = orderId,
        purchaseTimeMillis = purchaseTimeMillis,
        purchaseState = com.appactor.android.billing.AppActorStorePurchaseState.Purchased,
        basePlanId = basePlanId,
        offerId = offerId,
        priceAmountMicros = priceAmountMicros,
        currencyCode = currencyCode,
        isAcknowledged = false,
        isAutoRenewing = isAutoRenewing,
        obfuscatedAccountId = obfuscatedAccountId,
        rawPurchaseData = rawPurchaseData,
        purchaseSignature = purchaseSignature,
    )
}

private fun restoreDedupKey(purchase: AppActorStorePurchase): String {
    return listOf(
        purchase.purchaseToken,
        purchase.productId,
        purchase.purchaseTimeMillis.toString(),
    ).joinToString("|")
}

private fun batchPurchaseKey(purchase: AppActorStorePurchase): String {
    return listOf(
        purchase.purchaseToken,
        purchase.productId,
        purchase.basePlanId.orEmpty(),
        purchase.offerId.orEmpty(),
    ).joinToString("|")
}

private fun batchPurchaseKey(result: AppActorGoogleBatchResultDTO): String {
    return listOf(
        result.purchaseToken,
        result.productId,
        result.basePlanId.orEmpty(),
        result.offerId.orEmpty(),
    ).joinToString("|")
}

private fun successfulBatchPurchaseKeys(
    purchases: List<AppActorStorePurchase>,
    successCount: Int,
    results: List<AppActorGoogleBatchResultDTO>,
): Set<String> {
    if (results.isNotEmpty()) {
        return results
            .asSequence()
            .filter { it.status == "synced" }
            .map(::batchPurchaseKey)
            .toSet()
    }

    if (successCount < purchases.size) {
        return emptySet()
    }

    return purchases.asSequence().map(::batchPurchaseKey).toSet()
}
