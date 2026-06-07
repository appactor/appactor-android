package com.appactor.android.pipeline

import com.appactor.android.billing.AppActorStorePurchase
import com.appactor.android.internal.logging.AppActorLogger
import com.appactor.android.storage.AppActorIdentityStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns identity-transition buffering for the payment pipeline. While an identity
 * switch is in flight (between [begin] and [end]), live purchase updates that
 * arrive without an explicit appUserId override are captured against the identity
 * that was current when the transition began, preventing wrong-user attribution.
 *
 * State owned here:
 *  - [identityTransitionAppUserId]: the appUserId captured at [begin]; non-null
 *    iff a transition is active.
 *  - [identityTransitionBuffer]: purchases observed during the transition,
 *    bounded by [MAX_TRANSITION_BUFFER_SIZE].
 *  - [transitionMutex]: dedicated monitor guarding both fields above. It is
 *    exclusive to this collaborator — no other code touches this lock, and this
 *    collaborator never acquires the shared pipelineMutex (the orchestrator owns
 *    the dispatch that does). Callers must not invoke a process/recursion callback
 *    while [bufferIfTransitioning] holds the lock; this class never does.
 */
internal class AppActorIdentityTransitionBuffer(
    private val identityStore: AppActorIdentityStore,
) {

    private val transitionMutex = Mutex()
    private var identityTransitionAppUserId: String? = null
    private val identityTransitionBuffer = mutableListOf<BufferedPurchase>()
    private val maxTransitionBufferSize = MAX_TRANSITION_BUFFER_SIZE

    internal data class BufferedPurchase(
        val purchase: AppActorStorePurchase,
        val capturedAppUserId: String,
        val purchaseUpdateContext: AppActorPaymentProcessor.PurchaseUpdateContext,
    )

    suspend fun begin() {
        transitionMutex.withLock {
            identityTransitionAppUserId = identityStore.currentAppUserId
                ?: identityStore.ensureAppUserId()
        }
    }

    /**
     * Drains the buffer (under the lock), ends the active transition, then runs
     * [process] for each captured-identity group OUTSIDE the lock. [process]
     * receives the captured appUserId, the buffered purchases, the per-token
     * context overrides, and whether the deferred-purchase callback should fire
     * (true only when the captured identity matches the currently active one).
     */
    suspend fun end(
        process: suspend (
            appUserId: String,
            purchases: List<AppActorStorePurchase>,
            purchaseUpdateContextOverrides: Map<String, AppActorPaymentProcessor.PurchaseUpdateContext>,
            emitDeferredPurchaseCallback: Boolean,
        ) -> AppActorPurchaseUpdateProcessingResult?,
    ): List<AppActorPurchaseUpdateProcessingResult> {
        val buffered = transitionMutex.withLock {
            val items = identityTransitionBuffer.toList()
            identityTransitionBuffer.clear()
            identityTransitionAppUserId = null
            items
        }
        val currentAppUserId = identityStore.currentAppUserId
        return buffered.groupBy { it.capturedAppUserId }
            .mapNotNull { (userId, items) ->
                process(
                    userId,
                    items.map { it.purchase },
                    items.associate { it.purchase.purchaseToken to it.purchaseUpdateContext },
                    currentAppUserId == userId,
                )
            }
    }

    /**
     * If an identity transition is active, buffers [purchases] against the
     * captured identity and returns the overflow purchases (those that didn't fit
     * within [MAX_TRANSITION_BUFFER_SIZE]); the caller processes the overflow
     * immediately with the captured identity. Returns:
     *  - null   → no transition active; caller should process normally.
     *  - empty  → all purchases buffered; caller should return without processing.
     *  - non-empty → overflow purchases the caller must process immediately.
     *
     * [resolveContext] is invoked under the lock and MUST be non-suspending.
     */
    suspend fun bufferIfTransitioning(
        purchases: List<AppActorStorePurchase>,
        purchaseUpdateContextOverrides: Map<String, AppActorPaymentProcessor.PurchaseUpdateContext>,
        resolveContext: (AppActorStorePurchase) -> AppActorPaymentProcessor.PurchaseUpdateContext,
    ): List<BufferedPurchase>? {
        return transitionMutex.withLock {
            val userId = identityTransitionAppUserId ?: return@withLock null
            val overflowPurchases = mutableListOf<BufferedPurchase>()
            purchases.forEach { purchase ->
                val updateContext = purchaseUpdateContextOverrides[purchase.purchaseToken]
                    ?: resolveContext(purchase)
                if (identityTransitionBuffer.size < maxTransitionBufferSize) {
                    identityTransitionBuffer.add(BufferedPurchase(purchase, userId, updateContext))
                } else {
                    overflowPurchases.add(BufferedPurchase(purchase, userId, updateContext))
                    AppActorLogger.warn("[PaymentProcessor] Transition buffer full ($maxTransitionBufferSize), purchase ${purchase.productId} will be processed immediately with captured identity")
                }
            }
            overflowPurchases
        }
    }

    private companion object {
        const val MAX_TRANSITION_BUFFER_SIZE = 50
    }
}
