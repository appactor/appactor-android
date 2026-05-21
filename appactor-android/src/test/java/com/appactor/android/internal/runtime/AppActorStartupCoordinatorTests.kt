package com.appactor.android.internal.runtime

import com.appactor.android.billing.AppActorStorePurchase
import com.appactor.android.models.AppActorCustomerInfo
import com.appactor.android.models.AppActorDebugCategory
import com.appactor.android.models.AppActorDiagnosticsDataSource
import com.appactor.android.models.AppActorLogLevel
import com.appactor.android.pipeline.AppActorPurchaseUpdateProcessingResult
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `startup bootstraps without an identify readiness phase`() = runBlocking {
        val runtime = createRuntimeState(
            options = runtimeTestOptions(),
        )
        val host = RecordingStartupHost(runtime).apply {
            deferredStepsExpected = CountDownLatch(4)
        }
        val coordinator = AppActorStartupCoordinator(host)

        val handles = coordinator.start(runtime)
        handles.bootstrapCompletionJob?.join()

        assertTrue(host.deferredStepsExpected!!.await(5, TimeUnit.SECONDS))
        val order = host.operationOrder.toList()
        assertTrue(order.contains("offerings"))
        assertTrue(order.contains("sync"))
        assertTrue(order.contains("dead_letter_retry"))
        assertTrue(order.contains("customer"))
        assertTrue("sync must come before dead_letter_retry", order.indexOf("sync") < order.indexOf("dead_letter_retry"))
        assertTrue("dead_letter_retry must come before customer", order.indexOf("dead_letter_retry") < order.indexOf("customer"))
        runtime.scope.cancel()
    }

    @Test
    fun `bootstrap completion does not wait for offerings prefetch warmup`() = runBlocking {
        val runtime = createRuntimeState(
            options = runtimeTestOptions(),
        )
        val host = RecordingStartupHost(runtime).apply {
            deferredStepsExpected = CountDownLatch(3)
            offeringsPrefetchStarted = CountDownLatch(1)
            releaseOfferingsPrefetch = CountDownLatch(1)
            offeringsPrefetchFinished = CountDownLatch(1)
        }
        val coordinator = AppActorStartupCoordinator(host)

        val handles = coordinator.start(runtime)

        assertTrue(host.offeringsPrefetchStarted!!.await(5, TimeUnit.SECONDS))
        assertTrue(host.deferredStepsExpected!!.await(5, TimeUnit.SECONDS))
        withTimeout(5_000L) {
            handles.bootstrapCompletionJob?.join()
        }

        val order = host.operationOrder.toList()
        assertTrue(order.contains("sync"))
        assertTrue(order.contains("dead_letter_retry"))
        assertTrue(order.contains("customer"))
        assertFalse(host.offeringsPrefetchFinished!!.await(200, TimeUnit.MILLISECONDS))

        host.releaseOfferingsPrefetch!!.countDown()
        assertTrue(host.offeringsPrefetchFinished!!.await(5, TimeUnit.SECONDS))
        assertTrue(host.deferredStepsExpected!!.await(5, TimeUnit.SECONDS))
        assertTrue("sync must come before dead_letter_retry", order.indexOf("sync") < order.indexOf("dead_letter_retry"))
        assertTrue("dead_letter_retry must come before customer", order.indexOf("dead_letter_retry") < order.indexOf("customer"))
        runtime.scope.cancel()
    }

    @Test
    fun `customer refresh waits for synchronous dead letter retry drain`() = runBlocking {
        val runtime = createRuntimeState(
            options = runtimeTestOptions(),
        )
        val host = RecordingStartupHost(runtime).apply {
            deadLetterRetryStarted = CountDownLatch(1)
            releaseDeadLetterRetry = CountDownLatch(1)
            customerFetchStarted = CountDownLatch(1)
            retryDeadLetterResult = AppActorCustomerInfo.empty.copy(
                appUserId = runtime.identityStore.currentAppUserId ?: runtime.identityStore.ensureAppUserId(),
            )
        }
        val coordinator = AppActorStartupCoordinator(host)

        val handles = coordinator.start(runtime)

        assertTrue(host.deadLetterRetryStarted!!.await(5, TimeUnit.SECONDS))
        assertFalse(host.customerFetchStarted!!.await(200, TimeUnit.MILLISECONDS))

        host.releaseDeadLetterRetry!!.countDown()
        handles.bootstrapCompletionJob?.join()

        assertTrue(host.customerFetchStarted!!.await(5, TimeUnit.SECONDS))
        runtime.scope.cancel()
    }

    @Test
    fun `purchase updates use resolved local identity immediately`() = runBlocking {
        val purchaseUpdates = MutableSharedFlow<List<AppActorStorePurchase>>(replay = 1)
        val runtime = createRuntimeState(
            storeAdapter = createMockStoreAdapter(purchaseUpdatesFlow = purchaseUpdates),
            options = runtimeTestOptions(),
        )
        val host = RecordingStartupHost(runtime).apply {
            purchaseUpdateProcessed = CountDownLatch(1)
        }
        val coordinator = AppActorStartupCoordinator(host)

        coordinator.start(runtime)
        purchaseUpdates.emit(listOf(runtimeTestPurchase(obfuscatedAccountId = runtime.identityStore.currentAppUserId)))

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
            purchaseUpdateProcessed = CountDownLatch(1)
            bootstrapSnapshotAwaited = CountDownLatch(1)
            releaseBootstrapSnapshot = CountDownLatch(1)
        }
        val coordinator = AppActorStartupCoordinator(host)

        coordinator.start(runtime)
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

        handles.bootstrapCompletionJob?.join()
        purchaseUpdates.emit(listOf(runtimeTestPurchase(obfuscatedAccountId = runtime.identityStore.currentAppUserId)))

        assertTrue(host.purchaseUpdateProcessed!!.await(5, TimeUnit.SECONDS))
        assertEquals(0, host.publishCustomerInfoCount.get())
        runtime.scope.cancel()
    }

    @Test
    fun `startup no longer emits identify phase debug event`() = runBlocking {
        val runtime = createRuntimeState(
            options = runtimeTestOptions(),
        )
        val host = RecordingStartupHost(runtime)
        val coordinator = AppActorStartupCoordinator(host)

        val handles = coordinator.start(runtime)
        handles.bootstrapCompletionJob?.join()

        val identifyEvent = host.debugEvents.firstOrNull { it["name"] == "startup_phase_identify" }
        assertEquals(null, identifyEvent)

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
        val processPurchaseUpdatesCount = AtomicInteger(0)
        val publishCustomerInfoCount = AtomicInteger(0)

        var deferredStepsExpected: CountDownLatch? = null
        var purchaseUpdateProcessed: CountDownLatch? = null
        var bootstrapSnapshotAwaited: CountDownLatch? = null
        var releaseBootstrapSnapshot: CountDownLatch? = null
        var offeringsPrefetchStarted: CountDownLatch? = null
        var releaseOfferingsPrefetch: CountDownLatch? = null
        var offeringsPrefetchFinished: CountDownLatch? = null
        var deadLetterRetryStarted: CountDownLatch? = null
        var releaseDeadLetterRetry: CountDownLatch? = null
        var customerFetchStarted: CountDownLatch? = null
        var persistResult: Boolean = true
        var retryDeadLetterResult: AppActorCustomerInfo? = null

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

        override suspend fun retryDeadLetteredItems(
            snapshot: AppActorOperationSnapshot,
        ): AppActorCustomerInfo? {
            deadLetterRetryStarted?.countDown()
            releaseDeadLetterRetry?.let { assertTrue(it.await(5, TimeUnit.SECONDS)) }
            operationOrder += "dead_letter_retry"
            deferredStepsExpected?.countDown()
            return retryDeadLetterResult
        }

        override suspend fun prefetchOfferings(runtimeState: AppActorRuntimeState): AppActorDiagnosticsDataSource? {
            offeringsPrefetchStarted?.countDown()
            releaseOfferingsPrefetch?.let { assertTrue(it.await(5, TimeUnit.SECONDS)) }
            operationOrder += "offerings"
            deferredStepsExpected?.countDown()
            offeringsPrefetchFinished?.countDown()
            return AppActorDiagnosticsDataSource.Network
        }

        override suspend fun fetchCustomerInfo(
            snapshot: AppActorOperationSnapshot,
        ): Pair<AppActorCustomerInfo, AppActorDiagnosticsDataSource?> {
            customerFetchStarted?.countDown()
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
            purchases: List<AppActorStorePurchase>,
        ): AppActorPurchaseUpdateProcessingResult? {
            processPurchaseUpdatesCount.incrementAndGet()
            purchaseUpdateProcessed?.countDown()
            val appUserId = runtime.identityStore.currentAppUserId ?: runtime.identityStore.ensureAppUserId()
            return AppActorPurchaseUpdateProcessingResult(
                customerInfo = customerInfo(appUserId),
                appUserId = appUserId,
            )
        }

        override suspend fun publishPurchaseUpdateIfCurrent(
            runtimeState: AppActorRuntimeState,
            result: AppActorPurchaseUpdateProcessingResult,
        ): Boolean {
            if (!persistResult) return false
            publishCustomerInfoCount.incrementAndGet()
            return true
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
