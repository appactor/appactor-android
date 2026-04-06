package com.appactor.android.managers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.appactor.android.backend.client.AppActorBackendClient
import com.appactor.android.backend.client.AppActorBackendHttpResponse
import com.appactor.android.backend.dto.AppActorExperimentAssignmentEnvelopeDTO
import com.appactor.android.backend.dto.AppActorExperimentAssignmentResponseDTO
import com.appactor.android.backend.dto.AppActorExperimentDTO
import com.appactor.android.backend.dto.AppActorExperimentVariantDTO
import com.appactor.android.cache.AppActorCacheDiskStore
import com.appactor.android.cache.AppActorETagManager
import com.appactor.android.cache.AppActorExperimentCacheStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
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
class AppActorExperimentManagerTests {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun mockClient(
        response: AppActorBackendHttpResponse<AppActorExperimentAssignmentEnvelopeDTO>? = null,
        throwable: Throwable? = null,
    ): AppActorBackendClient {
        val mock = mockk<AppActorBackendClient>(relaxed = true)
        when {
            throwable != null -> coEvery { mock.postExperimentAssignment(any(), any(), any(), any()) } throws throwable
            response != null -> coEvery { mock.postExperimentAssignment(any(), any(), any(), any()) } returns response
        }
        return mock
    }

    @Test
    fun `experiment manager caches assignment per key`() = runBlocking {
        val mock = mockClient(response = successResponse())
        val manager = createManager(mock)

        val first = manager.getAssignment("paywall_copy", "user_android_123")
        val second = manager.getAssignment("paywall_copy", "user_android_123")

        assertEquals("variant_b", first?.variantKey)
        assertEquals("variant_b", second?.variantKey)
        coVerify(exactly = 1) { mock.postExperimentAssignment(any(), any(), any(), any()) }
    }

    @Test
    fun `experiment manager caches nil assignment`() = runBlocking {
        val mock = mockClient(
            response = AppActorBackendHttpResponse(
                body = AppActorExperimentAssignmentEnvelopeDTO(
                    data = AppActorExperimentAssignmentResponseDTO(
                        inExperiment = false,
                        reason = "not_targeted",
                    ),
                    requestId = "req_exp_nil",
                ),
                statusCode = 200,
                requestId = "req_exp_nil",
                signatureVerified = true,
            )
        )
        val manager = createManager(mock)

        val first = manager.getAssignment("paywall_copy", "user_android_123")
        val second = manager.getAssignment("paywall_copy", "user_android_123")

        assertNull(first)
        assertNull(second)
        coVerify(exactly = 1) { mock.postExperimentAssignment(any(), any(), any(), any()) }
    }

    @Test
    fun `experiment manager falls back to disk cache on network error`() = runBlocking {
        val cacheStore = createCacheStore("experiment-fallback")
        val seedMock = mockClient(response = successResponse())
        val seededManager = createManager(seedMock, cacheStore = cacheStore)
        seededManager.getAssignment("paywall_copy", "user_android_A")

        val failMock = mockClient(throwable = IOException("offline"))
        val manager = createManager(failMock, cacheStore = cacheStore)
        val assignment = manager.getAssignment("paywall_copy", "user_android_A")

        assertTrue(assignment != null)
        assertEquals("variant_b", assignment?.variantKey)
    }

    @Test
    fun `experiment clear cache cancels in flight request and prevents stale repopulation`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val unblock = CompletableDeferred<Unit>()
        val cacheStore = createCacheStore("experiment-clear-race")
        val mock = mockk<AppActorBackendClient>(relaxed = true)
        coEvery { mock.postExperimentAssignment(any(), any(), any(), any()) } coAnswers {
            started.complete(Unit)
            unblock.await()
            successResponse().let { AppActorBackendHttpResponse(
                body = it.body,
                statusCode = it.statusCode,
                requestId = "req_exp_race",
                signatureVerified = true,
            ) }
        }
        val manager = createManager(mock, cacheStore = cacheStore)

