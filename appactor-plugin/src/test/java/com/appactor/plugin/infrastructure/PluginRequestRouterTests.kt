package com.appactor.plugin.infrastructure

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
}
