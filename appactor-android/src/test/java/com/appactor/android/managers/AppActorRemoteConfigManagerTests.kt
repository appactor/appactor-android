package com.appactor.android.managers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.appactor.android.backend.client.AppActorBackendClient
import com.appactor.android.backend.client.AppActorBackendHttpResponse
import com.appactor.android.backend.client.AppActorBackendJson
import com.appactor.android.backend.dto.AppActorRemoteConfigItemDTO
import com.appactor.android.backend.dto.AppActorRemoteConfigsEnvelopeDTO
import com.appactor.android.cache.AppActorCacheDiskStore
import com.appactor.android.cache.AppActorETagManager
import com.appactor.android.cache.AppActorRemoteConfigsCacheStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class AppActorRemoteConfigManagerTests {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `remote config manager caches successful fetch in memory`() = runBlocking {
        var now = 1_710_000_000_000L
        val mockClient = mockk<AppActorBackendClient>(relaxed = true)
        coEvery { mockClient.getRemoteConfigs(any(), any(), any(), any()) } returns AppActorBackendHttpResponse(
            body = sampleEnvelope(),
            statusCode = 200,
            requestId = "req_remote_001",
            eTag = "\"etag_remote\"",
            signatureVerified = true,
        )
        val manager = createManager(
            backendClient = mockClient,
            dateProviderMillis = { now },
        )

        val first = manager.getRemoteConfigs(appUserId = "user_android_123")
        val second = manager.getRemoteConfigs(appUserId = "user_android_123")

        assertEquals(true, first["has_rating"]?.boolValue)
        assertEquals("1.2.3", second["min_version"]?.stringValue)
        coVerify(exactly = 1) { mockClient.getRemoteConfigs(any(), any(), any(), any()) }
    }

    @Test
    fun `remote config manager handles not modified via disk cache`() = runBlocking {
        val cacheStore = createCacheStore("remote-config-304")
        cacheStore.save(
            appUserId = "user_android_123",
            payload = AppActorBackendJson.instance.encodeToString(sampleEnvelope()),
            eTag = "\"etag_remote\"",
            verified = true,
        )
        val mockClient = mockk<AppActorBackendClient>(relaxed = true)
        coEvery { mockClient.getRemoteConfigs(any(), any(), any(), any()) } returns AppActorBackendHttpResponse(
            body = null,
            statusCode = 304,
            requestId = "req_remote_304",
            eTag = "\"etag_remote\"",
            isNotModified = true,
        )
        val manager = createManager(
            backendClient = mockClient,
            cacheStore = cacheStore,
        )

        val configs = manager.getRemoteConfigs(appUserId = "user_android_123")

        assertEquals("1.2.3", configs["min_version"]?.stringValue)
        assertEquals("req_remote_304", manager.requestId())
    }

    @Test
    fun `remote config manager falls back to disk cache on network error`() = runBlocking {
        val cacheStore = createCacheStore("remote-config-fallback")
        cacheStore.save(
            appUserId = "user_android_123",
            payload = AppActorBackendJson.instance.encodeToString(sampleEnvelope()),
            eTag = "\"etag_remote\"",
            verified = true,
        )
        val mockClient = mockk<AppActorBackendClient>(relaxed = true)
        coEvery { mockClient.getRemoteConfigs(any(), any(), any(), any()) } throws IOException("offline")
        val manager = createManager(
            backendClient = mockClient,
            cacheStore = cacheStore,
        )

        val configs = manager.getRemoteConfigs(appUserId = "user_android_123")

        assertTrue(configs.items.isNotEmpty())
        assertEquals(true, configs["has_rating"]?.boolValue)
    }

    @Test
    fun `remote config clear cache cancels in flight request and prevents stale repopulation`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val unblock = CompletableDeferred<Unit>()
        val cacheStore = createCacheStore("remote-config-clear-race")
        val mockClient = mockk<AppActorBackendClient>(relaxed = true)
        coEvery { mockClient.getRemoteConfigs(any(), any(), any(), any()) } coAnswers {
            started.complete(Unit)
            unblock.await()
            AppActorBackendHttpResponse(
                body = sampleEnvelope(),
                statusCode = 200,
                requestId = "req_remote_race",
                eTag = "\"etag_remote_race\"",
                signatureVerified = true,
            )
        }
        val manager = createManager(
            backendClient = mockClient,
            cacheStore = cacheStore,
        )

        val failure = async {
            runCatching { manager.getRemoteConfigs(appUserId = "user_android_123") }.exceptionOrNull()
        }

        started.await()
        manager.clearCache("user_android_123")
        unblock.complete(Unit)

        assertTrue(failure.await() is CancellationException)
        assertNull(manager.cached())
        assertNull(cacheStore.load("user_android_123"))
    }

    @Test
    fun `remote config fallback does not leak another user's disk cache`() = runBlocking {
        val cacheStore = createCacheStore("remote-config-cross-user")
        cacheStore.save(
            appUserId = "user_android_A",
            payload = AppActorBackendJson.instance.encodeToString(sampleEnvelope()),
            eTag = "\"etag_remote_A\"",
            verified = true,
        )
        val mockClient = mockk<AppActorBackendClient>(relaxed = true)
        coEvery { mockClient.getRemoteConfigs(any(), any(), any(), any()) } throws IOException("offline")
        val manager = createManager(
            backendClient = mockClient,
            cacheStore = cacheStore,
        )

        val failure = runCatching {
            manager.getRemoteConfigs(appUserId = "user_android_B")
        }.exceptionOrNull()

        assertTrue(failure != null)
    }

    @Test
    fun `remote config concurrent callers share in flight request without deadlock`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val unblock = CompletableDeferred<Unit>()
        val mockClient = mockk<AppActorBackendClient>(relaxed = true)
        coEvery { mockClient.getRemoteConfigs(any(), any(), any(), any()) } coAnswers {
            started.complete(Unit)
            unblock.await()
            AppActorBackendHttpResponse(
                body = sampleEnvelope(),
                statusCode = 200,
                requestId = "req_remote_single_flight",
                eTag = "\"etag_remote_single_flight\"",
                signatureVerified = true,
            )
        }
        val manager = createManager(backendClient = mockClient)

        val first = async { manager.getRemoteConfigs(appUserId = "user_android_123") }
        started.await()
        val second = async { manager.getRemoteConfigs(appUserId = "user_android_123") }
        unblock.complete(Unit)

        withTimeout(2_000) {
            assertEquals("1.2.3", first.await()["min_version"]?.stringValue)
            assertEquals("1.2.3", second.await()["min_version"]?.stringValue)
        }
        coVerify(exactly = 1) { mockClient.getRemoteConfigs(any(), any(), any(), any()) }
    }

    private fun createManager(
        backendClient: AppActorBackendClient,
        cacheStore: AppActorRemoteConfigsCacheStore = createCacheStore("remote-config-${UUID.randomUUID()}"),
        dateProviderMillis: () -> Long = { System.currentTimeMillis() },
    ): AppActorRemoteConfigManager {
        return AppActorRemoteConfigManager(
            backendClient = backendClient,
            cacheStore = cacheStore,
            appVersionProvider = { "1.0.0" },
            countryProvider = { "TR" },
            dateProviderMillis = dateProviderMillis,
        )
    }

    private fun createCacheStore(name: String): AppActorRemoteConfigsCacheStore {
        return AppActorRemoteConfigsCacheStore(
            AppActorETagManager(
                diskStore = AppActorCacheDiskStore(
                    context,
                    File(context.cacheDir, "tests/$name")
                ),
                responseVerificationEnabled = false,
            )
        )
    }

    private fun sampleEnvelope(): AppActorRemoteConfigsEnvelopeDTO {
        return AppActorRemoteConfigsEnvelopeDTO(
            data = listOf(
                AppActorRemoteConfigItemDTO(
                    key = "has_rating",
                    value = JsonPrimitive(true),
                    valueType = "boolean",
                ),
                AppActorRemoteConfigItemDTO(
                    key = "min_version",
                    value = JsonPrimitive("1.2.3"),
                    valueType = "string",
                ),
            ),
            requestId = "req_remote_001",
        )
    }
}
