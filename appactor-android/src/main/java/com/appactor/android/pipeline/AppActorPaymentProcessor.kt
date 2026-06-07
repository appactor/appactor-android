package com.appactor.android.pipeline

import android.app.Activity
import com.appactor.android.backend.client.AppActorBackendClient
import com.appactor.android.internal.logging.AppActorLogger
import com.appactor.android.backend.dto.AppActorGoogleReceiptResponseDTO
import com.appactor.android.billing.AppActorStoreAdapter
import com.appactor.android.billing.AppActorStorePurchase
import com.appactor.android.billing.AppActorStorePurchaseLaunchResult
import com.appactor.android.cache.AppActorOfflineProductCatalogStore
import com.appactor.android.managers.AppActorCustomerManager
import com.appactor.android.managers.AppActorOfferingsManager
import com.appactor.android.models.AppActorResolvedPurchaseTarget
import com.appactor.android.models.AppActorConfiguration
import com.appactor.android.models.AppActorCustomerInfo
import com.appactor.android.models.AppActorEnvironment
import com.appactor.android.models.AppActorError
import com.appactor.android.models.AppActorPackage
import com.appactor.android.models.AppActorProductType
import com.appactor.android.models.AppActorPurchaseInfo
import com.appactor.android.models.AppActorPurchaseResult
import com.appactor.android.models.AppActorReceiptPipelineEvent
import com.appactor.android.models.AppActorSubscriptionReplacementMode
import com.appactor.android.models.AppActorStore
import com.appactor.android.models.toResolvedPurchaseTarget
import com.appactor.android.storage.AppActorIdentityStore
import com.appactor.android.storage.AppActorAtomicJsonReceiptQueueStore
import com.appactor.android.storage.AppActorPostedLedgerStore
import com.appactor.android.storage.AppActorReceiptQueueItem
import com.appactor.android.storage.AppActorReceiptQueuePhase
import com.appactor.android.storage.AppActorReceiptQueueStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
    // Owns pending-purchase token persistence + recent-foreground-purchase
    // markers. The maps inside are ConcurrentHashMaps (no extra lock); its
    // methods are synchronous and lock-free, so they're safe to call while
    // holding pipelineMutex.
    private val pendingPurchaseRegistry = AppActorPendingPurchaseRegistry(
        configuration = configuration,
        dateProviderMillis = dateProviderMillis,
    )

    private val offlineCustomerInfoBuilder = AppActorOfflineCustomerInfoBuilder(
        configuration = configuration,
    )

    @Volatile
    var onDeferredPurchaseResolved: ((productId: String, customerInfo: AppActorCustomerInfo) -> Unit)? = null

    // Identity transition buffering — prevents wrong-user attribution
    // when purchases arrive between drainAll() and identity switch. The
    // collaborator owns its own dedicated transitionMutex + buffer state.
    private val identityTransitionBuffer = AppActorIdentityTransitionBuffer(
        identityStore = identityStore,
    )

    // Owns the retry-wake scheduler state: retryWakeJob, scheduledRetryAtMillis,
    // and its exclusive retryWakeLock (audit android-6). The drain it triggers
    // runs under the shared pipelineMutex via the supplied callback — the
    // orchestrator keeps the mutex; the scheduler never touches it. The
    // rate-limit cooldown check lives in the receipt-queue drainer (shared with
    // hasReadyWork and the locked drain) and is supplied as a synchronous
    // callback that delegates to it. Both callbacks are evaluated lazily at
    // runtime, so the mutual reference with receiptQueueDrainer is safe despite
    // declaration order.
    private val retryWakeScheduler: AppActorRetryWakeScheduler = AppActorRetryWakeScheduler(
        queueStore = queueStore,
        backgroundScope = backgroundScope,
        dateProviderMillis = dateProviderMillis,
        activeRateLimitCooldown = { nowMillis -> receiptQueueDrainer.activeRateLimitCooldown(nowMillis) },
        runDrainUnderPipelineLock = { limit ->
            pipelineMutex.withLock {
                receiptQueueDrainer.drainAllAssumingLocked(limit)
            }
        },
    )

    // Owns the receipt-queue drain / retry / dead-letter engine. The shared
    // pipelineMutex is NOT moved here — it STAYS in this orchestrator. The
    // drainer's *AssumingLocked methods assume the caller already holds
    // pipelineMutex; this orchestrator acquires the mutex and then calls them.
    // Shared logic (entitlement resolution, queue-item normalization, posted-
    // ledger keying, retry-wake rescheduling, deferred-callback resolution)
    // stays in this orchestrator and is supplied as callbacks so there is a
    // single implementation.
    private val receiptQueueDrainer: AppActorReceiptQueueDrainer = AppActorReceiptQueueDrainer(
        backendClient = backendClient,
        storeAdapter = storeAdapter,
        queueStore = queueStore,
        customerManager = customerManager,
        identityStore = identityStore,
        offlineCustomerInfoBuilder = offlineCustomerInfoBuilder,
        onPipelineEvent = onPipelineEvent,
        dateProviderMillis = dateProviderMillis,
        ensureProductEntitlements = { ensureProductEntitlements() },
        normalizeQueueItemForPosting = { item, productEntitlements ->
            normalizeQueueItemForPosting(item, productEntitlements)
        },
        isPurchasePosted = { item -> isPurchasePosted(item) },
        markPurchasePosted = { item -> markPurchasePosted(item) },
        finalizePostedPurchase = { item -> finalizePostedPurchase(item) },
        resolveDeferredPurchaseCallbackIfNeeded = { purchaseToken, customerInfo, emitCallback ->
            resolveDeferredPurchaseCallbackIfNeeded(
                purchaseToken = purchaseToken,
                customerInfo = customerInfo,
                emitCallback = emitCallback,
            )
        },
        scheduleNextRetryWake = { retryWakeScheduler.scheduleNextRetryWake() },
    )

    // Owns restore + startup/foreground-sync PLANNING (bulk-restore batch builder,
    // foreground-sync orchestration, finalize/enqueue helpers). Same pipelineMutex
    // rule as the drainer: the shared lock STAYS in this orchestrator; the
    // coordinator's *AssumingLocked entry points assume the caller already holds
    // pipelineMutex, and the orchestrator acquires it before calling them. Shared
    // logic (entitlement resolution, enqueue-and-process, queue-item construction,
    // posted-ledger finalization, deferred-callback resolution) stays here and is
    // supplied as callbacks so there is a single implementation. The post-lock
    // retry-wake scheduling also stays in this orchestrator.
    private val restoreSyncCoordinator: AppActorRestoreSyncCoordinator = AppActorRestoreSyncCoordinator(
        backendClient = backendClient,
        storeAdapter = storeAdapter,
        queueStore = queueStore,
        customerManager = customerManager,
        identityStore = identityStore,
        pendingPurchaseRegistry = pendingPurchaseRegistry,
        receiptQueueDrainer = receiptQueueDrainer,
        dateProviderMillis = dateProviderMillis,
        ensureProductEntitlements = { refreshIfMissing ->
            ensureProductEntitlements(refreshIfMissing = refreshIfMissing)
        },
        enqueueAndProcess = { purchase, productEntitlements, appUserIdOverride, sourceIntent, clientPurchaseContext ->
            enqueueAndProcess(
                purchase = purchase,
                productEntitlements = productEntitlements,
                appUserIdOverride = appUserIdOverride,
                sourceIntent = sourceIntent,
                clientPurchaseContext = clientPurchaseContext,
            )
        },
        normalizePurchaseForPosting = { purchase -> normalizePurchaseForPosting(purchase) },
        resolveUnknownOneTimePurchase = { purchase, productEntitlements ->
            resolveUnknownOneTimePurchase(
                purchase = purchase,
                productEntitlements = productEntitlements,
            )
        },
        consumePendingPurchaseUpdateContext = { purchase -> consumePendingPurchaseUpdateContext(purchase) },
        fireDeferredPurchaseCallbackIfNeeded = { purchase, customerInfo, emitCallback ->
            fireDeferredPurchaseCallbackIfNeeded(
                purchase = purchase,
                customerInfo = customerInfo,
                emitCallback = emitCallback,
            )
        },
        resolveDeferredPurchaseCallbackIfNeeded = { purchaseToken, customerInfo, emitCallback ->
            resolveDeferredPurchaseCallbackIfNeeded(
                purchaseToken = purchaseToken,
                customerInfo = customerInfo,
                emitCallback = emitCallback,
            )
        },
        markPurchasePosted = { item -> markPurchasePosted(item) },
        finalizePostedPurchase = { item -> finalizePostedPurchase(item) },
        makeQueueItem = { purchase, appUserIdOverride, sourceIntent ->
            makeQueueItem(
                purchase = purchase,
                appUserIdOverride = appUserIdOverride,
                sourceIntent = sourceIntent,
            )
        },
        queueItemForIncomingPurchase = { item -> queueItemForIncomingPurchase(item) },
    )

    internal data class PurchaseUpdateContext(
        val sourceIntent: String,
        val clientPurchaseContext: AppActorClientPurchaseContext?,
        val appUserId: String? = null,
    )

    suspend fun beginIdentityTransition() {
        identityTransitionBuffer.begin()
    }

    suspend fun endIdentityTransition(): List<AppActorPurchaseUpdateProcessingResult> {
        return identityTransitionBuffer.end { userId, purchases, contextOverrides, emitDeferredPurchaseCallback ->
            processPurchaseUpdatesInternal(
                purchases = purchases,
                appUserIdOverride = userId,
                purchaseUpdateContextOverrides = contextOverrides,
                emitDeferredPurchaseCallback = emitDeferredPurchaseCallback,
            )
        }
    }

    suspend fun purchase(
        activity: Activity,
        appActorPackage: AppActorPackage,
        appUserIdOverride: String? = null,
        placement: String? = null,
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
                placement = placement,
            )
        } finally {
            purchaseMutex.unlock()
        }
    }

    suspend fun purchase(
        activity: Activity,
        params: com.appactor.android.models.AppActorPurchaseParams,
        appUserIdOverride: String? = null,
        placement: String? = null,
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
                placement = placement,
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
            val overflow = identityTransitionBuffer.bufferIfTransitioning(
                purchases = purchases,
                purchaseUpdateContextOverrides = purchaseUpdateContextOverrides,
                resolveContext = { purchase -> resolvePurchaseUpdateContext(purchase) },
            )
            if (overflow == null) {
                // Fall through — no identity transition active.
            } else if (overflow.isEmpty()) {
                return null
            } else {
                return processPurchaseUpdatesInternal(
                    purchases = overflow.map { it.purchase },
                    appUserIdOverride = overflow.first().capturedAppUserId,
                    purchaseUpdateContextOverrides = overflow.associate { it.purchase.purchaseToken to it.purchaseUpdateContext },
                    emitDeferredPurchaseCallback = false,
                )
            }
        }
        var processedAppUserId: String? = null
        val result = pipelineMutex.withLock {
            val defaultAppUserId = appUserIdOverride
                ?.takeIf { it.isNotBlank() }
                ?: identityStore.currentAppUserId
                ?: identityStore.ensureAppUserId()
            processedAppUserId = defaultAppUserId
            val productEntitlements = ensureProductEntitlements()
            var latestCustomer: AppActorCustomerInfo? = null
            purchases.forEach { purchase ->
                val updateContext = purchaseUpdateContextOverrides[purchase.purchaseToken]
                    ?: resolvePurchaseUpdateContext(purchase)
                val receiptAppUserId = updateContext.appUserId?.takeIf { it.isNotBlank() } ?: defaultAppUserId
                if (processedAppUserId == null || processedAppUserId == defaultAppUserId) {
                    processedAppUserId = receiptAppUserId
                }
                val shouldEmitDeferredCallback =
                    emitDeferredPurchaseCallback && identityStore.currentAppUserId == receiptAppUserId
                when (
                    val outcome = enqueueAndProcess(
                        purchase = purchase,
                        productEntitlements = productEntitlements,
                        appUserIdOverride = receiptAppUserId,
                        sourceIntent = updateContext.sourceIntent,
                        clientPurchaseContext = updateContext.clientPurchaseContext,
                    )
                ) {
                    is ProcessingOutcome.Success -> {
                        latestCustomer = outcome.customerInfo
                        fireDeferredPurchaseCallbackIfNeeded(
                            purchase = purchase,
                            customerInfo = outcome.customerInfo,
                            emitCallback = shouldEmitDeferredCallback,
                        )
                    }
                    is ProcessingOutcome.AlreadyPosted -> {
                        latestCustomer = outcome.customerInfo
                        fireDeferredPurchaseCallbackIfNeeded(
                            purchase = purchase,
                            customerInfo = outcome.customerInfo,
                            emitCallback = shouldEmitDeferredCallback,
                        )
                    }
                    is ProcessingOutcome.Queued,
                    is ProcessingOutcome.PermanentFailure -> Unit
                }
            }
            latestCustomer
        }
        retryWakeScheduler.scheduleNextRetryWake()
        return AppActorPurchaseUpdateProcessingResult(
            customerInfo = result,
            appUserId = processedAppUserId ?: return null,
        )
    }

    private suspend fun purchaseWithTarget(
        activity: Activity,
        target: AppActorResolvedPurchaseTarget,
        appUserIdOverride: String? = null,
        placement: String? = null,
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
        val clientPurchaseContext = AppActorClientPurchaseContext.purchaseAttempt(
            startedAtMillis = dateProviderMillis(),
            placement = placement,
        )
        var keepForegroundMarker = false
        pendingPurchaseRegistry.markForegroundPurchaseProduct(foregroundProductId, clientPurchaseContext = clientPurchaseContext)
        try {
            return when (val launchResult = storeAdapter.launchPurchase(activity, resolvedRequest)) {
                is AppActorStorePurchaseLaunchResult.Cancelled -> AppActorPurchaseResult.Cancelled
                is AppActorStorePurchaseLaunchResult.Pending -> {
                    val now = dateProviderMillis()
                    launchResult.purchases.forEach { purchase ->
                        pendingPurchaseRegistry.putPendingEntry(
                            purchase.purchaseToken,
                            clientPurchaseContext.toPendingEntry(
                                productId = purchase.productId,
                                recordedAtMillis = now,
                                appUserId = appUserId,
                            ),
                        )
                    }
                    pendingPurchaseRegistry.persist()
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
                                val offline = offlineCustomerInfoBuilder.buildOfflineCustomerInfo(
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
                    retryWakeScheduler.scheduleNextRetryWake()
                    purchaseResult
                }
            }
        } catch (error: CancellationException) {
            pendingPurchaseRegistry.markForegroundPurchaseProduct(
                productId = foregroundProductId,
                ttlMillis = FOREGROUND_PURCHASE_CANCELLATION_GRACE_MILLIS,
            )
            keepForegroundMarker = true
            throw error
        } finally {
            if (!keepForegroundMarker) {
                pendingPurchaseRegistry.clearForegroundPurchaseProduct(foregroundProductId)
            }
        }
    }

    suspend fun syncCurrentPurchases(
        limit: Int = 20,
        appUserIdOverride: String? = null,
        refreshEntitlementsIfMissing: Boolean = true,
    ): AppActorCustomerInfo? {
        val result = pipelineMutex.withLock {
            restoreSyncCoordinator.syncCurrentPurchasesAssumingLocked(
                limit = limit,
                appUserIdOverride = appUserIdOverride,
                refreshEntitlementsIfMissing = refreshEntitlementsIfMissing,
            )
        }
        retryWakeScheduler.scheduleNextRetryWake(limit)
        return result
    }

    suspend fun restorePurchases(
        maxPurchases: Int = 500,
        appUserIdOverride: String? = null,
    ): AppActorCustomerInfo {
        val result = pipelineMutex.withLock {
            restoreSyncCoordinator.restorePurchasesAssumingLocked(
                maxPurchases = maxPurchases,
                appUserIdOverride = appUserIdOverride,
            )
        }
        retryWakeScheduler.scheduleNextRetryWake()
        return result
    }

    suspend fun drainReadyQueue(limit: Int = 20): AppActorCustomerInfo? {
        val result = pipelineMutex.withLock {
            receiptQueueDrainer.drainReadyQueueAssumingLocked(limit)
        }
        retryWakeScheduler.scheduleNextRetryWake(limit)
        return result
    }

    suspend fun drainAll(
        limit: Int = 20,
    ): AppActorCustomerInfo? {
        val result = pipelineMutex.withLock {
            receiptQueueDrainer.drainAllAssumingLocked(limit)
        }
        retryWakeScheduler.scheduleNextRetryWake(limit)
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
            receiptQueueDrainer.drainAllAssumingLocked(limit)
        }
        retryWakeScheduler.scheduleNextRetryWake(limit)
        return result
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
        val rawItem = makeQueueItem(
            normalizedPurchase,
            appUserIdOverride,
            offeringId,
            packageId,
            sourceIntent,
            clientPurchaseContext,
        )
        val item = queueItemForIncomingPurchase(rawItem)
        val existing = queueStore.get(item.key)
        if (existing?.phase == AppActorReceiptQueuePhase.DeadLettered) {
            val revived = receiptQueueDrainer.reviveRecoverableDeadLetter(
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
                return receiptQueueDrainer.processClaimedItem(
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
        return receiptQueueDrainer.processClaimedItem(mergedItem.copy(phase = AppActorReceiptQueuePhase.Posting), productEntitlements)
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

    private fun fireDeferredPurchaseCallbackIfNeeded(
        purchase: AppActorStorePurchase,
        customerInfo: AppActorCustomerInfo,
        emitCallback: Boolean = true,
    ) {
        resolveDeferredPurchaseCallbackIfNeeded(
            purchaseToken = purchase.purchaseToken,
            customerInfo = customerInfo,
            emitCallback = emitCallback,
        )
    }

    private fun resolveDeferredPurchaseCallbackIfNeeded(
        purchaseToken: String,
        customerInfo: AppActorCustomerInfo,
        emitCallback: Boolean = true,
    ) {
        val resolvedProductId = pendingPurchaseRegistry.resolveDeferredEntry(purchaseToken)
        if (resolvedProductId != null) {
            if (emitCallback) {
                onDeferredPurchaseResolved?.invoke(resolvedProductId, customerInfo)
            }
        }
    }

    private fun resolvePurchaseUpdateContext(purchase: AppActorStorePurchase): PurchaseUpdateContext {
        val pendingContext = consumePendingPurchaseUpdateContext(purchase)
        if (pendingContext != null) {
            return pendingContext
        }

        val foregroundContext = pendingPurchaseRegistry.consumeRecentForegroundPurchaseContext(purchase.productId)
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
        val (entry, now) = pendingPurchaseRegistry.takePendingEntryIfValid(purchase.purchaseToken)
            ?: return null
        return PurchaseUpdateContext(
            sourceIntent = SOURCE_INTENT_PURCHASE,
            clientPurchaseContext = AppActorClientPurchaseContext.fromPendingEntry(entry, observedAtMillis = now),
            appUserId = entry.appUserId,
        )
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
                orderId = purchase.orderId,
                purchaseTime = purchase.purchaseTimeMillis.toString(),
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
            placement = clientPurchaseContext?.placement.normalizePlacement(),
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

    private fun isPurchasePosted(item: AppActorReceiptQueueItem): Boolean {
        if (postedLedgerStore.isPosted(postedLedgerKey(item))) return true
        if (!isLegacyKeyedQueueItem(item)) return false
        return postedLedgerStore.isPosted(legacyQueueKey(item))
    }

    private fun markPurchasePosted(item: AppActorReceiptQueueItem) {
        val primaryKey = postedLedgerKey(item)
        val legacyKey = legacyQueueKey(item)
        postedLedgerStore.markPosted(primaryKey)
        if (legacyKey != primaryKey) {
            postedLedgerStore.markPosted(legacyKey)
        }
        if (item.key != primaryKey && item.key != legacyKey) {
            postedLedgerStore.markPosted(item.key)
        }
    }

    private fun queueItemForIncomingPurchase(item: AppActorReceiptQueueItem): AppActorReceiptQueueItem {
        if (queueStore.get(item.key) != null) return item
        val legacyKey = legacyQueueKey(item)
        if (legacyKey == item.key) return item
        val legacyItem = queueStore.get(legacyKey) ?: return item
        return if (representsSameQueuedEconomicEvent(legacyItem, item)) {
            item.copy(key = legacyItem.key)
        } else {
            item
        }
    }

    private fun representsSameQueuedEconomicEvent(
        existing: AppActorReceiptQueueItem,
        incoming: AppActorReceiptQueueItem,
    ): Boolean {
        if (existing.purchaseToken != incoming.purchaseToken) return false
        if (existing.productId != incoming.productId) return false
        if (existing.basePlanId != incoming.basePlanId) return false
        val existingRevision = queuedEconomicRevision(existing)
        val incomingRevision = queuedEconomicRevision(incoming)
        return if (existingRevision != null && incomingRevision != null) {
            existingRevision == incomingRevision
        } else {
            true
        }
    }

    private fun queuedEconomicRevision(item: AppActorReceiptQueueItem): String? {
        return item.orderId
            ?.takeIf { it.isNotBlank() }
            ?.let { "orderId=$it" }
            ?: item.purchaseTime
                .takeIf { it.isNotBlank() }
                ?.let { "purchaseTime=$it" }
    }

    private fun legacyQueueKey(item: AppActorReceiptQueueItem): String {
        return AppActorReceiptQueueItem.makeKey(
            purchaseToken = item.purchaseToken,
            productId = item.productId,
            basePlanId = item.basePlanId,
        )
    }

    private fun isLegacyKeyedQueueItem(item: AppActorReceiptQueueItem): Boolean {
        return item.key == legacyQueueKey(item)
    }

    private fun postedLedgerKey(item: AppActorReceiptQueueItem): String {
        return AppActorReceiptQueueItem.makeKey(
            purchaseToken = item.purchaseToken,
            productId = item.productId,
            basePlanId = item.basePlanId,
            orderId = item.orderId,
            purchaseTime = item.purchaseTime,
        )
    }

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
        const val FOREGROUND_PURCHASE_CANCELLATION_GRACE_MILLIS: Long = 30 * 1_000L // 30 seconds
        const val SOURCE_INTENT_PURCHASE = "purchase"
        const val SOURCE_INTENT_QUEUE = "queue"
    }
}

internal sealed interface ProcessingOutcome {
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

internal fun AppActorGoogleReceiptResponseDTO.toPipelineStatus(): AppActorPaymentProcessor.ReceiptPipelineStatus {
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

internal fun AppActorReceiptQueueItem.toStorePurchase(): AppActorStorePurchase {
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

internal fun AppActorStorePurchase.purchaseDateString(): String {
    val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    formatter.timeZone = TimeZone.getTimeZone("UTC")
    return formatter.format(Date(purchaseTimeMillis))
}
