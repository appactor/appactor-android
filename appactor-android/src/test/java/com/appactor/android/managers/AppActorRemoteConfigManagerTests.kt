package com.appactor.android.managers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.appactor.android.backend.client.AppActorBackendClient
import com.appactor.android.backend.client.AppActorBackendHttpResponse
import com.appactor.android.backend.client.AppActorBackendJson
import com.appactor.android.backend.dto.AppActorRemoteConfigItemDTO
import com.appactor.android.backend.dto.AppActorRemoteConfigsEnvelopeDTO
import com.appactor.android.cache.AppActorCacheDiskStore
import com.appactor.android.cache.AppActorCacheResource
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
            remoteConfigRequiresUserContext = false,
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
            appUserId = null,
            appVersion = "1.0.0",
            country = "TR",
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
            remoteConfigRequiresUserContext = false,
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
            appUserId = null,
            appVersion = "1.0.0",
            country = "TR",
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

        val configs = manager.getRemoteConfigs(appUserId = "")

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
        assertNull(cacheStore.load(appUserId = null, appVersion = "1.0.0", country = "TR"))
    }

    @Test
    fun `remote config fallback does not leak another user's disk cache`() = runBlocking {
        val cacheStore = createCacheStore("remote-config-cross-user")
        cacheStore.save(
            appUserId = "user_android_A",
            appVersion = "1.0.0",
            country = "TR",
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
                remoteConfigRequiresUserContext = false,
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

    @Test
    fun `remote config public-first omits app user id when backend says public`() = runBlocking {
        val mockClient = mockk<AppActorBackendClient>(relaxed = true)
        coEvery { mockClient.getRemoteConfigs(any(), any(), any(), any()) } returns AppActorBackendHttpResponse(
            body = sampleEnvelope(),
            statusCode = 200,
            requestId = "req_public",
            eTag = "\"etag_public\"",
            signatureVerified = true,
            remoteConfigRequiresUserContext = false,
        )
        val manager = createManager(backendClient = mockClient)

        val configs = manager.getRemoteConfigs(appUserId = "user_android_123")

        assertEquals(true, configs["has_rating"]?.boolValue)
        coVerify(exactly = 1) {
            mockClient.getRemoteConfigs(
                appUserId = null,
                appVersion = "1.0.0",
                country = "TR",
                eTag = any(),
            )
        }
    }

    @Test
    fun `remote config refetches with app user id when backend requires user context`() = runBlocking {
        val mockClient = mockk<AppActorBackendClient>(relaxed = true)
        coEvery { mockClient.getRemoteConfigs(null, any(), any(), any()) } returns AppActorBackendHttpResponse(
            body = sampleEnvelope("audience" to JsonPrimitive("public")),
            statusCode = 200,
            requestId = "req_public",
            eTag = "\"etag_public\"",
            signatureVerified = true,
            remoteConfigRequiresUserContext = true,
        )
        coEvery { mockClient.getRemoteConfigs("user_android_123", any(), any(), any()) } returns AppActorBackendHttpResponse(
            body = sampleEnvelope("audience" to JsonPrimitive("premium")),
            statusCode = 200,
            requestId = "req_user",
            eTag = "\"etag_user\"",
            signatureVerified = true,
            remoteConfigRequiresUserContext = true,
        )
        val manager = createManager(backendClient = mockClient)

        val configs = manager.getRemoteConfigs(appUserId = "user_android_123")

        assertEquals("premium", configs["audience"]?.stringValue)
        coVerify(exactly = 1) { mockClient.getRemoteConfigs(null, "1.0.0", "TR", any()) }
        coVerify(exactly = 1) { mockClient.getRemoteConfigs("user_android_123", "1.0.0", "TR", any()) }
    }

    @Test
    fun `remote config missing user-context header refetches with app user id`() = runBlocking {
        val mockClient = mockk<AppActorBackendClient>(relaxed = true)
        coEvery { mockClient.getRemoteConfigs(null, any(), any(), any()) } returns AppActorBackendHttpResponse(
            body = sampleEnvelope("audience" to JsonPrimitive("public")),
            statusCode = 200,
            requestId = "req_public",
            eTag = "\"etag_public\"",
            signatureVerified = true,
            remoteConfigRequiresUserContext = null,
        )
        coEvery { mockClient.getRemoteConfigs("user_android_123", any(), any(), any()) } returns AppActorBackendHttpResponse(
            body = sampleEnvelope("audience" to JsonPrimitive("premium")),
            statusCode = 200,
            requestId = "req_user",
            eTag = "\"etag_user\"",
            signatureVerified = true,
            remoteConfigRequiresUserContext = true,
        )
        val manager = createManager(backendClient = mockClient)

        val configs = manager.getRemoteConfigs(appUserId = "user_android_123")

        assertEquals("premium", configs["audience"]?.stringValue)
        coVerify(exactly = 1) { mockClient.getRemoteConfigs(null, "1.0.0", "TR", any()) }
        coVerify(exactly = 1) { mockClient.getRemoteConfigs("user_android_123", "1.0.0", "TR", any()) }
    }

    @Test
    fun `remote config headerless fresh public cache does not satisfy user context`() = runBlocking {
        val mockClient = mockk<AppActorBackendClient>(relaxed = true)
        coEvery { mockClient.getRemoteConfigs(null, any(), any(), any()) } returns AppActorBackendHttpResponse(
            body = sampleEnvelope("audience" to JsonPrimitive("public")),
            statusCode = 200,
            requestId = "req_public",
            eTag = "\"etag_public\"",
            signatureVerified = true,
            remoteConfigRequiresUserContext = null,
        )
        coEvery { mockClient.getRemoteConfigs("user_android_123", any(), any(), any()) } returns AppActorBackendHttpResponse(
            body = sampleEnvelope("audience" to JsonPrimitive("premium")),
            statusCode = 200,
            requestId = "req_user",
            eTag = "\"etag_user\"",
            signatureVerified = true,
            remoteConfigRequiresUserContext = true,
        )
        val manager = createManager(backendClient = mockClient)

        manager.getRemoteConfigs(appUserId = "")
        val configs = manager.getRemoteConfigs(appUserId = "user_android_123")

        assertEquals("premium", configs["audience"]?.stringValue)
        coVerify(exactly = 1) { mockClient.getRemoteConfigs(null, "1.0.0", "TR", any()) }
        coVerify(exactly = 1) { mockClient.getRemoteConfigs("user_android_123", "1.0.0", "TR", any()) }
    }

    @Test
    fun `remote config concurrent public probe awaiters all refetch with user when required`() = runBlocking {
        val userStarted = CompletableDeferred<Unit>()
        val releaseUser = CompletableDeferred<Unit>()
        val mockClient = mockk<AppActorBackendClient>(relaxed = true)
        coEvery { mockClient.getRemoteConfigs(null, any(), any(), any()) } coAnswers {
            AppActorBackendHttpResponse(
                body = sampleEnvelope("audience" to JsonPrimitive("public")),
                statusCode = 200,
                requestId = "req_public",
                eTag = "\"etag_public\"",
                signatureVerified = true,
                remoteConfigRequiresUserContext = true,
            )
        }
        coEvery { mockClient.getRemoteConfigs("user_android_123", any(), any(), any()) } coAnswers {
            userStarted.complete(Unit)
            releaseUser.await()
            AppActorBackendHttpResponse(
                body = sampleEnvelope("audience" to JsonPrimitive("premium")),
                statusCode = 200,
                requestId = "req_user",
                eTag = "\"etag_user\"",
                signatureVerified = true,
                remoteConfigRequiresUserContext = true,
            )
        }
        val manager = createManager(backendClient = mockClient)

        val first = async { manager.getRemoteConfigs(appUserId = "user_android_123") }
        val second = async { manager.getRemoteConfigs(appUserId = "user_android_123") }
        userStarted.await()
        releaseUser.complete(Unit)

        assertEquals("premium", first.await()["audience"]?.stringValue)
        assertEquals("premium", second.await()["audience"]?.stringValue)
        coVerify(exactly = 1) { mockClient.getRemoteConfigs(null, "1.0.0", "TR", any()) }
        coVerify(exactly = 1) { mockClient.getRemoteConfigs("user_android_123", "1.0.0", "TR", any()) }
    }

    @Test
    fun `remote config remembered user mode skips public probe`() = runBlocking {
        val mockClient = mockk<AppActorBackendClient>(relaxed = true)
        coEvery { mockClient.getRemoteConfigs(null, any(), any(), any()) } returns AppActorBackendHttpResponse(
            body = sampleEnvelope("audience" to JsonPrimitive("public")),
            statusCode = 200,
            requestId = "req_public",
            eTag = "\"etag_public\"",
            signatureVerified = true,
            remoteConfigRequiresUserContext = true,
        )
        coEvery { mockClient.getRemoteConfigs("user_android_123", any(), any(), any()) } returns AppActorBackendHttpResponse(
            body = sampleEnvelope("audience" to JsonPrimitive("premium")),
            statusCode = 200,
            requestId = "req_user",
            eTag = "\"etag_user\"",
            signatureVerified = true,
            remoteConfigRequiresUserContext = true,
        )
        val manager = createManager(backendClient = mockClient)

        manager.getRemoteConfigs(appUserId = "user_android_123")
        manager.clearCache("user_android_123")
        val configs = manager.getRemoteConfigs(appUserId = "user_android_123")

        assertEquals("premium", configs["audience"]?.stringValue)
        coVerify(exactly = 1) { mockClient.getRemoteConfigs(null, "1.0.0", "TR", any()) }
        coVerify(exactly = 2) { mockClient.getRemoteConfigs("user_android_123", "1.0.0", "TR", any()) }
    }

    @Test
    fun `remote config unknown public disk fallback prefers user cache when available`() = runBlocking {
        val cacheStore = createCacheStore("remote-config-public-unknown-user-fallback")
        cacheStore.save(
            appUserId = null,
            appVersion = "1.0.0",
            country = "TR",
            payload = AppActorBackendJson.instance.encodeToString(sampleEnvelope("audience" to JsonPrimitive("public"))),
            eTag = "\"etag_public\"",
            verified = true,
        )
        cacheStore.save(
            appUserId = "user_android_123",
            appVersion = "1.0.0",
            country = "TR",
            payload = AppActorBackendJson.instance.encodeToString(sampleEnvelope("audience" to JsonPrimitive("premium"))),
            eTag = "\"etag_user\"",
            verified = true,
        )
        val mockClient = mockk<AppActorBackendClient>(relaxed = true)
        coEvery { mockClient.getRemoteConfigs(any(), any(), any(), any()) } throws IOException("offline")
        val manager = createManager(
            backendClient = mockClient,
            cacheStore = cacheStore,
        )

        val configs = manager.getRemoteConfigs(appUserId = "user_android_123")

        assertEquals("premium", configs["audience"]?.stringValue)
        coVerify(exactly = 1) { mockClient.getRemoteConfigs(null, "1.0.0", "TR", any()) }
    }

    @Test
    fun `remote config user refetch failure does not expose public probe cache`() = runBlocking {
        val mockClient = mockk<AppActorBackendClient>(relaxed = true)
        coEvery { mockClient.getRemoteConfigs(null, any(), any(), any()) } returns AppActorBackendHttpResponse(
            body = sampleEnvelope("audience" to JsonPrimitive("public")),
            statusCode = 200,
            requestId = "req_public",
            eTag = "\"etag_public\"",
            signatureVerified = true,
            remoteConfigRequiresUserContext = true,
        )
        coEvery { mockClient.getRemoteConfigs("user_android_123", any(), any(), any()) } throws IOException("offline")
        val manager = createManager(backendClient = mockClient)

        val failure = runCatching {
            manager.getRemoteConfigs(appUserId = "user_android_123")
        }.exceptionOrNull()

        assertTrue(failure != null)
        assertNull(manager.cached())
    }

    @Test
    fun `remote config user-required public probe is not reused after restart when user fetch fails`() = runBlocking {
        val cacheStore = createCacheStore("remote-config-user-required-public-probe-discard")
        val firstClient = mockk<AppActorBackendClient>(relaxed = true)
        coEvery { firstClient.getRemoteConfigs(null, any(), any(), any()) } returns AppActorBackendHttpResponse(
            body = sampleEnvelope("audience" to JsonPrimitive("public")),
            statusCode = 200,
            requestId = "req_public",
            eTag = "\"etag_public\"",
            signatureVerified = true,
            remoteConfigRequiresUserContext = true,
        )
        coEvery { firstClient.getRemoteConfigs("user_android_123", any(), any(), any()) } throws IOException("offline")
        val firstManager = createManager(
            backendClient = firstClient,
            cacheStore = cacheStore,
        )

        val firstFailure = runCatching {
            firstManager.getRemoteConfigs(appUserId = "user_android_123")
        }.exceptionOrNull()
        assertTrue(firstFailure != null)

        val secondClient = mockk<AppActorBackendClient>(relaxed = true)
        coEvery { secondClient.getRemoteConfigs(any(), any(), any(), any()) } throws IOException("offline")
        val restartedManager = createManager(
            backendClient = secondClient,
            cacheStore = cacheStore,
        )

        val restartedFailure = runCatching {
            restartedManager.getRemoteConfigs(appUserId = "user_android_123")
        }.exceptionOrNull()

        assertTrue(restartedFailure != null)
        assertNull(restartedManager.cached())
    }

    @Test
    fun `remote config legacy user cache is still readable after context key migration`() = runBlocking {
        val eTagManager = createETagManager("remote-config-legacy-migration")
        eTagManager.storeFresh(
            resource = AppActorCacheResource.LegacyRemoteConfigs("user_android_123"),
            payload = AppActorBackendJson.instance.encodeToString(sampleEnvelope("audience" to JsonPrimitive("legacy"))),
            eTag = "\"etag_legacy\"",
            verified = true,
        )
        val cacheStore = AppActorRemoteConfigsCacheStore(eTagManager)

        val cached = cacheStore.load(
            appUserId = "user_android_123",
            appVersion = "1.0.0",
            country = "TR",
        )

        assertEquals("\"etag_legacy\"", cacheStore.eTag("user_android_123", "1.0.0", "TR"))
        assertTrue(cached?.payload?.contains("legacy") == true)
    }

    @Test
    fun `remote config selective clear does not cancel unrelated user context in flight`() = runBlocking {
        val publicCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val userStarted = CompletableDeferred<Unit>()
        val releaseUser = CompletableDeferred<Unit>()
        val mockClient = mockk<AppActorBackendClient>(relaxed = true)
        coEvery { mockClient.getRemoteConfigs(null, any(), any(), any()) } coAnswers {
            publicCalls.incrementAndGet()
            AppActorBackendHttpResponse(
                body = sampleEnvelope("audience" to JsonPrimitive("public")),
                statusCode = 200,
                requestId = "req_public",
                eTag = "\"etag_public\"",
                signatureVerified = true,
                remoteConfigRequiresUserContext = true,
            )
        }
        coEvery { mockClient.getRemoteConfigs("user_android_B", any(), any(), any()) } coAnswers {
            userStarted.complete(Unit)
            releaseUser.await()
            AppActorBackendHttpResponse(
                body = sampleEnvelope("audience" to JsonPrimitive("user_b")),
                statusCode = 200,
                requestId = "req_user_b",
                eTag = "\"etag_user_b\"",
                signatureVerified = true,
                remoteConfigRequiresUserContext = true,
            )
        }
        val manager = createManager(backendClient = mockClient)

        val fetch = async { manager.getRemoteConfigs(appUserId = "user_android_B") }
        userStarted.await()
        manager.clearCache("user_android_A")
        releaseUser.complete(Unit)

        assertEquals("user_b", fetch.await()["audience"]?.stringValue)
        assertEquals(1, publicCalls.get())
    }

    @Test
    fun `remote config full clear resets remembered user mode`() = runBlocking {
        val mockClient = mockk<AppActorBackendClient>(relaxed = true)
        coEvery { mockClient.getRemoteConfigs(null, any(), any(), any()) } returns AppActorBackendHttpResponse(
            body = sampleEnvelope("audience" to JsonPrimitive("public")),
            statusCode = 200,
            requestId = "req_public",
            eTag = "\"etag_public\"",
            signatureVerified = true,
            remoteConfigRequiresUserContext = true,
        )
        coEvery { mockClient.getRemoteConfigs("user_android_123", any(), any(), any()) } returns AppActorBackendHttpResponse(
            body = sampleEnvelope("audience" to JsonPrimitive("premium")),
            statusCode = 200,
            requestId = "req_user",
            eTag = "\"etag_user\"",
            signatureVerified = true,
            remoteConfigRequiresUserContext = true,
        )
        val manager = createManager(backendClient = mockClient)

        manager.getRemoteConfigs(appUserId = "user_android_123")
        manager.clearCache()
        manager.getRemoteConfigs(appUserId = "user_android_123")

        coVerify(exactly = 2) { mockClient.getRemoteConfigs(null, "1.0.0", "TR", any()) }
        coVerify(exactly = 2) { mockClient.getRemoteConfigs("user_android_123", "1.0.0", "TR", any()) }
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
        return AppActorRemoteConfigsCacheStore(createETagManager(name))
    }

    private fun createETagManager(name: String): AppActorETagManager {
        return AppActorETagManager(
            diskStore = AppActorCacheDiskStore(
                context,
                File(context.cacheDir, "tests/$name")
            ),
            responseVerificationEnabled = false,
        )
    }

    private fun sampleEnvelope(vararg overrides: Pair<String, JsonPrimitive>): AppActorRemoteConfigsEnvelopeDTO {
        if (overrides.isNotEmpty()) {
            return AppActorRemoteConfigsEnvelopeDTO(
                data = overrides.map { (key, value) ->
                    AppActorRemoteConfigItemDTO(
                        key = key,
                        value = value,
                        valueType = if (value.isString) "string" else "boolean",
                    )
                },
                requestId = "req_remote_001",
            )
        }
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
