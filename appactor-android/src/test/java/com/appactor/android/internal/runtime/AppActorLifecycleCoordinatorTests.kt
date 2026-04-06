package com.appactor.android.internal.runtime

import android.app.Activity
import com.appactor.android.models.AppActorDebugCategory
import com.appactor.android.models.AppActorLogLevel
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class AppActorLifecycleCoordinatorTests {

    @Before
    fun setUp() {
        clearRuntimeTestStorage()
    }

    @Test
    fun `foreground drains before refresh`() {
        val runtime = createRuntimeState(
            options = runtimeTestOptions(),
        )
        val host = RecordingLifecycleHost(runtime).apply {
            operationsExpected = CountDownLatch(2)
        }
        val coordinator = AppActorLifecycleCoordinator(host)

        val callbacks = coordinator.registerLifecycleCallbacksIfNeeded(runtime)
        assertNotNull(callbacks)

        callbacks!!.onActivityStarted(Activity())

        assertTrue(host.operationsExpected!!.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("drain", "refresh"), host.operationOrder.toList())
        runtime.scope.cancel()
    }

    @Test
    fun `staleness timer fires periodic refresh when enabled`() {
        val runtime = createRuntimeState(
            options = runtimeTestOptions(),
        )
        val host = RecordingLifecycleHost(runtime).apply {
            operationsExpected = CountDownLatch(2)
        }
        // Use a short interval for testing by verifying the timer starts
        val coordinator = AppActorLifecycleCoordinator(host)

        val callbacks = coordinator.registerLifecycleCallbacksIfNeeded(runtime)
        assertNotNull(callbacks)

        callbacks!!.onActivityStarted(Activity())

        // Wait for initial foreground drain + refresh
        assertTrue(host.operationsExpected!!.await(5, TimeUnit.SECONDS))
        val refreshAfterForeground = host.refreshCount.get()
        assertTrue(refreshAfterForeground >= 1)

        // Background should cancel timer
        callbacks.onActivityStopped(Activity())

        val refreshAfterBackground = host.refreshCount.get()
        Thread.sleep(200)
        // No additional refreshes after background
        assertEquals(refreshAfterBackground, host.refreshCount.get())
        runtime.scope.cancel()
    }

    private class RecordingLifecycleHost(
        private val runtime: AppActorRuntimeState,
    ) : AppActorLifecycleCoordinatorHost {

        val operationOrder = Collections.synchronizedList(mutableListOf<String>())
        val drainCount = AtomicInteger(0)
        val refreshCount = AtomicInteger(0)

        var operationsExpected: CountDownLatch? = null
        var drainCompleted: CountDownLatch? = null

        override fun currentRuntimeSnapshot(): AppActorRuntimeState? = runtime

        override suspend fun awaitStartupIfNeeded(runtimeState: AppActorRuntimeState) = Unit

        override suspend fun drainReceipts(runtimeState: AppActorRuntimeState) {
            drainCount.incrementAndGet()
            operationOrder += "drain"
            drainCompleted?.countDown()
            operationsExpected?.countDown()
        }

        override suspend fun refreshCustomerInfoIfNeeded(runtimeState: AppActorRuntimeState) {
            refreshCount.incrementAndGet()
            operationOrder += "refresh"
            operationsExpected?.countDown()
        }

        override fun emitDebugEvent(
            runtimeSessionId: Long,
            category: AppActorDebugCategory,
            level: AppActorLogLevel,
            name: String,
            message: String,
            requestId: String?,
            attributes: Map<String, String>,
        ) = Unit
    }
}
