package com.appactor.android.internal.runtime

import com.appactor.android.models.AppActorCustomerInfo
import com.appactor.android.models.AppActorReceiptPipelineEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppActorRuntimeFactoryTests {

    @Before
    fun setUp() {
        clearRuntimeTestStorage()
    }

    @Test
    fun `runtime factory creates dependency graph and preserves callback wiring`() {
        val storeAdapter = createMockStoreAdapter()
        val customerCallback: (AppActorCustomerInfo) -> Unit = {}
        val receiptCallback: (AppActorReceiptPipelineEvent) -> Unit = {}

        val runtime = createRuntimeState(
            storeAdapter = storeAdapter,
            sessionId = 42L,
            appUserId = "user_factory_123",
            callbackState = AppActorCallbackState(
                onCustomerInfoChanged = customerCallback,
                onReceiptPipelineEvent = receiptCallback,
            ),
        )

        assertEquals(42L, runtime.sessionId)
        assertEquals("user_factory_123", runtime.identityStore.currentAppUserId)
        assertSame(storeAdapter, runtime.storeAdapter)
        assertSame(customerCallback, runtime.onCustomerInfoChanged)
        assertSame(receiptCallback, runtime.onReceiptPipelineEvent)
        assertNotNull(runtime.paymentProcessor)
        assertNotNull(runtime.customerManager)
        assertNotNull(runtime.offeringsManager)
        assertNotNull(runtime.remoteConfigManager)
        assertNotNull(runtime.experimentManager)
    }
}
