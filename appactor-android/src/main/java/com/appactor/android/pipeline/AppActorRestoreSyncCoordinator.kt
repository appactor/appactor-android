package com.appactor.android.pipeline

import com.appactor.android.backend.client.AppActorBackendClient
import com.appactor.android.backend.client.AppActorBackendException
import com.appactor.android.backend.dto.AppActorCustomerEnvelopeDTO
import com.appactor.android.backend.dto.AppActorGoogleBatchResultDTO
import com.appactor.android.backend.dto.AppActorGoogleRestorePurchaseDTO
import com.appactor.android.backend.dto.AppActorGoogleRestoreRequestDTO
import com.appactor.android.backend.dto.AppActorGoogleSyncRequestDTO
import com.appactor.android.billing.AppActorStoreAdapter
import com.appactor.android.billing.AppActorStorePurchase
import com.appactor.android.managers.AppActorCustomerManager
import com.appactor.android.models.AppActorCustomerInfo
import com.appactor.android.models.AppActorError
import com.appactor.android.models.AppActorProductType
import com.appactor.android.models.appActorGoogleObfuscatedAccountId
import com.appactor.android.storage.AppActorIdentityStore
import com.appactor.android.storage.AppActorReceiptQueueItem
import com.appactor.android.storage.AppActorReceiptQueuePhase
import com.appactor.android.storage.AppActorReceiptQueueStore
import kotlinx.coroutines.CancellationException

/**
 * Owns restore + startup/foreground-sync PLANNING for the payment pipeline: the
 * bulk-restore batch builder, the foreground-sync orchestration, and the
 * finalize/enqueue helpers those two flows share.
 *
 * CONCURRENCY (the #1 safety rule): the shared [pipelineMutex] is NOT owned by
 * this collaborator — it STAYS in the orchestrator. The two entry points here
 * ([restorePurchasesAssumingLocked], [syncCurrentPurchasesAssumingLocked]) assume
 * the pipeline lock is already held; the orchestrator acquires the mutex and then
 * calls them (e.g. `pipelineMutex.withLock { restoreSyncCoordinator.restorePurchasesAssumingLocked(...) }`).
 * The post-lock `retryWakeScheduler.scheduleNextRetryWake(...)` also stays in the
 * orchestrator. This class never touches [pipelineMutex] or the retry-wake
 * scheduler and therefore can never re-enter the lock.
 *
 * Shared logic that other (orchestrator-resident) flows also need — entitlement
 * resolution, enqueue-and-process, queue-item construction, posted-ledger
 * finalization, deferred-callback resolution — stays in the orchestrator and is
 * supplied here as callbacks so there is exactly one implementation. It also
 * collaborates with the [AppActorReceiptQueueDrainer] for the post-sync drain.
 */
