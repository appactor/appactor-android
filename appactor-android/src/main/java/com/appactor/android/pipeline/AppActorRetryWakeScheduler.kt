package com.appactor.android.pipeline

import com.appactor.android.storage.AppActorAtomicJsonReceiptQueueStore
import com.appactor.android.storage.AppActorReceiptQueuePhase
import com.appactor.android.storage.AppActorReceiptQueueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Owns the retry-wake scheduler for the payment pipeline. When the receipt queue
 * has work that is not yet ready (a future [AppActorReceiptQueueItem.nextRetryAtMillis],
 * a stale-claim deadline, or an active rate-limit cooldown), this scheduler arms a
 * single background coroutine that wakes at the earliest ready moment and triggers a
 * drain, then re-arms itself for whatever work remains.
 *
 * State owned here (audit android-6):
 *  - [retryWakeJob]: the currently armed wake coroutine, or null.
 *  - [scheduledRetryAtMillis]: the wall-clock millis the armed job is scheduled to
 *    fire for, or null when running an immediate drain / no job is armed.
 *  - [retryWakeLock]: dedicated monitor guarding both fields above. It is exclusive
 *    to this collaborator — no other code touches this lock. All inspection and
 *    mutation of [retryWakeJob]/[scheduledRetryAtMillis] (including the cancel+assign
 *    sequence in [scheduleNextRetryWake] and the completion cleanup inside the launched
 *    wake coroutines) happens under this lock so they share a consistent happens-before
 *    relationship regardless of the calling thread or whether the pipeline mutex is held.
 *
 * The drain itself is launched (never run) inside the monitor, so the lock is never
 * held across a suspension. The drain is performed by [runDrainUnderPipelineLock],
 * which the orchestrator supplies — it acquires the shared pipelineMutex and runs the
 * locked drain. This scheduler never touches pipelineMutex directly, so it can never
 * re-enter it.
 *
 * [activeRateLimitCooldown] stays in the orchestrator (shared with hasReadyWork and
 * the locked drain) and is supplied here as a synchronous callback; it is invoked
 * inside [retryWakeLock] from [nextReadyAtMillis] and must remain non-suspending.
 */
internal class AppActorRetryWakeScheduler(
    private val queueStore: AppActorReceiptQueueStore,
    private val backgroundScope: CoroutineScope,
    private val dateProviderMillis: () -> Long,
    private val activeRateLimitCooldown: (nowMillis: Long) -> Long?,
    private val runDrainUnderPipelineLock: suspend (limit: Int) -> Unit,
) {

    // Dedicated monitor guarding the retry-wake scheduler state below. All
    // reads/writes of retryWakeJob and scheduledRetryAtMillis — including the
    // cancel+assign sequence in scheduleNextRetryWake() and the cleanup inside
    // the launched wake coroutines — must happen under this lock so they share a
    // consistent happens-before relationship regardless of the calling thread or
    // whether pipelineMutex is held. (See audit android-6.)
    private val retryWakeLock = Any()
    private var retryWakeJob: Job? = null
    private var scheduledRetryAtMillis: Long? = null

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

    fun scheduleNextRetryWake(limit: Int = 20) {
        val now = dateProviderMillis()
        // All inspection and mutation of retryWakeJob/scheduledRetryAtMillis is
        // funnelled through this monitor so the cancel+assign is atomic and
        // visible across the coroutine Mutex callers, the lock-free callers, and
        // the background wake threads. The drain itself is launched (not run)
        // inside the lock, so we never hold the monitor across suspension.
        synchronized(retryWakeLock) {
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
                launchRetryWake(limit, delayMillis = 0L)
                return
            }

            if (scheduledRetryAtMillis == nextReadyAt && retryWakeJob?.isActive == true) {
                return
            }

            retryWakeJob?.cancel()
            scheduledRetryAtMillis = nextReadyAt
            launchRetryWake(limit, delayMillis = maxOf(nextReadyAt - now, 250L))
        }
    }

    /**
     * Launches a single wake coroutine and atomically records it as the active
     * [retryWakeJob]. Must be called while holding [retryWakeLock].
     *
     * The completion cleanup re-acquires [retryWakeLock] and only clears the
     * scheduler fields when they still reference *this* job, so a newer schedule
     * that replaced [retryWakeJob] after this one started is never clobbered —
     * preventing the lost-cancel / orphaned-coroutine race (audit android-6).
     */
    private fun launchRetryWake(limit: Int, delayMillis: Long) {
        // Started lazily so the field assignment below completes before the
        // coroutine body can read `thisJob`, guaranteeing the identity check in
        // the completion cleanup observes an initialized reference.
        lateinit var thisJob: Job
        thisJob = backgroundScope.launch(start = CoroutineStart.LAZY) {
            if (delayMillis > 0L) {
                delay(delayMillis)
            }
            runDrainUnderPipelineLock(limit)
            val isStillActiveJob = synchronized(retryWakeLock) {
                if (retryWakeJob === thisJob) {
                    retryWakeJob = null
                    scheduledRetryAtMillis = null
                    true
                } else {
                    false
                }
            }
            if (isStillActiveJob) {
                scheduleNextRetryWake(limit)
            }
        }
        retryWakeJob = thisJob
        thisJob.start()
    }
}
