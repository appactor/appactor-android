package com.appactor.android.internal.runtime

import com.appactor.android.billing.AppActorStorePurchase
import com.appactor.android.models.AppActorCustomerInfo
import com.appactor.android.models.AppActorDebugCategory
import com.appactor.android.models.AppActorDiagnosticsDataSource
import com.appactor.android.models.AppActorLogLevel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class AppActorStartupCoordinatorTests {

    private fun awaitCondition(timeoutMs: Long = 2000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    @Before
    fun setUp() {
        clearRuntimeTestStorage()
    }

    @Test
    fun `startup identify confirms identity after completion`() = runBlocking {
        val runtime = createRuntimeState(
            options = runtimeTestOptions(),
        )
        val host = RecordingStartupHost(runtime)
        val coordinator = AppActorStartupCoordinator(host)

        val handles = coordinator.start(runtime)
        handles.identityReadyJob?.join()

        assertEquals(1, host.identifyCount.get())
        assertEquals(1, host.confirmIdentityCount.get())
        runtime.scope.cancel()
    }

    @Test
    fun `deferred startup tasks preserve purchase sync then offerings then customer refresh order`() = runBlocking {
        val runtime = createRuntimeState(
            options = runtimeTestOptions(),
        )
        val host = RecordingStartupHost(runtime).apply {
            deferredStepsExpected = CountDownLatch(3)
        }
        val coordinator = AppActorStartupCoordinator(host)

        val handles = coordinator.start(runtime)
        handles.bootstrapCompletionJob?.join()

        assertTrue(host.deferredStepsExpected!!.await(5, TimeUnit.SECONDS))
        awaitCondition { host.operationOrder.size >= 4 }
        val order = host.operationOrder.toList()
        assertEquals("identify", order.first())
        assertTrue("sync must come before customer", order.indexOf("sync") < order.indexOf("customer"))
        assertTrue("offerings must be present", order.contains("offerings"))
        runtime.scope.cancel()
    }

    @Test
    fun `purchase updates wait for identity readiness before processing`() = runBlocking {
        val purchaseUpdates = MutableSharedFlow<List<AppActorStorePurchase>>(replay = 1)
        val runtime = createRuntimeState(
            storeAdapter = createMockStoreAdapter(purchaseUpdatesFlow = purchaseUpdates),
            options = runtimeTestOptions(),
        )
        val host = RecordingStartupHost(runtime).apply {
            releaseIdentify = CountDownLatch(1)
            purchaseUpdateProcessed = CountDownLatch(1)
        }
        val coordinator = AppActorStartupCoordinator(host)

        coordinator.start(runtime)
        purchaseUpdates.emit(listOf(runtimeTestPurchase(obfuscatedAccountId = runtime.identityStore.currentAppUserId)))

        Thread.sleep(150)
        assertEquals(0, host.processPurchaseUpdatesCount.get())

        host.releaseIdentify!!.countDown()

        assertTrue(host.purchaseUpdateProcessed!!.await(5, TimeUnit.SECONDS))
        assertEquals(1, host.processPurchaseUpdatesCount.get())
        runtime.scope.cancel()
    }

    @Test
    fun `purchase updates do not wait for deferred bootstrap completion`() = runBlocking {
        val purchaseUpdates = MutableSharedFlow<List<AppActorStorePurchase>>(extraBufferCapacity = 1)
        val runtime = createRuntimeState(
            storeAdapter = createMockStoreAdapter(purchaseUpdatesFlow = purchaseUpdates),
            options = runtimeTestOptions(),
        )
        val host = RecordingStartupHost(runtime).apply {
            releaseIdentify = CountDownLatch(1)
            purchaseUpdateProcessed = CountDownLatch(1)
            bootstrapSnapshotAwaited = CountDownLatch(1)
            releaseBootstrapSnapshot = CountDownLatch(1)
        }
        val coordinator = AppActorStartupCoordinator(host)

        coordinator.start(runtime)
        host.releaseIdentify!!.countDown()

        assertTrue(host.bootstrapSnapshotAwaited!!.await(5, TimeUnit.SECONDS))

        purchaseUpdates.emit(listOf(runtimeTestPurchase(obfuscatedAccountId = runtime.identityStore.currentAppUserId)))

        assertTrue(host.purchaseUpdateProcessed!!.await(5, TimeUnit.SECONDS))
        assertEquals(1, host.processPurchaseUpdatesCount.get())
        assertFalse(host.releaseBootstrapSnapshot!!.await(200, TimeUnit.MILLISECONDS))

        host.releaseBootstrapSnapshot!!.countDown()
        runtime.scope.cancel()
    }

    @Test
    fun `purchase updates do not publish when current session guard rejects persistence`() = runBlocking {
        val purchaseUpdates = MutableSharedFlow<List<AppActorStorePurchase>>(replay = 1)
        val runtime = createRuntimeState(
            storeAdapter = createMockStoreAdapter(purchaseUpdatesFlow = purchaseUpdates),
        )
        val host = RecordingStartupHost(runtime).apply {
            persistResult = false
            purchaseUpdateProcessed = CountDownLatch(1)
        }
        val coordinator = AppActorStartupCoordinator(host)
        val handles = coordinator.start(runtime)

        handles.identityReadyJob?.join()
        purchaseUpdates.emit(listOf(runtimeTestPurchase(obfuscatedAccountId = runtime.identityStore.currentAppUserId)))

        assertTrue(host.purchaseUpdateProcessed!!.await(5, TimeUnit.SECONDS))
        assertEquals(0, host.publishCustomerInfoCount.get())
        runtime.scope.cancel()
    }

    @Test
    fun `startup identify phase emits duration debug event`() = runBlocking {
        val runtime = createRuntimeState(
            options = runtimeTestOptions(),
        )
        val host = RecordingStartupHost(runtime)
        val coordinator = AppActorStartupCoordinator(host)

        val handles = coordinator.start(runtime)
        handles.identityReadyJob?.join()

        val identifyEvent = host.debugEvents.firstOrNull { it["name"] == "startup_phase_identify" }
        assertNotNull("Expected startup_phase_identify debug event", identifyEvent)
        val durationMs = identifyEvent!!["duration_ms"]?.toLongOrNull()
        assertNotNull("duration_ms should be present", durationMs)
        assertTrue("duration_ms should be non-negative", durationMs!! >= 0)

        runtime.scope.cancel()
    }

    @Test
    fun `deferred startup phases emit duration debug events`() = runBlocking {
        val runtime = createRuntimeState(
            options = runtimeTestOptions(),
        )
        val host = RecordingStartupHost(runtime).apply {
            deferredStepsExpected = CountDownLatch(3)
        }
        val coordinator = AppActorStartupCoordinator(host)

        val handles = coordinator.start(runtime)
        handles.bootstrapCompletionJob?.join()
        assertTrue(host.deferredStepsExpected!!.await(5, TimeUnit.SECONDS))
        awaitCondition {
            val names = host.debugEvents.map { it["name"] }.toSet()
            names.contains("startup_phase_purchase_sync") && names.contains("startup_phase_offerings") && names.contains("startup_phase_customer_refresh")
        }

        val eventNames = host.debugEvents.filter { it.containsKey("duration_ms") }.map { it["name"] }.toSet()
        assertTrue("Expected purchase_sync event, got: $eventNames", eventNames.contains("startup_phase_purchase_sync"))
        assertTrue("Expected offerings event, got: $eventNames", eventNames.contains("startup_phase_offerings"))
        assertTrue("Expected customer_refresh event, got: $eventNames", eventNames.contains("startup_phase_customer_refresh"))

        runtime.scope.cancel()
    }

    private class RecordingStartupHost(
        private val runtime: AppActorRuntimeState,
    ) : AppActorStartupCoordinatorHost {

        val operationOrder = CopyOnWriteArrayList<String>()
        val debugEvents = CopyOnWriteArrayList<Map<String, String>>()
        val identifyCount = AtomicInteger(0)
        val confirmIdentityCount = AtomicInteger(0)
        val processPurchaseUpdatesCount = AtomicInteger(0)
        val publishCustomerInfoCount = AtomicInteger(0)

        var releaseIdentify: CountDownLatch? = null
        var deferredStepsExpected: CountDownLatch? = null
        var purchaseUpdateProcessed: CountDownLatch? = null
        var bootstrapSnapshotAwaited: CountDownLatch? = null
        var releaseBootstrapSnapshot: CountDownLatch? = null
        var persistResult: Boolean = true

        override suspend fun performStartupIdentify(
            runtimeState: AppActorRuntimeState,
        ): Pair<((AppActorCustomerInfo) -> Unit)?, AppActorCustomerInfo>? {
            identifyCount.incrementAndGet()
            operationOrder += "identify"
            releaseIdentify?.await(5, TimeUnit.SECONDS)
            return null
        }

        override fun confirmIdentity(runtimeState: AppActorRuntimeState) {
            confirmIdentityCount.incrementAndGet()
        }

        override suspend fun captureOperationSnapshot(
            resolveAppUserId: Boolean,
            awaitBootstrapCompletion: Boolean,
        ): AppActorOperationSnapshot {
            val appUserId = runtime.identityStore.currentAppUserId ?: runtime.identityStore.ensureAppUserId()
            return AppActorOperationSnapshot(
                runtime = runtime,
                epoch = 1L,
                appUserId = appUserId,
            )
        }

        override suspend fun syncCurrentPurchases(snapshot: AppActorOperationSnapshot): AppActorCustomerInfo? {
            operationOrder += "sync"
            bootstrapSnapshotAwaited?.countDown()
            releaseBootstrapSnapshot?.let { assertTrue(it.await(5, TimeUnit.SECONDS)) }
            deferredStepsExpected?.countDown()
            return null
        }

        override suspend fun fetchOfferings(runtimeState: AppActorRuntimeState): AppActorDiagnosticsDataSource? {
            operationOrder += "offerings"
            deferredStepsExpected?.countDown()
            return AppActorDiagnosticsDataSource.Network
        }

        override suspend fun fetchCustomerInfo(
            snapshot: AppActorOperationSnapshot,
        ): Pair<AppActorCustomerInfo, AppActorDiagnosticsDataSource?> {
            operationOrder += "customer"
            deferredStepsExpected?.countDown()
            return customerInfo(snapshot.appUserId) to AppActorDiagnosticsDataSource.Network
        }

        override suspend fun persistCustomerInfoIfCurrent(
            snapshot: AppActorOperationSnapshot,
            info: AppActorCustomerInfo,
        ): Boolean {
            return persistResult
        }

        override suspend fun publishCustomerInfoIfCurrent(
            snapshot: AppActorOperationSnapshot,
            info: AppActorCustomerInfo,
            source: AppActorDiagnosticsDataSource?,
        ): Boolean {
            publishCustomerInfoCount.incrementAndGet()
            return true
        }

        override suspend fun persistOfferingsSource(
            runtimeSessionId: Long,
            source: AppActorDiagnosticsDataSource?,
        ) = Unit

        override suspend fun processPurchaseUpdates(
            runtimeState: AppActorRuntimeState,
            snapshot: AppActorOperationSnapshot,
            purchases: List<AppActorStorePurchase>,
        ): AppActorCustomerInfo? {
            processPurchaseUpdatesCount.incrementAndGet()
            purchaseUpdateProcessed?.countDown()
            return customerInfo(snapshot.appUserId)
        }

        override fun deliverOnMain(block: () -> Unit) {
            block()
        }

        override fun emitDebugEvent(
            runtimeSessionId: Long,
            category: AppActorDebugCategory,
            level: AppActorLogLevel,
            name: String,
            message: String,
            requestId: String?,
            attributes: Map<String, String>,
        ) {
            debugEvents += buildMap {
                put("name", name)
                putAll(attributes)
            }
        }

        private fun customerInfo(appUserId: String): AppActorCustomerInfo {
            return AppActorCustomerInfo.empty.copy(
                appUserId = appUserId,
            )
        }
    }
}
