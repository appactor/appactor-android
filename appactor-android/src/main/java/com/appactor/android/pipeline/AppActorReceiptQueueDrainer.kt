package com.appactor.android.pipeline

import com.appactor.android.backend.client.AppActorBackendClient
import com.appactor.android.backend.client.AppActorBackendException
import com.appactor.android.backend.dto.AppActorCustomerEnvelopeDTO
import com.appactor.android.billing.AppActorStoreAdapter
import com.appactor.android.managers.AppActorCustomerManager
import com.appactor.android.models.AppActorCustomerInfo
import com.appactor.android.models.AppActorProductType
import com.appactor.android.models.AppActorReceiptPipelineEvent
import com.appactor.android.models.appActorPublicReceiptId
import com.appactor.android.storage.AppActorIdentityStore
import com.appactor.android.storage.AppActorAtomicJsonReceiptQueueStore
import com.appactor.android.storage.AppActorReceiptQueueItem
import com.appactor.android.storage.AppActorReceiptQueuePhase
import com.appactor.android.storage.AppActorReceiptQueueStore
import kotlinx.coroutines.CancellationException

/**
 * Owns the receipt-queue drain / retry / dead-letter engine for the payment
 * pipeline. This is the part of the processor that actually posts queued
 * receipts to the backend, decides whether a failed post should be retried or
 * permanently rejected (dead-lettered), and revives recoverable dead letters.
 *
 * CONCURRENCY (the #1 safety rule): the shared [pipelineMutex] is NOT owned by
 * this collaborator. It STAYS in the orchestrator. The methods here that assume
 * the pipeline lock is already held end in `AssumingLocked`; the orchestrator
 * acquires the mutex and then calls them (e.g.
 * `pipelineMutex.withLock { drainer.drainAllAssumingLocked(limit) }`). This class
 * never touches [pipelineMutex] and therefore can never re-enter it.
 *
 * Shared logic that other (orchestrator-resident) flows also need — entitlement
 * resolution, queue-item normalization, posted-ledger keying, retry-wake
 * rescheduling, deferred-callback resolution — stays in the orchestrator and is
 * supplied here as callbacks so there is exactly one implementation. The
 * rate-limit cooldown check ([activeRateLimitCooldown]) lives here because it is
 * read by the locked drain and [hasReadyWork]; the orchestrator's retry-wake
 * scheduler reuses it via a callback that delegates back to this class.
 */
internal class AppActorReceiptQueueDrainer(
    private val backendClient: AppActorBackendClient,
    private val storeAdapter: AppActorStoreAdapter,
    private val queueStore: AppActorReceiptQueueStore,
    private val customerManager: AppActorCustomerManager,
    private val identityStore: AppActorIdentityStore,
    private val offlineCustomerInfoBuilder: AppActorOfflineCustomerInfoBuilder,
    private val onPipelineEvent: (AppActorReceiptPipelineEvent) -> Unit,
    private val dateProviderMillis: () -> Long,
    private val ensureProductEntitlements: suspend () -> Map<String, List<String>>,
    private val normalizeQueueItemForPosting: suspend (
        item: AppActorReceiptQueueItem,
        productEntitlements: Map<String, List<String>>,
    ) -> AppActorReceiptQueueItem,
    private val isPurchasePosted: (AppActorReceiptQueueItem) -> Boolean,
    private val markPurchasePosted: (AppActorReceiptQueueItem) -> Unit,
    private val finalizePostedPurchase: suspend (AppActorReceiptQueueItem) -> Boolean,
    private val resolveDeferredPurchaseCallbackIfNeeded: (
        purchaseToken: String,
        customerInfo: AppActorCustomerInfo,
        emitCallback: Boolean,
    ) -> Unit,
    private val scheduleNextRetryWake: () -> Unit,
) {

    suspend fun drainReadyQueueAssumingLocked(limit: Int = 20): AppActorCustomerInfo? {
        val now = dateProviderMillis()
        if (activeRateLimitCooldown(now) != null) return null
        val claimed = queueStore.claimReady(limit = limit, nowMillis = now)
        if (claimed.isEmpty()) return null

        val productEntitlements = ensureProductEntitlements()
        var latestCustomer: AppActorCustomerInfo? = null
        claimed.forEach { item ->
            when (val outcome = processClaimedItem(item, productEntitlements)) {
                is ProcessingOutcome.Success -> {
                    latestCustomer = outcome.customerInfo
                    resolveDeferredPurchaseCallbackIfNeeded(
                        item.purchaseToken,
                        outcome.customerInfo,
                        identityStore.currentAppUserId == item.appUserId,
                    )
                }
                is ProcessingOutcome.AlreadyPosted -> {
                    latestCustomer = outcome.customerInfo
                    resolveDeferredPurchaseCallbackIfNeeded(
                        item.purchaseToken,
                        outcome.customerInfo,
                        identityStore.currentAppUserId == item.appUserId,
                    )
                }
                is ProcessingOutcome.Queued,
                is ProcessingOutcome.PermanentFailure -> Unit
            }
        }
        return latestCustomer
    }

    suspend fun drainAllAssumingLocked(
        limit: Int = 20,
    ): AppActorCustomerInfo? {
        var latestCustomer: AppActorCustomerInfo? = null
        while (true) {
            val drained = drainReadyQueueAssumingLocked(limit)
            if (drained != null) {
                latestCustomer = drained
            }
            if (drained == null || !hasReadyWork()) {
                break
            }
        }
        return latestCustomer
    }

    suspend fun reviveRecoverableDeadLetter(
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
            placement = existing.placement ?: incoming.placement,
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

    suspend fun processClaimedItem(
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

        if (isPurchasePosted(normalizedItem)) {
            return finishAlreadyPostedItem(normalizedItem, productEntitlements)
        }

        return try {
            val response = backendClient.postGoogleReceipt(
                AppActorReceiptRequestBuilder.buildGoogleReceiptRequest(normalizedItem)
            )
            val body = requireNotNull(response.body) { "Google receipt response body was null." }
            when (val result = body.toPipelineStatus()) {
                is AppActorPaymentProcessor.ReceiptPipelineStatus.Success -> {
                    val customerDTO = requireNotNull(body.customer) {
                        "Receipt response success was missing customer info."
                    }
                    markPurchasePosted(normalizedItem)
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

                is AppActorPaymentProcessor.ReceiptPipelineStatus.RetryableError -> {
                    scheduleRetryOrDeadLetter(
                        item = normalizedItem,
                        retryAfterSeconds = result.retryAfterSeconds,
                        errorCode = result.errorCode,
                        errorMessage = result.errorMessage,
                    )
                }

                is AppActorPaymentProcessor.ReceiptPipelineStatus.PermanentError -> {
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
            ?: offlineCustomerInfoBuilder.buildOfflineCustomerInfo(
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
            phase = if (isPurchasePosted(item)) {
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
            markPurchasePosted(item)
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

    fun hasReadyWork(nowMillis: Long = dateProviderMillis()): Boolean {
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

    fun activeRateLimitCooldown(nowMillis: Long = dateProviderMillis()): Long? {
        val cooldown = queueStore.getRateLimitCooldownMillis() ?: return null
        if (cooldown <= nowMillis) {
            queueStore.setRateLimitCooldownMillis(null)
            return null
        }
        return cooldown
    }

    private companion object {
        const val SOURCE_INTENT_PURCHASE = "purchase"
        const val SOURCE_INTENT_RESTORE = "restore"
        const val SOURCE_INTENT_SYNC = "sync"
        const val SOURCE_INTENT_QUEUE = "queue"
    }
}