internal class AppActorRestoreSyncCoordinator(
    private val backendClient: AppActorBackendClient,
    private val storeAdapter: AppActorStoreAdapter,
    private val queueStore: AppActorReceiptQueueStore,
    private val customerManager: AppActorCustomerManager,
    private val identityStore: AppActorIdentityStore,
    private val pendingPurchaseRegistry: AppActorPendingPurchaseRegistry,
    private val receiptQueueDrainer: AppActorReceiptQueueDrainer,
    private val dateProviderMillis: () -> Long,
    private val ensureProductEntitlements: suspend (refreshIfMissing: Boolean) -> Map<String, List<String>>,
    private val enqueueAndProcess: suspend (
        purchase: AppActorStorePurchase,
        productEntitlements: Map<String, List<String>>,
        appUserIdOverride: String?,
        sourceIntent: String,
        clientPurchaseContext: AppActorClientPurchaseContext?,
    ) -> ProcessingOutcome,
    private val normalizePurchaseForPosting: suspend (AppActorStorePurchase) -> AppActorStorePurchase,
    private val resolveUnknownOneTimePurchase: suspend (
        purchase: AppActorStorePurchase,
        productEntitlements: Map<String, List<String>>,
    ) -> AppActorStorePurchase?,
    private val consumePendingPurchaseUpdateContext: (AppActorStorePurchase) -> AppActorPaymentProcessor.PurchaseUpdateContext?,
    private val fireDeferredPurchaseCallbackIfNeeded: (
        purchase: AppActorStorePurchase,
        customerInfo: AppActorCustomerInfo,
        emitCallback: Boolean,
    ) -> Unit,
    private val resolveDeferredPurchaseCallbackIfNeeded: (
        purchaseToken: String,
        customerInfo: AppActorCustomerInfo,
        emitCallback: Boolean,
    ) -> Unit,
    private val markPurchasePosted: (AppActorReceiptQueueItem) -> Unit,
    private val finalizePostedPurchase: suspend (AppActorReceiptQueueItem) -> Boolean,
    private val makeQueueItem: (
        purchase: AppActorStorePurchase,
        appUserIdOverride: String?,
        sourceIntent: String,
    ) -> AppActorReceiptQueueItem,
    private val queueItemForIncomingPurchase: (AppActorReceiptQueueItem) -> AppActorReceiptQueueItem,
) {

    internal data class CurrentPurchasesSyncMetadata(
        val sourceIntent: String,
        val source: String,
        val clientDeliverySource: AppActorClientDeliverySource,
    ) {
        fun clientPurchaseContext(observedAtMillis: Long): AppActorClientPurchaseContext {
            return AppActorClientPurchaseContext(
                clientObservedAtMillis = observedAtMillis,
                clientDeliverySource = clientDeliverySource,
            )
        }

        companion object {
            fun foregroundSync(): CurrentPurchasesSyncMetadata {
                return CurrentPurchasesSyncMetadata(
                    sourceIntent = SOURCE_INTENT_SYNC,
                    source = "foreground_sync",
                    clientDeliverySource = AppActorClientDeliverySource.ForegroundSync,
                )
            }

            fun restoreFallback(): CurrentPurchasesSyncMetadata {
                return CurrentPurchasesSyncMetadata(
                    sourceIntent = SOURCE_INTENT_RESTORE,
                    source = "user_restore",
                    clientDeliverySource = AppActorClientDeliverySource.RestoreFlow,
                )
            }
        }
    }

    suspend fun syncCurrentPurchasesAssumingLocked(
        limit: Int = 20,
        appUserIdOverride: String? = null,
        excludedPurchaseTokens: Set<String> = emptySet(),
        refreshEntitlementsIfMissing: Boolean = true,
        metadata: CurrentPurchasesSyncMetadata = CurrentPurchasesSyncMetadata.foregroundSync(),
    ): AppActorCustomerInfo? {
        val appUserId = appUserIdOverride
            ?.takeIf { it.isNotBlank() }
            ?: identityStore.currentAppUserId
            ?: identityStore.ensureAppUserId()
        val productEntitlements = ensureProductEntitlements(refreshEntitlementsIfMissing)
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
                val pendingAppUserId = pendingUpdateContext.appUserId?.takeIf { it.isNotBlank() } ?: appUserId
                when (val outcome = enqueueAndProcess(
                    normalized,
                    productEntitlements,
                    pendingAppUserId,
                    pendingUpdateContext.sourceIntent,
                    pendingUpdateContext.clientPurchaseContext,
                )) {
                    is ProcessingOutcome.Success -> {
                        latestCustomer = outcome.customerInfo
                        fireDeferredPurchaseCallbackIfNeeded(
                            normalized,
                            outcome.customerInfo,
                            identityStore.currentAppUserId == pendingAppUserId,
                        )
                    }
                    is ProcessingOutcome.AlreadyPosted -> {
                        latestCustomer = outcome.customerInfo
                        fireDeferredPurchaseCallbackIfNeeded(
                            normalized,
                            outcome.customerInfo,
                            identityStore.currentAppUserId == pendingAppUserId,
                        )
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
                    metadata.sourceIntent,
                    metadata.clientPurchaseContext(dateProviderMillis()),
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
                val syncContext = metadata.clientPurchaseContext(dateProviderMillis())
                val response = backendClient.postGoogleSync(
                    AppActorGoogleSyncRequestDTO(
                        appUserId = appUserId,
                        obfuscatedAccountId = appActorGoogleObfuscatedAccountId(appUserId),
                        obfuscatedProfileId = null,
                        sourceIntent = metadata.sourceIntent,
                        source = metadata.source,
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
                    metadata.sourceIntent,
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
                            metadata.sourceIntent,
                            metadata.clientPurchaseContext(dateProviderMillis()),
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

        val drained = receiptQueueDrainer.drainAllAssumingLocked(limit)
        if (drained != null) {
            latestCustomer = drained
        }

        // Fire deferred purchase callbacks for any pending purchases that were resolved during sync
        if (latestCustomer != null && pendingPurchaseRegistry.hasPendingTokens()) {
            allProcessedPurchases.forEach { purchase ->
                fireDeferredPurchaseCallbackIfNeeded(purchase, latestCustomer!!, true)
            }
        }

        return latestCustomer
    }

    suspend fun restorePurchasesAssumingLocked(
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

        val productEntitlements = ensureProductEntitlements(true)
        val restorePlan = buildRestorePlan(
            activePurchases = activePurchases,
            historyPurchases = historyPurchases,
            productEntitlements = productEntitlements,
        )
        if (restorePlan.bulkCandidates.isEmpty()) {
            val syncCustomer = if (restorePlan.followUpSyncRequired) {
                syncCurrentPurchasesAssumingLocked(
                    limit = batchSize,
                    appUserIdOverride = currentAppUserId,
                    metadata = CurrentPurchasesSyncMetadata.restoreFallback(),
                )
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
                val successfullyRestoredActivePurchases = activeCandidates.filter { purchase ->
                    successfulPurchaseKeys.contains(batchPurchaseKey(purchase))
                }
                finalizeRestoredActivePurchases(
                    purchases = successfullyRestoredActivePurchases,
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
                val restoredCustomer = customerManager.cachedInfo(resolvedAppUserId)
                latestBatchCustomer = restoredCustomer
                if (restoredCustomer != null) {
                    successfullyRestoredActivePurchases.forEach { purchase ->
                        resolveDeferredPurchaseCallbackIfNeeded(
                            purchase.purchaseToken,
                            restoredCustomer,
                            true,
                        )
                    }
                }
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                val fallbackCustomer = runCatching {
                    val syncCustomer = syncCurrentPurchasesAssumingLocked(
                        limit = batchSize,
                        appUserIdOverride = currentAppUserId,
                        metadata = CurrentPurchasesSyncMetadata.restoreFallback(),
                    )
                    currentAppUserId = identityStore.currentAppUserId ?: currentAppUserId
                    syncCustomer ?: customerManager.getCustomerInfo(
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
            syncCurrentPurchasesAssumingLocked(
                limit = batchSize,
                appUserIdOverride = currentAppUserId,
                excludedPurchaseTokens = restorePlan.restoredActivePurchaseTokens,
                metadata = CurrentPurchasesSyncMetadata.restoreFallback(),
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
            historyPurchase,
            productEntitlements,
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
            markPurchasePosted(finishItem)
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
                    sourceIntent,
                    clientPurchaseContext,
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
            purchase,
            appUserId,
            SOURCE_INTENT_RESTORE,
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
        val keyedRestoreItem = queueItemForIncomingPurchase(restoreItem)
        val existing = queueStore.get(keyedRestoreItem.key) ?: return keyedRestoreItem
        return existing.copy(
            appUserId = keyedRestoreItem.appUserId,
            packageName = keyedRestoreItem.packageName,
            environment = keyedRestoreItem.environment,
            productId = keyedRestoreItem.productId,
            productType = if (restoreItem.productType != AppActorProductType.Unknown.wireValue) {
                keyedRestoreItem.productType
            } else {
                existing.productType
            },
            purchaseToken = keyedRestoreItem.purchaseToken,
            purchaseTime = keyedRestoreItem.purchaseTime,
            purchaseState = keyedRestoreItem.purchaseState,
            orderId = keyedRestoreItem.orderId ?: existing.orderId,
            basePlanId = keyedRestoreItem.basePlanId ?: existing.basePlanId,
            offerId = keyedRestoreItem.offerId ?: existing.offerId,
            priceAmountMicros = keyedRestoreItem.priceAmountMicros ?: existing.priceAmountMicros,
            currencyCode = keyedRestoreItem.currencyCode ?: existing.currencyCode,
            isAutoRenewing = keyedRestoreItem.isAutoRenewing ?: existing.isAutoRenewing,
            obfuscatedAccountId = keyedRestoreItem.obfuscatedAccountId ?: existing.obfuscatedAccountId,
            sourceIntent = existing.sourceIntent,
            idempotencyKey = keyedRestoreItem.idempotencyKey,
            rawPurchaseData = keyedRestoreItem.rawPurchaseData ?: existing.rawPurchaseData,
            purchaseSignature = keyedRestoreItem.purchaseSignature ?: existing.purchaseSignature,
            clientPurchaseAttemptStartedAt = keyedRestoreItem.clientPurchaseAttemptStartedAt ?: existing.clientPurchaseAttemptStartedAt,
            clientObservedAt = keyedRestoreItem.clientObservedAt ?: existing.clientObservedAt,
            clientDeliverySource = keyedRestoreItem.clientDeliverySource ?: existing.clientDeliverySource,
            clientPurchaseAttemptId = keyedRestoreItem.clientPurchaseAttemptId ?: existing.clientPurchaseAttemptId,
            placement = existing.placement,
            sdkOriginated = keyedRestoreItem.sdkOriginated ?: existing.sdkOriginated,
            sdkVersion = keyedRestoreItem.sdkVersion ?: existing.sdkVersion,
            isAcknowledged = existing.isAcknowledged || keyedRestoreItem.isAcknowledged,
            shouldAcknowledge = existing.shouldAcknowledge || keyedRestoreItem.shouldAcknowledge,
            shouldConsume = existing.shouldConsume || keyedRestoreItem.shouldConsume,
            retryCount = existing.retryCount,
            nextRetryAtMillis = 0L,
            createdAtMillis = existing.createdAtMillis,
            lastUpdatedAtMillis = now,
            claimedAtMillis = null,
            phase = AppActorReceiptQueuePhase.NeedsFinish,
            lastError = null,
        )
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

    private companion object {
        const val SOURCE_INTENT_RESTORE = "restore"
        const val SOURCE_INTENT_SYNC = "sync"
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