        val failure = async {
            runCatching { manager.getAssignment("paywall_copy", "user_android_123") }.exceptionOrNull()
        }

        started.await()
        manager.clearCache("user_android_123")
        unblock.complete(Unit)

        assertTrue(failure.await() is CancellationException)
        assertNull(manager.cached("paywall_copy"))
        assertNull(cacheStore.load("user_android_123"))
    }

    @Test
    fun `experiment fallback does not leak another user's disk cache`() = runBlocking {
        val cacheStore = createCacheStore("experiment-cross-user")
        val seedMock = mockClient(response = successResponse())
        val seededManager = createManager(seedMock, cacheStore = cacheStore)
        seededManager.getAssignment("paywall_copy", "user_android_A")

        val failMock = mockClient(throwable = IOException("offline"))
        val manager = createManager(failMock, cacheStore = cacheStore)
        val failure = runCatching {
            manager.getAssignment("paywall_copy", "user_android_B")
        }.exceptionOrNull()

        assertTrue(failure != null)
    }

    @Test
    fun `experiment concurrent callers share in flight request without deadlock`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val unblock = CompletableDeferred<Unit>()
        val mock = mockk<AppActorBackendClient>(relaxed = true)
        coEvery { mock.postExperimentAssignment(any(), any(), any(), any()) } coAnswers {
            started.complete(Unit)
            unblock.await()
            successResponse().let { AppActorBackendHttpResponse(
                body = it.body,
                statusCode = it.statusCode,
                requestId = "req_exp_single_flight",
                signatureVerified = true,
            ) }
        }
        val manager = createManager(mock)

        val first = async { manager.getAssignment("paywall_copy", "user_android_123") }
        started.await()
        val second = async { manager.getAssignment("paywall_copy", "user_android_123") }
        unblock.complete(Unit)

        withTimeout(2_000) {
            assertEquals("variant_b", first.await()?.variantKey)
            assertEquals("variant_b", second.await()?.variantKey)
        }
        coVerify(exactly = 1) { mock.postExperimentAssignment(any(), any(), any(), any()) }
    }

    private fun successResponse(): AppActorBackendHttpResponse<AppActorExperimentAssignmentEnvelopeDTO> {
        return AppActorBackendHttpResponse(
            body = sampleEnvelope(),
            statusCode = 200,
            requestId = "req_exp_001",
            signatureVerified = true,
        )
    }

    private fun createManager(
        backendClient: AppActorBackendClient,
        cacheStore: AppActorExperimentCacheStore = createCacheStore("experiment-${UUID.randomUUID()}"),
        dateProviderMillis: () -> Long = { System.currentTimeMillis() },
    ): AppActorExperimentManager {
        return AppActorExperimentManager(
            backendClient = backendClient,
            cacheStore = cacheStore,
            appVersionProvider = { "1.0.0" },
            countryProvider = { "TR" },
            dateProviderMillis = dateProviderMillis,
        )
    }

    private fun createCacheStore(name: String): AppActorExperimentCacheStore {
        return AppActorExperimentCacheStore(
            AppActorETagManager(
                diskStore = AppActorCacheDiskStore(
                    context,
                    File(context.cacheDir, "tests/$name")
                ),
                responseVerificationEnabled = false,
            )
        )
    }

    private fun sampleEnvelope(): AppActorExperimentAssignmentEnvelopeDTO {
        return AppActorExperimentAssignmentEnvelopeDTO(
            data = AppActorExperimentAssignmentResponseDTO(
                inExperiment = true,
                experiment = AppActorExperimentDTO(
                    id = "exp_001",
                    key = "paywall_copy",
                ),
                variant = AppActorExperimentVariantDTO(
                    id = "var_002",
                    key = "variant_b",
                    valueType = "string",
                    payload = JsonPrimitive("new_copy"),
                ),
                assignedAt = "2026-03-14T12:00:00Z",
            ),
            requestId = "req_exp_001",
        )
    }
}
