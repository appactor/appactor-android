package com.appactor.android.pipeline

import com.appactor.android.api.queueItem
import com.appactor.android.storage.AppActorReceiptQueueStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

class AppActorRetryWakeSchedulerTests {

    @Test
    fun `a drain that re-arms the wake for a failed receipt runs to its end`() = runBlocking {
        val now = 1_000_000L
        val queueStore = mockk<AppActorReceiptQueueStore>()
        every { queueStore.snapshot() } returns listOf(queueItem().copy(nextRetryAtMillis = now))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val drainFinished = CompletableDeferred<Boolean>()
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
                drainFinished.complete(true)
            },
        )

        scheduler.scheduleNextRetryWake()

        assertTrue(withTimeout(5_000) { drainFinished.await() })
        scope.cancel()
    }
}
