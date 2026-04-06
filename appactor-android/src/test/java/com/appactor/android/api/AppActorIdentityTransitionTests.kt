package com.appactor.android.api

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class AppActorIdentityTransitionTests {

    @Before
    fun setUp() {
        resetApiTestState()
    }

    private fun identifyAppUserId(request: okhttp3.mockwebserver.RecordedRequest, default: String): String {
        return try {
            val body = request.body.readUtf8()
            com.appactor.android.backend.client.AppActorBackendJson.instance
                .decodeFromString<com.appactor.android.backend.dto.AppActorIdentifyRequestDTO>(body)
                .appUserId ?: default
        } catch (_: Exception) { default }
    }

    @Test
    fun `stale remote config fetch retries after logout transition`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val firstRemoteConfigSeen = CountDownLatch(1)
        val releaseFirstRemoteConfig = CountDownLatch(1)
        val remoteConfigCalls = AtomicInteger(0)
        AppActor.storeAdapterFactory = { FakeStoreAdapter() }

        TestBackendServer { request ->
            val path = request.path?.substringBefore("?") ?: ""
            when (path) {
                "/v1/payment/identify" -> {
                    val userId = identifyAppUserId(request, "user_a")
                    jsonResponse(customerEnvelope(requestId = "req_identify", appUserId = userId))
                }
                "/v1/payment/offerings" -> jsonResponse("""{"requestId":"req_off","data":{"offerings":[],"productEntitlements":{}}}""")
                "/v1/remote-config" -> {
                    val callNumber = remoteConfigCalls.incrementAndGet()
                    val appUserId = request.requestUrl?.queryParameter("app_user_id")
                    if (callNumber == 1) {
                        assertEquals("user_a", appUserId)
                        firstRemoteConfigSeen.countDown()
                        assertTrue(releaseFirstRemoteConfig.await(5, TimeUnit.SECONDS))
                        jsonResponse(
                            remoteConfigEnvelope(
                                requestId = "req_remote_stale",
                                key = "audience",
                                value = "user_a",
                            ),
                        )
                    } else {
                        assertTrue(appUserId?.startsWith("appactor-anon-") == true)
                        jsonResponse(
                            remoteConfigEnvelope(
                                requestId = "req_remote_fresh",
                                key = "audience",
                                value = "anonymous",
                            ),
                        )
                    }
                }

                "/v1/payment/logout" -> jsonResponse(
                    """
                        {
                          "requestId": "req_logout",
                          "success": true
                        }
                    """.trimIndent(),
                )

                else -> {
                    if (path.startsWith("/v1/customers/")) {
                        val userId = path.removePrefix("/v1/customers/")
                        jsonResponse(customerEnvelope(requestId = "req_customer", appUserId = userId))
                    } else {
                        jsonResponse("{}", 404)
                    }
                }
            }
        }.use { backend ->
            AppActor.configure(
                com.appactor.android.models.AppActorConfiguration(
                    context = context,
                    apiKey = "pk_test_123",
                    appUserId = "user_a",
                    baseUrl = backend.baseUrl,
                    options = testOptionsForLocalBackend(),
                )
            )

            val remoteConfigsDeferred = async(Dispatchers.Default) {
                AppActor.getRemoteConfigs()
            }
            assertTrue(firstRemoteConfigSeen.await(5, TimeUnit.SECONDS))

            val logoutAck = async(Dispatchers.Default) { AppActor.logOut() }
            assertTrue(logoutAck.await())

            releaseFirstRemoteConfig.countDown()
            val configs = remoteConfigsDeferred.await()

            assertTrue("Expected at least 2 remote config calls", remoteConfigCalls.get() >= 2)
            assertEquals("anonymous", configs["audience"]?.stringValue)
            assertTrue(AppActor.appUserId?.startsWith("appactor-anon-") == true)
        }
    }

    @Test
    fun `ignored repeated configure does not invalidate in flight remote config fetch`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val firstRemoteConfigSeen = CountDownLatch(1)
        val releaseRemoteConfig = CountDownLatch(1)
        val remoteConfigCalls = AtomicInteger(0)

        TestBackendServer { request ->
            when (request.path?.substringBefore("?")) {
                "/v1/remote-config" -> {
                    remoteConfigCalls.incrementAndGet()
                    assertEquals("user_a", request.requestUrl?.queryParameter("app_user_id"))
                    firstRemoteConfigSeen.countDown()
                    assertTrue(releaseRemoteConfig.await(5, TimeUnit.SECONDS))
                    jsonResponse(
                        remoteConfigEnvelope(
                            requestId = "req_remote_single_pass",
                            key = "audience",
                            value = "user_a",
                        ),
                    )
                }

                "/v1/payment/identify" -> jsonResponse(
                    customerEnvelope(
                        requestId = "req_identify_configure_repeat",
                        appUserId = "user_a",
                    ),
                )

                else -> jsonResponse("{}", 404)
            }
        }.use { backend ->
            AppActor.configure(
                com.appactor.android.models.AppActorConfiguration(
                    context = context,
                    apiKey = "pk_test_123",
                    appUserId = "user_a",
                    baseUrl = backend.baseUrl,
                    options = testOptionsForLocalBackend(),
                )
            )

            val remoteConfigsDeferred = async(Dispatchers.Default) {
                AppActor.getRemoteConfigs()
            }
            assertTrue(firstRemoteConfigSeen.await(5, TimeUnit.SECONDS))

            AppActor.configure(
                com.appactor.android.models.AppActorConfiguration(
                    context = context,
                    apiKey = "pk_test_456",
                    appUserId = "user_b",
                    baseUrl = backend.baseUrl,
                    options = testOptionsForLocalBackend(),
                )
            )

            releaseRemoteConfig.countDown()
            val configs = remoteConfigsDeferred.await()

            assertEquals(1, remoteConfigCalls.get())
            assertEquals("user_a", configs["audience"]?.stringValue)
            assertEquals("user_a", AppActor.appUserId)
        }
    }

    @Test
    fun `stale customer fetch retries after login transition without republishing old user`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val firstCustomerSeen = CountDownLatch(1)
        val releaseFirstCustomer = CountDownLatch(1)
        val customerCalls = AtomicInteger(0)
        val publishedAppUserIds = Collections.synchronizedList(mutableListOf<String>())
        AppActor.storeAdapterFactory = { FakeStoreAdapter() }

        TestBackendServer { request ->
            val path = request.path?.substringBefore("?") ?: ""
            when (path) {
                "/v1/payment/identify" -> {
                    val userId = identifyAppUserId(request, "user_a")
                    jsonResponse(customerEnvelope(requestId = "req_identify", appUserId = userId))
                }
                "/v1/payment/offerings" -> jsonResponse("""{"requestId":"req_off","data":{"offerings":[],"productEntitlements":{}}}""")
                "/v1/customers/user_a" -> {
                    val callNum = customerCalls.incrementAndGet()
                    if (callNum == 1) {
                        // Bootstrap customer refresh — let through immediately
                        jsonResponse(customerEnvelope(requestId = "req_customer_bootstrap", appUserId = "user_a"))
                    } else {
                        // Explicit getCustomerInfo — block until released
                        firstCustomerSeen.countDown()
                        assertTrue(releaseFirstCustomer.await(5, TimeUnit.SECONDS))
                        jsonResponse(
                            customerEnvelope(
                                requestId = "req_customer_user_a",
                                appUserId = "user_a",
                            ),
                        )
                    }
                }

                "/v1/customers/user_b" -> {
                    customerCalls.incrementAndGet()
                    jsonResponse(
                        customerEnvelope(
                            requestId = "req_customer_user_b",
                            appUserId = "user_b",
                        ),
                    )
                }

                "/v1/payment/login" -> jsonResponse(
                    loginEnvelope(
                        requestId = "req_login_user_b",
                        appUserId = "user_b",
                    ),
                )

                else -> jsonResponse("{}", 404)
            }
        }.use { backend ->
            AppActor.configure(
                com.appactor.android.models.AppActorConfiguration(
                    context = context,
                    apiKey = "pk_test_123",
                    appUserId = "user_a",
                    baseUrl = backend.baseUrl,
                    options = testOptionsForLocalBackend(),
                )
            )
            AppActor.onCustomerInfoChanged = { info ->
                info.appUserId?.let(publishedAppUserIds::add)
            }

            val customerDeferred = async(Dispatchers.Default) {
                AppActor.getCustomerInfo(forceRefresh = true)
            }
            assertTrue(firstCustomerSeen.await(5, TimeUnit.SECONDS))

            val loggedIn = async(Dispatchers.Default) { AppActor.logIn("user_b") }.await()
            assertEquals("user_b", loggedIn.appUserId)

            releaseFirstCustomer.countDown()
            val info = customerDeferred.await()

            assertTrue("Expected at least 3 customer calls (bootstrap + stale + retry)", customerCalls.get() >= 3)
            assertEquals("user_b", info.appUserId)
            assertEquals("user_b", AppActor.appUserId)
            assertEquals("req_customer_user_b", info.requestId)
            assertFalse(publishedAppUserIds.contains("user_a"))
            assertTrue(publishedAppUserIds.all { it == "user_b" })
        }
    }

    @Test
    fun `customer callback can re enter sdk during login without deadlocking`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val callbackObserved = CountDownLatch(1)
        val callbackRemoteConfigValue = Collections.synchronizedList(mutableListOf<String>())
        AppActor.storeAdapterFactory = { FakeStoreAdapter() }

        TestBackendServer { request ->
            val path = request.path?.substringBefore("?") ?: ""
            when (path) {
                "/v1/payment/identify" -> {
                    val userId = identifyAppUserId(request, "user_a")
                    jsonResponse(customerEnvelope(requestId = "req_identify", appUserId = userId))
                }
                "/v1/payment/offerings" -> jsonResponse("""{"requestId":"req_off","data":{"offerings":[],"productEntitlements":{}}}""")
                "/v1/payment/login" -> jsonResponse(
                    loginEnvelope(
                        requestId = "req_login_callback_user_b",
                        appUserId = "user_b",
                    ),
                )

                "/v1/remote-config" -> {
                    assertEquals("user_b", request.requestUrl?.queryParameter("app_user_id"))
                    jsonResponse(
                        remoteConfigEnvelope(
                            requestId = "req_remote_callback_user_b",
                            key = "audience",
                            value = "user_b",
                        ),
                    )
                }

                else -> {
                    if (path.startsWith("/v1/customers/")) {
                        val userId = path.removePrefix("/v1/customers/")
                        jsonResponse(customerEnvelope(requestId = "req_customer", appUserId = userId))
                    } else {
                        jsonResponse("{}", 404)
                    }
                }
            }
        }.use { backend ->
            AppActor.configure(
                com.appactor.android.models.AppActorConfiguration(
                    context = context,
                    apiKey = "pk_test_123",
                    appUserId = "user_a",
                    baseUrl = backend.baseUrl,
                    options = testOptionsForLocalBackend(),
                )
            )

            AppActor.onCustomerInfoChanged = { info ->
                if (info.appUserId == "user_b") {
                    runBlocking {
                        callbackRemoteConfigValue += AppActor.getRemoteConfigs()["audience"]?.stringValue.orEmpty()
                    }
                    callbackObserved.countDown()
                }
            }

            val info = withTimeout(5_000L) { AppActor.logIn("user_b") }

            assertEquals("user_b", info.appUserId)
            assertTrue(callbackObserved.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("user_b"), callbackRemoteConfigValue)
        }
    }
}
