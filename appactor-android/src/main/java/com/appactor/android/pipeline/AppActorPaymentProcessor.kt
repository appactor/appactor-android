package com.appactor.android.pipeline

import android.app.Activity
import com.appactor.android.backend.client.AppActorBackendClient
import com.appactor.android.backend.client.AppActorBackendException
import com.appactor.android.backend.dto.AppActorCustomerEnvelopeDTO
import com.appactor.android.backend.dto.AppActorGoogleBatchResultDTO
import com.appactor.android.backend.dto.AppActorGoogleReceiptResponseDTO
import com.appactor.android.backend.dto.AppActorGoogleRestorePurchaseDTO
import com.appactor.android.backend.dto.AppActorGoogleRestoreRequestDTO
import com.appactor.android.backend.dto.AppActorGoogleSyncRequestDTO
import com.appactor.android.billing.AppActorStoreAdapter
import com.appactor.android.billing.AppActorStoreProductRequest
import com.appactor.android.billing.AppActorStorePurchase
import com.appactor.android.billing.AppActorStorePurchaseLaunchResult
import com.appactor.android.billing.toBillingReplacementMode
import com.appactor.android.managers.AppActorCustomerManager
import com.appactor.android.managers.AppActorOfferingsManager
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
import com.appactor.android.models.AppActorSubscriptionReplacementMode
import com.appactor.android.models.AppActorStore
import com.appactor.android.models.AppActorSubscriptionStatus
import com.appactor.android.storage.AppActorIdentityStore
import com.appactor.android.storage.AppActorAtomicJsonReceiptQueueStore
import com.appactor.android.storage.AppActorPostedLedgerStore
import com.appactor.android.storage.AppActorReceiptQueueItem
import com.appactor.android.storage.AppActorReceiptQueuePhase
import com.appactor.android.storage.AppActorReceiptQueueStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.appactor.android.models.appActorPublicReceiptId
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal class AppActorPaymentProcessor(
    private val configuration: AppActorConfiguration,
    private val backendClient: AppActorBackendClient,
    private val storeAdapter: AppActorStoreAdapter,
    private val queueStore: AppActorReceiptQueueStore,
    private val postedLedgerStore: AppActorPostedLedgerStore,
    private val customerManager: AppActorCustomerManager,
    private val identityStore: AppActorIdentityStore,
    private val offeringsManager: AppActorOfferingsManager,
    private val packageName: String,
    private val onPipelineEvent: (AppActorReceiptPipelineEvent) -> Unit = {},
    private val dateProviderMillis: () -> Long = { System.currentTimeMillis() },
) {

    private val purchaseMutex = Mutex()
    // Keep receipt reconciliation single-flight so purchase updates, startup sync,
    // restore, and foreground drains can't race each other.
    private val pipelineMutex = Mutex()
    private val identityGate = CompletableDeferred<Unit>()
    // Key: purchaseToken, Value: "productId|timestampMillis"
    private val pendingPurchaseTokens = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val pendingPrefs = configuration.applicationContext.getSharedPreferences(
        PENDING_PREFS_NAME, android.content.Context.MODE_PRIVATE,
    )

    init {
        // Restore persisted pending purchase state from previous session
        val now = dateProviderMillis()
        pendingPrefs.all.forEach { (token, value) ->
            val entry = value as? String ?: return@forEach
            val timestamp = entry.substringAfterLast('|', "0").toLongOrNull() ?: return@forEach
            if (now - timestamp < PENDING_EXPIRY_MILLIS) {
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
    )

    suspend fun beginIdentityTransition() {
        transitionMutex.withLock {
            identityTransitionAppUserId = identityStore.currentAppUserId
                ?: identityStore.ensureAppUserId()
        }
    }

    suspend fun endIdentityTransition() {
        val buffered = transitionMutex.withLock {
            val items = identityTransitionBuffer.toList()
            identityTransitionBuffer.clear()
            identityTransitionAppUserId = null
            items
        }
        buffered.groupBy { it.capturedAppUserId }
            .forEach { (userId, items) ->
                processPurchaseUpdates(
                    purchases = items.map { it.purchase },
                    appUserIdOverride = userId,
                )
            }
    }

    fun confirmIdentity() {
        identityGate.complete(Unit)
    }

    private suspend fun waitForIdentity() {
        identityGate.await()
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
            val request = AppActorStoreProductRequest(
                productId = appActorPackage.productId,
                productType = appActorPackage.productType,
                basePlanId = appActorPackage.basePlanId,
                offerId = appActorPackage.offerId,
                obfuscatedAccountId = googleObfuscatedAccountId(appUserId),
                oldPurchaseToken = appActorPackage.oldPurchaseToken,
                replacementMode = appActorPackage.replacementMode?.toBillingReplacementMode(),
            )
            return purchaseWithRequest(
                activity = activity,
                request = request,
                expectedPrimaryProductId = appActorPackage.productId,
                appUserIdOverride = appUserId,
                offeringId = appActorPackage.offeringId,
                packageId = appActorPackage.id,
            )
        } finally {
            purchaseMutex.unlock()
        }
    }

    suspend fun purchase(
        activity: Activity,
        productId: String,
        appUserIdOverride: String? = null,
    ): AppActorPurchaseResult {
        val appUserId = appUserIdOverride
            ?.takeIf { it.isNotBlank() }
            ?: identityStore.currentAppUserId
            ?: identityStore.ensureAppUserId()
        val offerings = runCatching {
            offeringsManager.getOfferings(forceRefresh = false)
        }.getOrNull()
        val packageMatch = offerings?.all?.values
            ?.asSequence()
            ?.flatMap { it.packages.asSequence() }
            ?.firstOrNull { it.productId == productId }

        return if (packageMatch != null) {
            purchase(activity, packageMatch, appUserId)
        } else {
            if (!purchaseMutex.tryLock()) {
                throw AppActorError.InvalidConfiguration("Only one purchase can be in-flight at a time.")
            }
            try {
                val request = storeAdapter.resolveDirectPurchaseRequest(
                    productId = productId,
                    obfuscatedAccountId = googleObfuscatedAccountId(appUserId),
                )
                purchaseWithRequest(
                    activity = activity,
                    request = request,
                    expectedPrimaryProductId = productId,
                    appUserIdOverride = appUserId,
                )
            } finally {
                purchaseMutex.unlock()
            }
        }
    }

    suspend fun processPurchaseUpdates(
        purchases: List<AppActorStorePurchase>,
        appUserIdOverride: String? = null,
    ): AppActorCustomerInfo? {
        if (appUserIdOverride == null) {
            val buffered = transitionMutex.withLock {
                val userId = identityTransitionAppUserId ?: return@withLock false
                purchases.forEach { purchase ->
                    if (identityTransitionBuffer.size < maxTransitionBufferSize) {
                        identityTransitionBuffer.add(BufferedPurchase(purchase, userId))
                    }
                }
                true
            }
            if (buffered) return null
        }
        return pipelineMutex.withLock {
            if (purchases.isEmpty()) return@withLock null
            val appUserId = appUserIdOverride
                ?.takeIf { it.isNotBlank() }
                ?: identityStore.currentAppUserId
                ?: identityStore.ensureAppUserId()
            val productEntitlements = ensureProductEntitlements()
            var latestCustomer: AppActorCustomerInfo? = null
            purchases.forEach { purchase ->
                when (val outcome = enqueueAndProcess(purchase, productEntitlements, appUserId)) {
                    is ProcessingOutcome.Success -> {
                        latestCustomer = outcome.customerInfo
                        fireDeferredPurchaseCallbackIfNeeded(purchase, outcome.customerInfo)
                    }
                    is ProcessingOutcome.AlreadyPosted -> {
                        latestCustomer = outcome.customerInfo
                        fireDeferredPurchaseCallbackIfNeeded(purchase, outcome.customerInfo)
                    }
                    is ProcessingOutcome.Queued,
                    is ProcessingOutcome.PermanentFailure -> Unit
                }
            }
            latestCustomer
        }
    }

    private suspend fun purchaseWithRequest(
        activity: Activity,
        request: AppActorStoreProductRequest,
        expectedPrimaryProductId: String,
        appUserIdOverride: String? = null,
        offeringId: String? = null,
        packageId: String? = null,
    ): AppActorPurchaseResult {
        val appUserId = appUserIdOverride
            ?.takeIf { it.isNotBlank() }
            ?: identityStore.currentAppUserId
            ?: identityStore.ensureAppUserId()
        return when (val launchResult = storeAdapter.launchPurchase(activity, request)) {
                is AppActorStorePurchaseLaunchResult.Cancelled -> AppActorPurchaseResult.Cancelled
                is AppActorStorePurchaseLaunchResult.Pending -> {
                    val now = dateProviderMillis()
                    launchResult.purchases.forEach { purchase ->
                        pendingPurchaseTokens[purchase.purchaseToken] = "${purchase.productId}|$now"
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
                    pipelineMutex.withLock {
                        val productEntitlements = ensureProductEntitlements()
                        val primaryPurchase = launchResult.purchases.firstOrNull { purchase ->
                            purchase.productId == expectedPrimaryProductId
                        } ?: launchResult.purchases.first()
                        var primaryOutcome: ProcessingOutcome? = null
                        launchResult.purchases.forEach { purchase ->
                            val isPrimary = purchase.purchaseToken == primaryPurchase.purchaseToken
                            val outcome = enqueueAndProcess(
                                purchase = purchase,
                                productEntitlements = productEntitlements,
                                appUserIdOverride = appUserId,
                                offeringId = if (isPrimary) offeringId else null,
                                packageId = if (isPrimary) packageId else null,
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
                }
            }
    }

    suspend fun syncCurrentPurchases(
        limit: Int = 20,
        appUserIdOverride: String? = null,
    ): AppActorCustomerInfo? {
        return pipelineMutex.withLock {
            syncCurrentPurchasesLocked(limit = limit, appUserIdOverride = appUserIdOverride)
        }
    }

    private suspend fun syncCurrentPurchasesLocked(
        limit: Int = 20,
        appUserIdOverride: String? = null,
        excludedPurchaseTokens: Set<String> = emptySet(),
    ): AppActorCustomerInfo? {
        val appUserId = appUserIdOverride
            ?.takeIf { it.isNotBlank() }
            ?: identityStore.currentAppUserId
            ?: identityStore.ensureAppUserId()
        val productEntitlements = ensureProductEntitlements()
        var latestCustomer: AppActorCustomerInfo? = null
        val syncCandidates = mutableListOf<AppActorStorePurchase>()

        val allProcessedPurchases = mutableListOf<AppActorStorePurchase>()

        storeAdapter.queryActivePurchases().forEach { purchase ->
            val normalized = normalizePurchaseForPosting(purchase)
            if (excludedPurchaseTokens.contains(normalized.purchaseToken)) {
                return@forEach
            }
            allProcessedPurchases += normalized
            if (normalized.productType == AppActorProductType.Unknown) {
                when (val outcome = enqueueAndProcess(normalized, productEntitlements, appUserId)) {
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
                val response = backendClient.postGoogleSync(
                    AppActorGoogleSyncRequestDTO(
                        appUserId = appUserId,
                        obfuscatedAccountId = googleObfuscatedAccountId(appUserId),
                        obfuscatedProfileId = null,
                        source = "foreground_sync",
                        observedAt = isoNow(),
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
                latestCustomer = customerManager.cachedInfo(resolvedAppUserId)
            } catch (_: Throwable) {
                syncCandidates.forEach { purchase ->
                    when (val outcome = enqueueAndProcess(purchase, productEntitlements, appUserId)) {
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
        return pipelineMutex.withLock {
            restorePurchasesLocked(maxPurchases = maxPurchases, appUserIdOverride = appUserIdOverride)
        }
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
                val activeCandidates = batch
                    .filter { it.isActive }
                    .map { it.purchase }
                val response = backendClient.postGoogleRestore(
                    AppActorGoogleRestoreRequestDTO(
                        appUserId = currentAppUserId,
                        obfuscatedAccountId = googleObfuscatedAccountId(currentAppUserId),
                        obfuscatedProfileId = null,
                        source = "user_restore",
                        observedAt = isoNow(),
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
                latestBatchCustomer = customerManager.cachedInfo(resolvedAppUserId)
            } catch (throwable: Throwable) {
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
        return pipelineMutex.withLock {
            drainReadyQueueLocked(limit)
        }
    }

    private suspend fun drainReadyQueueLocked(limit: Int = 20): AppActorCustomerInfo? {
        val claimed = queueStore.claimReady(limit = limit, nowMillis = dateProviderMillis())
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
        return pipelineMutex.withLock {
            drainAllLocked(limit)
        }
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
    ): ProcessingOutcome {
        val normalizedPurchase = normalizePurchaseForPosting(purchase)
        val item = makeQueueItem(normalizedPurchase, appUserIdOverride, offeringId, packageId)
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
        return processClaimedItem(item.copy(phase = AppActorReceiptQueuePhase.Posting), productEntitlements)
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
        val baseline = existing.copy(
            appUserId = incoming.appUserId,
            environment = incoming.environment,
            purchaseState = incoming.purchaseState,
            orderId = incoming.orderId ?: existing.orderId,
            obfuscatedAccountId = incoming.obfuscatedAccountId ?: existing.obfuscatedAccountId,
            rawPurchaseData = incoming.rawPurchaseData ?: existing.rawPurchaseData,
            purchaseSignature = incoming.purchaseSignature ?: existing.purchaseSignature,
            isAutoRenewing = incoming.isAutoRenewing ?: existing.isAutoRenewing,
            offeringId = incoming.offeringId ?: existing.offeringId,
            packageId = incoming.packageId ?: existing.packageId,
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

    private fun adoptResolvedAppUserId(
        requestedAppUserId: String,
        resolvedAppUserId: String?,
    ): String {
        val finalAppUserId = resolvedAppUserId?.takeIf { it.isNotBlank() } ?: requestedAppUserId
        if (finalAppUserId != requestedAppUserId) {
            customerManager.clearCache(requestedAppUserId)
            identityStore.setAppUserId(finalAppUserId)
            identityStore.setServerUserId(finalAppUserId)
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
            isAutoRenewing = restoreItem.isAutoRenewing ?: existing.isAutoRenewing,
            obfuscatedAccountId = restoreItem.obfuscatedAccountId ?: existing.obfuscatedAccountId,
            idempotencyKey = restoreItem.idempotencyKey,
            rawPurchaseData = restoreItem.rawPurchaseData ?: existing.rawPurchaseData,
            purchaseSignature = restoreItem.purchaseSignature ?: existing.purchaseSignature,
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
        waitForIdentity()
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
        if (AppActorRetryPolicy.hasExhaustedRetries(nextRetryCount)) {
            deadLetter(
                item = item.copy(
                    retryCount = nextRetryCount,
                    lastUpdatedAtMillis = now,
                    claimedAtMillis = null,
                ),
                code = null,
                message = lastError,
            )
            return ProcessingOutcome.Queued
        }
        val updated = item.copy(
            retryCount = nextRetryCount,
            nextRetryAtMillis = AppActorRetryPolicy.nextRetryAtMillis(
                nowMillis = now,
                retryCount = nextRetryCount,
                retryAfterSeconds = retryAfterSeconds,
            ),
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
            AppActorRetryPolicy.hasExhaustedRetries(retryCount) -> "$resolved (dead-lettered after $retryCount attempts)"
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

    private fun fireDeferredPurchaseCallbackIfNeeded(purchase: AppActorStorePurchase, customerInfo: AppActorCustomerInfo) {
        var resolvedProductId: String? = null
        pendingPurchaseTokens.compute(purchase.purchaseToken) { _, entry ->
            if (entry == null) return@compute null
            val timestamp = entry.substringAfterLast('|', "0").toLongOrNull() ?: 0L
            if (dateProviderMillis() - timestamp > PENDING_EXPIRY_MILLIS) {
                null // Expired — stale entry from abandoned pending purchase
            } else {
                resolvedProductId = entry.substringBeforeLast('|')
                null // Remove — resolved
            }
        }
        if (resolvedProductId != null) {
            persistPendingState()
            onDeferredPurchaseResolved?.invoke(resolvedProductId!!, customerInfo)
        }
    }

    private fun persistPendingState() {
        pendingPrefs.edit().apply {
            clear()
            pendingPurchaseTokens.forEach { (token, entry) -> putString(token, entry) }
            apply()
        }
    }

    private suspend fun ensureProductEntitlements(): Map<String, List<String>> {
        val existing = offeringsManager.currentProductEntitlements()
        if (existing.isNotEmpty()) return existing
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
        ) ?: run {
            runCatching { offeringsManager.getOfferings(forceRefresh = false) }
            resolveKnownOneTimeProductType(
                productId = purchase.productId,
                productEntitlements = ensureProductEntitlements(),
            )
        } ?: return null

        return purchase.copy(productType = resolvedType)
    }

    private fun resolveKnownOneTimeProductType(
        productId: String,
        productEntitlements: Map<String, List<String>>,
    ): AppActorProductType? {
        val packageMatches = offeringsManager.cached()?.all
            ?.values
            ?.asSequence()
            ?.flatMap { offering -> offering.packages.asSequence() }
            ?.filter { appActorPackage ->
                appActorPackage.store == AppActorStore.PlayStore &&
                    appActorPackage.productId == productId &&
                    (appActorPackage.productType == AppActorProductType.Consumable ||
                        appActorPackage.productType == AppActorProductType.NonConsumable)
            }
            ?.map { it.productType }
            ?.distinct()
            ?.toList()
            .orEmpty()

        if (packageMatches.size == 1) {
            return packageMatches.single()
        }

        val keyMatches = productEntitlements.keys
            .asSequence()
            .mapNotNull { key ->
                if (key == "android:$productId") {
                    AppActorProductType.NonConsumable
                } else {
                    null
                }
            }
            .distinct()
            .toList()

        return if (packageMatches.isEmpty() && keyMatches.size == 1) {
            keyMatches.single()
        } else {
            null
        }
    }

    private fun hasReadyWork(nowMillis: Long = dateProviderMillis()): Boolean {
        val rateLimitCooldown = queueStore.getRateLimitCooldownMillis()
        if (rateLimitCooldown != null && rateLimitCooldown > nowMillis) {
            return false
        }
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
    ): AppActorReceiptQueueItem {
        val appUserId = appUserIdOverride?.takeIf { it.isNotBlank() }
            ?: identityStore.currentAppUserId
            ?: purchase.obfuscatedAccountId?.takeIf { it.isNotBlank() }
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
            isAutoRenewing = purchase.isAutoRenewing,
            obfuscatedAccountId = purchase.obfuscatedAccountId,
            idempotencyKey = "google:${purchase.productId}:${purchase.basePlanId.orEmpty()}:${purchase.purchaseToken}",
            rawPurchaseData = purchase.rawPurchaseData,
            purchaseSignature = purchase.purchaseSignature,
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
        isAutoRenewing = isAutoRenewing,
    )
}

private fun isoNow(): String = java.time.Instant.now().toString()

private fun googleObfuscatedAccountId(appUserId: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(appUserId.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { byte -> "%02x".format(byte) }
}

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

