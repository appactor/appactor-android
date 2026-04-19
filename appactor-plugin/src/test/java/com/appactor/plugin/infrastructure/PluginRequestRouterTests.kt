package com.appactor.plugin.infrastructure

import android.content.Context
import com.appactor.android.api.AppActor
import com.appactor.android.models.AppActorError
import com.appactor.android.models.AppActorLogLevel
import com.appactor.android.models.AppActorOfferings
import com.appactor.android.models.AppActorOfferingsFetchPolicy
import com.appactor.android.models.AppActorOptions
import com.appactor.android.models.AppActorPlatformInfo
import com.appactor.plugin.AppActorPlugin
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockk
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
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        AppActorPlugin.setContext(context)
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
            coEvery { AppActor.offerings(any()) } throws AppActorError.StoreProductsMissing(
                "Failed to resolve Play product details for productId=com.appactor.pro.monthly"
            )

            val result = PluginRequestRouter.route("get_offerings", "{}")

            assertEquals(PluginError.SDK_STORE_PRODUCTS_MISSING, (result as PluginResult.Error).error.code)
        } finally {
            unmockkObject(AppActor)
        }
    }

    @Test
    fun `configure accepts canonical nested platform info contract`() = runBlocking {
        mockkObject(AppActor)
        try {
            coEvery { AppActor.configure(any(), any(), any(), any()) } returns Unit

            val result = PluginRequestRouter.route(
                "configure",
                """
                {
                  "api_key": "pk_test_123",
                  "platform_flavor": "legacy",
                  "platform_version": "0.9.0",
                  "options": {
                    "log_level": "debug",
                    "platform_info": {
                      "flavor": "flutter",
                      "version": "1.2.3"
                    }
                  }
                }
                """.trimIndent(),
            )

            assertTrue(result is PluginResult.Success)
            coVerify(exactly = 1) {
                AppActor.configure(
                    any(),
                    "pk_test_123",
                    null,
                    withArg<AppActorOptions> { options ->
                        assertEquals(AppActorLogLevel.Debug, options.logLevel)
                        assertEquals(AppActorPlatformInfo("flutter", "1.2.3"), options.platformInfo)
                    },
                )
            }
        } finally {
            unmockkObject(AppActor)
        }
    }

    @Test
    fun `configure leaves platform info null when omitted`() = runBlocking {
        mockkObject(AppActor)
        try {
            coEvery { AppActor.configure(any(), any(), any(), any()) } returns Unit

            val result = PluginRequestRouter.route(
                "configure",
                """{"api_key":"pk_test_123"}""",
            )

            assertTrue(result is PluginResult.Success)
            coVerify(exactly = 1) {
                AppActor.configure(
                    any(),
                    "pk_test_123",
                    null,
                    withArg<AppActorOptions> { options ->
                        assertEquals(null, options.platformInfo)
                    },
                )
            }
        } finally {
            unmockkObject(AppActor)
        }
    }

    @Test
    fun `configure keeps legacy top level platform aliases during migration`() = runBlocking {
        mockkObject(AppActor)
        try {
            coEvery { AppActor.configure(any(), any(), any(), any()) } returns Unit

            val result = PluginRequestRouter.route(
                "configure",
                """
                {
                  "api_key": "pk_test_123",
                  "platform_info": {
                    "flavor": "flutter",
                    "version": "1.2.3"
                  }
                }
                """.trimIndent(),
            )

            assertTrue(result is PluginResult.Success)
            coVerify(exactly = 1) {
                AppActor.configure(
                    any(),
                    "pk_test_123",
                    null,
                    withArg<AppActorOptions> { options ->
                        assertEquals(AppActorPlatformInfo("flutter", "1.2.3"), options.platformInfo)
                    },
                )
            }
        } finally {
            unmockkObject(AppActor)
        }
    }

    @Test
    fun `configure keeps flutter fallback when only version is provided`() = runBlocking {
        mockkObject(AppActor)
        try {
            coEvery { AppActor.configure(any(), any(), any(), any()) } returns Unit

            val result = PluginRequestRouter.route(
                "configure",
                """{"api_key":"pk_test_123","platform_version":"1.2.3"}""",
            )

            assertTrue(result is PluginResult.Success)
            coVerify(exactly = 1) {
                AppActor.configure(
                    any(),
                    "pk_test_123",
                    null,
                    withArg<AppActorOptions> { options ->
                        assertEquals(AppActorPlatformInfo("flutter", "1.2.3"), options.platformInfo)
                    },
                )
            }
        } finally {
            unmockkObject(AppActor)
        }
    }

    @Test
    fun `plugin errors preserve structured server diagnostics`() = runBlocking {
        mockkObject(AppActor)
        try {
            coEvery { AppActor.offerings(any()) } throws AppActorError.Server(
                description = "Too many requests",
                statusCode = 429,
                scope = "app",
                retryAfterSeconds = 12.5,
            )

            val result = PluginRequestRouter.route("get_offerings", "{}")

            result as PluginResult.Error
            assertEquals(PluginError.SDK_SERVER, result.error.code)
            assertEquals(null, result.error.requestId)
            assertEquals("app", result.error.scope)
            assertEquals(12.5, result.error.retryAfterSeconds ?: -1.0, 0.0)
        } finally {
            unmockkObject(AppActor)
        }
    }

    @Test
    fun `plugin errors preserve request id for customer not found`() = runBlocking {
        mockkObject(AppActor)
        try {
            coEvery { AppActor.offerings(any()) } throws AppActorError.CustomerNotFound(
                appUserId = "user_123",
                requestId = "req_404",
            )

            val result = PluginRequestRouter.route("get_offerings", "{}")

            result as PluginResult.Error
            assertEquals(PluginError.SDK_CUSTOMER_NOT_FOUND, result.error.code)
            assertEquals("req_404", result.error.requestId)
        } finally {
            unmockkObject(AppActor)
        }
    }

    @Test
    fun `get offerings accepts canonical fetch policy wire values`() = runBlocking {
        mockkObject(AppActor)
        try {
            coEvery { AppActor.offerings(any()) } returns AppActorOfferings()

            val result = PluginRequestRouter.route(
                "get_offerings",
                """{"fetch_policy":"cacheOnly"}""",
            )

            assertTrue(result is PluginResult.Success)
            coVerify(exactly = 1) {
                AppActor.offerings(AppActorOfferingsFetchPolicy.CacheOnly)
            }
        } finally {
            unmockkObject(AppActor)
        }
    }
}
