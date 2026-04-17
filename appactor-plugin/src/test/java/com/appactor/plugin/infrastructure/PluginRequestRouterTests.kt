package com.appactor.plugin.infrastructure

import com.appactor.android.api.AppActor
import com.appactor.android.models.AppActorError
import io.mockk.coEvery
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PluginRequestRouterTests {

    @Before
    fun setUp() {
        PluginRequestRouter.registerDefaults()
    }

    @Test
    fun `register defaults includes quiet sync and drain methods`() {
        val methods = PluginRequestRouter.availableMethods

        assertTrue(methods.contains("quiet_sync_purchases"))
        assertTrue(methods.contains("drain_receipt_queue_and_refresh_customer"))
    }

    @Test
    fun `quiet sync and drain aliases resolve to registered handlers`() = runBlocking {
        val quietResult = PluginRequestRouter.route("quiet_sync_purchases", "{}")
        val drainResult = PluginRequestRouter.route("drain_receipt_queue_and_refresh_customer", "{}")

        assertEquals(PluginError.SDK_NOT_CONFIGURED, (quietResult as PluginResult.Error).error.code)
        assertEquals(PluginError.SDK_NOT_CONFIGURED, (drainResult as PluginResult.Error).error.code)
    }

    @Test
    fun `get offerings returns 2008 when native sdk surfaces store products missing`() = runBlocking {
        mockkObject(AppActor)
        try {
            coEvery { AppActor.offerings() } throws AppActorError.StoreProductsMissing(
                "Failed to resolve Play product details for productId=com.appactor.pro.monthly"
            )

            val result = PluginRequestRouter.route("get_offerings", "{}")

            assertEquals(PluginError.SDK_STORE_PRODUCTS_MISSING, (result as PluginResult.Error).error.code)
        } finally {
            unmockkObject(AppActor)
        }
    }
}
