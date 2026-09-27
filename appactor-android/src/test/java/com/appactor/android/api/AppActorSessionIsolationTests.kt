package com.appactor.android.api

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.appactor.android.backend.client.AppActorBackendJson
import com.appactor.android.backend.dto.AppActorGoogleReceiptRequestDTO
import com.appactor.android.backend.dto.AppActorGoogleRestoreRequestDTO
import com.appactor.android.internal.runtime.runtimeTestPurchase
import com.appactor.android.models.AppActorBridgeError
import com.appactor.android.models.AppActorBridgeErrorCallback
import com.appactor.android.models.AppActorCompletionCallback
import com.appactor.android.models.AppActorConfiguration
import com.appactor.android.models.AppActorCustomerInfo
import com.appactor.android.storage.AppActorAtomicJsonReceiptQueueStore
import com.appactor.android.storage.AppActorReceiptQueuePhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Nothing of one user's session may leak into the next one's. */
@RunWith(RobolectricTestRunner::class)
class AppActorSessionIsolationTests {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        resetApiTestState()
        AppActor.storeAdapterFactory = { FakeStoreAdapter() }
    }

    @Test
    fun `bridge reset on a configured sdk wipes the identity and completes`() = runBlocking {
        stubBackend().use { backend ->
            AppActor.configure(configuration(backend, appUserId = "user_bridge_reset"))
        }
        val completed = CountDownLatch(1)
        val error = AtomicReference<AppActorBridgeError?>()

        AppActorBridge.reset(
            onComplete = AppActorCompletionCallback { completed.countDown() },
            onError = AppActorBridgeErrorCallback { bridgeError ->
                error.set(bridgeError)
                completed.countDown()
            },
        )

        assertTrue(awaitMainThreadCallback(completed))
        assertNull(error.get())
        assertNull(identityPreferences().getString("appactor_billing_app_user_id", null))
    }

    @Test
    fun `customer info flow taken before configure follows every session`() = runBlocking {
        val customerInfoFlow = AppActor.customerInfoFlow

        TestBackendServer(::customerBackend).use { backend ->
            AppActor.configure(configuration(backend, appUserId = "user_flow_a"))
            withTimeout(5_000L) { customerInfoFlow.first { it.appUserId == "user_flow_a" } }

            AppActor.reset()
            assertEquals(AppActorCustomerInfo.empty, customerInfoFlow.value)

            AppActor.configure(configuration(backend, appUserId = "user_flow_b"))
            withTimeout(5_000L) { customerInfoFlow.first { it.appUserId == "user_flow_b" } }
        }
        Unit
    }

    @Test
    fun `an attribute write in flight during logout is not sent again for the next user`() = runBlocking {
        val attributeRequests = CopyOnWriteArrayList<Pair<String, String>>()
        val emailPatchStarted = CountDownLatch(1)
        val releaseEmailPatch = CountDownLatch(1)

        TestBackendServer { request ->
            val path = request.path?.substringBefore("?").orEmpty()
            if (path.startsWith("/v1/payment/users/") && path.endsWith("/attributes")) {
                val body = request.body.readUtf8()
                attributeRequests += path to body
                if (path == "/v1/payment/users/user_attr_a/attributes" && body.contains(EMAIL)) {
                    emailPatchStarted.countDown()
                    releaseEmailPatch.await(5, TimeUnit.SECONDS)
                }
                jsonResponse("""{"requestId":"req_attributes"}""")
            } else {
                customerBackend(request)
            }
        }.use { backend ->
            AppActor.configure(configuration(backend, appUserId = "user_attr_a"))

            val write = async(Dispatchers.Default) { AppActor.setEmail(EMAIL) }
            assertTrue(emailPatchStarted.await(5, TimeUnit.SECONDS))
            val epochBeforeLogout = identityEpoch()
            val logout = async(Dispatchers.Default) { AppActor.logOut() }
            // Let logOut switch the identity while the write is still in flight.
            withTimeout(5_000L) {
                while (identityEpoch() == epochBeforeLogout) delay(10)
            }
            releaseEmailPatch.countDown()
            withTimeout(10_000L) {
                write.await()
                logout.await()
            }

            val nextAppUserId = AppActor.appUserId.orEmpty()
            assertTrue(nextAppUserId.startsWith("appactor-anon-"))
            assertTrue(attributeRequests.none { (path, body) -> nextAppUserId in path && body.contains(EMAIL) })
        }
    }

    @Test
    fun `a restore in flight during logout is not run again for the next user`() = runBlocking {
        // Acknowledged, so only the restore sends it, not the launch sweep.
        AppActor.storeAdapterFactory = {
            FakeStoreAdapter(activePurchases = listOf(runtimeTestPurchase(obfuscatedAccountId = null).copy(isAcknowledged = true)))
        }
        val restoredAppUserIds = CopyOnWriteArrayList<String>()
        val restoreStarted = CountDownLatch(1)
        val releaseRestore = CountDownLatch(1)

        TestBackendServer { request ->
            if (request.path?.substringBefore("?") == "/v1/payment/restore/google") {
                restoredAppUserIds += AppActorBackendJson.instance
                    .decodeFromString<AppActorGoogleRestoreRequestDTO>(request.body.readUtf8())
                    .appUserId
                restoreStarted.countDown()
                releaseRestore.await(5, TimeUnit.SECONDS)
                // The purchase is user_restore_a's, whoever restores it: the backend merges the
                // one asking into them.
                jsonResponse(googleRestoreEnvelope(requestId = "req_restore", appUserId = "user_restore_a"))
            } else {
                customerBackend(request)
            }
        }.use { backend ->
            AppActor.configure(configuration(backend, appUserId = "user_restore_a"))

            val restore = async(Dispatchers.Default) { AppActor.restorePurchases() }
            assertTrue(restoreStarted.await(5, TimeUnit.SECONDS))
            val epochBeforeLogout = identityEpoch()
            val logout = async(Dispatchers.Default) { AppActor.logOut() }
            // Let logOut start (it bumps the epoch, then waits for the restore) while the restore
            // is still in flight.
            withTimeout(5_000L) {
                while (identityEpoch() == epochBeforeLogout) delay(10)
            }
            releaseRestore.countDown()
            withTimeout(10_000L) {
                restore.await()
                logout.await()
            }

            assertEquals(listOf("user_restore_a"), restoredAppUserIds)
            assertTrue(AppActor.appUserId.orEmpty().startsWith("appactor-anon-"))
        }
    }

    @Test
    fun `a signed out user's queued receipt does not bring that user back at launch`() = runBlocking {
        AppActorAtomicJsonReceiptQueueStore(context).upsert(
            queueItem(phase = AppActorReceiptQueuePhase.DeadLettered).copy(appUserId = "user_signed_out")
        )
        val postedAppUserIds = CopyOnWriteArrayList<String>()

        TestBackendServer { request ->
            if (request.path?.substringBefore("?") == "/v1/payment/receipts/google") {
                postedAppUserIds += AppActorBackendJson.instance
                    .decodeFromString<AppActorGoogleReceiptRequestDTO>(request.body.readUtf8())
                    .appUserId
                jsonResponse(googleReceiptEnvelope(requestId = "req_receipt_signed_out", appUserId = "user_signed_out"))
            } else {
                customerBackend(request)
            }
        }.use { backend ->
            AppActor.configure(configuration(backend, appUserId = null))
        }

        assertEquals(listOf("user_signed_out"), postedAppUserIds)
        assertTrue(AppActor.appUserId.orEmpty().startsWith("appactor-anon-"))
        assertTrue(AppActor.customerInfo.appUserId != "user_signed_out")
    }

    private fun configuration(backend: TestBackendServer, appUserId: String?) = AppActorConfiguration(
        context = context,
        apiKey = "pk_test_123",
        appUserId = appUserId,
        baseUrl = backend.baseUrl,
        options = testOptionsForLocalBackend(),
    )

    private fun customerBackend(request: RecordedRequest): MockResponse {
        val path = request.path?.substringBefore("?").orEmpty()
        return when {
            path == "/v1/payment/offerings" ->
                jsonResponse("""{"requestId":"req_offerings","data":{"offerings":[],"productEntitlements":{}}}""")
            path.startsWith("/v1/customers/") -> {
                val appUserId = path.substringAfter("/v1/customers/")
                jsonResponse(customerEnvelope(requestId = "req_customer_$appUserId", appUserId = appUserId))
            }
            path.startsWith("/v1/payment/users/") -> jsonResponse("""{"requestId":"req_attributes"}""")
            else -> jsonResponse("{}", 404)
        }
    }

    private fun identityPreferences() = context.getSharedPreferences("appactor_identity", Context.MODE_PRIVATE)

    private fun identityEpoch(): Long = AppActor::class.java.getDeclaredField("identityEpoch")
        .apply { isAccessible = true }
        .getLong(AppActor)

    private companion object {
        const val EMAIL = "a@example.com"
    }
}
