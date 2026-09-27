package com.appactor.android.pipeline

import com.appactor.android.api.queueItem
import com.appactor.android.internal.runtime.appActorBackgroundScope
import com.appactor.android.storage.AppActorReceiptQueueStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

// TODO(android-6 coverage): also pin that the dedup-skip (scheduledRetryAtMillis == nextReadyAt
// with an active job) spawns no second wake, and that a wake replaced after its delay doesn't drain.
class AppActorRetryWakeSchedulerTests {

    @Test
    fun `a drain that re-arms the wake for a failed receipt runs to its end`() = runBlocking {
        val now = 1_000_000L
        val queueStore = mockk<AppActorReceiptQueueStore>()
        every { queueStore.snapshot() } returns listOf(queueItem().copy(nextRetryAtMillis = now))
        val scope = appActorBackgroundScope()
        val drainFinished = CompletableDeferred<Unit>()
        lateinit var scheduler: AppActorRetryWakeScheduler
        scheduler = AppActorRetryWakeScheduler(
            queueStore = queueStore,
            backgroundScope = scope,
            dateProviderMillis = { now },
            activeRateLimitCooldown = { null },
            runDrainUnderPipelineLock = {
                // The first receipt failed and retries in a minute; the drainer re-arms the wake.
                every { queueStore.snapshot() } returns listOf(queueItem().copy(nextRetryAtMillis = now + 60_000))
                scheduler.scheduleNextRetryWake()
                // Posting the next claimed receipt suspends.
                delay(50)
                drainFinished.complete(Unit)
            },
        )

        scheduler.scheduleNextRetryWake()

        withTimeout(5_000) { drainFinished.await() }
        scope.cancel()
    }
}
