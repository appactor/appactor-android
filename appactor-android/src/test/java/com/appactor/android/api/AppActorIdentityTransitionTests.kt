package com.appactor.android.api

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.appactor.android.billing.AppActorStorePurchase
import com.appactor.android.billing.AppActorStorePurchaseState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        val logoutCalls = AtomicInteger(0)
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
                    remoteConfigCalls.incrementAndGet()
                    val appUserId = request.requestUrl?.queryParameter("app_user_id")
                    if (appUserId == null) {
                        jsonResponse(
                            remoteConfigEnvelope(
                                requestId = "req_remote_public_probe",
                                key = "audience",
                                value = "public",
                            ),
                        ).addHeader("X-AppActor-Remote-Config-Requires-User-Context", "true")
                    } else if (appUserId == "user_a") {
                        assertEquals("user_a", appUserId)
                        firstRemoteConfigSeen.countDown()
                        assertTrue(releaseFirstRemoteConfig.await(5, TimeUnit.SECONDS))
                        jsonResponse(
                            remoteConfigEnvelope(
                                requestId = "req_remote_stale",
                                key = "audience",
                                value = "user_a",
                            ),
                        ).addHeader("X-AppActor-Remote-Config-Requires-User-Context", "true")
                    } else {
                        assertTrue(appUserId?.startsWith("appactor-anon-") == true)
                        jsonResponse(
                            remoteConfigEnvelope(
                                requestId = "req_remote_fresh",
                                key = "audience",
                                value = "anonymous",
                            ),
                        ).addHeader("X-AppActor-Remote-Config-Requires-User-Context", "true")
                    }
                }

                "/v1/payment/logout" -> {
                    logoutCalls.incrementAndGet()
                    jsonResponse("""{"requestId":"req_logout","success":true}""")
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

            val remoteConfigsDeferred = async(Dispatchers.Default) {
                AppActor.getRemoteConfigs()
            }
            assertTrue(firstRemoteConfigSeen.await(5, TimeUnit.SECONDS))

            val logoutAck = async(Dispatchers.Default) { AppActor.logOut() }
            assertTrue(logoutAck.await())

            releaseFirstRemoteConfig.countDown()
            val configs = remoteConfigsDeferred.await()

            assertEquals(0, logoutCalls.get())
            assertTrue("Expected at least 2 remote config calls", remoteConfigCalls.get() >= 2)
            assertEquals("anonymous", configs["audience"]?.stringValue)
            assertTrue(AppActor.appUserId?.startsWith("appactor-anon-") == true)
        }
    }

    @Test
    fun `logout clears legacy server user id state`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("appactor_identity", Context.MODE_PRIVATE)
        AppActor.storeAdapterFactory = { FakeStoreAdapter() }

        stubBackend().use { backend ->
            AppActor.configure(
                com.appactor.android.models.AppActorConfiguration(
                    context = context,
                    apiKey = "pk_test_123",
                    appUserId = "user_a",
                    baseUrl = backend.baseUrl,
                    options = testOptionsForLocalBackend(),
                )
            )

            preferences.edit()
                .putString("appactor_billing_server_user_id", "legacy_server_user_123")
                .commit()

            assertTrue(AppActor.logOut())
            assertNull(preferences.getString("appactor_billing_server_user_id", null))
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
                    val appUserId = request.requestUrl?.queryParameter("app_user_id")
                    if (appUserId == null) {
                        return@TestBackendServer jsonResponse(
                            remoteConfigEnvelope(
                                requestId = "req_remote_public_probe",
                                key = "audience",
                                value = "public",
                            ),
                        ).addHeader("X-AppActor-Remote-Config-Requires-User-Context", "true")
                    }
                    assertEquals("user_a", appUserId)
                    firstRemoteConfigSeen.countDown()
                    assertTrue(releaseRemoteConfig.await(5, TimeUnit.SECONDS))
                    jsonResponse(
                        remoteConfigEnvelope(
                            requestId = "req_remote_single_pass",
                            key = "audience",
                            value = "user_a",
                        ),
                    ).addHeader("X-AppActor-Remote-Config-Requires-User-Context", "true")
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

            assertEquals(2, remoteConfigCalls.get())
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
                    val appUserId = request.requestUrl?.queryParameter("app_user_id")
                    if (appUserId == null) {
                        return@TestBackendServer jsonResponse(
                            remoteConfigEnvelope(
                                requestId = "req_remote_callback_public_probe",
                                key = "audience",
                                value = "public",
                            ),
                        ).addHeader("X-AppActor-Remote-Config-Requires-User-Context", "true")
                    }
                    assertEquals("user_b", appUserId)
                    jsonResponse(
                        remoteConfigEnvelope(
                            requestId = "req_remote_callback_user_b",
                            key = "audience",
                            value = "user_b",
                        ),
                    ).addHeader("X-AppActor-Remote-Config-Requires-User-Context", "true")
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

    @Test
    fun `same user login still uses backend login path`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val loginCalls = AtomicInteger(0)
        val identifyCalls = AtomicInteger(0)
        AppActor.storeAdapterFactory = { FakeStoreAdapter() }

        TestBackendServer { request ->
            val path = request.path?.substringBefore("?") ?: ""
            when (path) {
                "/v1/payment/identify" -> {
                    identifyCalls.incrementAndGet()
                    val userId = identifyAppUserId(request, "user_a")
                    jsonResponse(customerEnvelope(requestId = "req_identify_same_user", appUserId = userId))
                }
                "/v1/payment/offerings" -> jsonResponse("""{"requestId":"req_off","data":{"offerings":[],"productEntitlements":{}}}""")
                "/v1/customers/user_a" -> jsonResponse(
                    customerEnvelope(
                        requestId = "req_customer_same_user",
                        appUserId = "user_a",
                    ),
                )
                "/v1/payment/login" -> {
                    loginCalls.incrementAndGet()
                    jsonResponse(
                        loginEnvelope(
                            requestId = "req_login_same_user",
                            appUserId = "user_a",
                        ),
                    )
                }

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

            val info = withTimeout(5_000L) { AppActor.logIn("user_a") }

            assertEquals("user_a", info.appUserId)
            assertEquals("user_a", AppActor.appUserId)
            assertEquals(1, loginCalls.get())
            assertEquals(0, identifyCalls.get())
        }
    }

    @Test
    fun `same user login publishes buffered purchase update for current identity`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val purchaseUpdates = MutableSharedFlow<List<AppActorStorePurchase>>(extraBufferCapacity = 1)
        val loginStarted = CountDownLatch(1)
        val releaseLogin = CountDownLatch(1)
        val receiptCalls = AtomicInteger(0)
        val deferredResolved = CountDownLatch(1)
        val receiptPublished = CountDownLatch(1)
        val deferredProducts = Collections.synchronizedList(mutableListOf<String>())
        val publishedRequestIds = Collections.synchronizedList(mutableListOf<String>())
        AppActor.storeAdapterFactory = {
            FakeStoreAdapter(purchaseUpdatesFlow = purchaseUpdates)
        }
        context.getSharedPreferences("com.appactor.android.pending_purchases", Context.MODE_PRIVATE)
            .edit()
            .putString("token_same_user_appactor_deferred_123", "com.appactor.pro.monthly|${System.currentTimeMillis()}")
            .commit()

        TestBackendServer { request ->
            val path = request.path?.substringBefore("?") ?: ""
            when (path) {
                "/v1/payment/identify" -> {
                    val userId = identifyAppUserId(request, "user_a")
                    jsonResponse(customerEnvelope(requestId = "req_identify_same_user_publish", appUserId = userId))
                }
                "/v1/payment/offerings" -> jsonResponse("""{"requestId":"req_off","data":{"offerings":[],"productEntitlements":{}}}""")
                "/v1/customers/user_a" -> jsonResponse(
                    customerEnvelope(
                        requestId = "req_customer_same_user_publish",
                        appUserId = "user_a",
                    ),
                )
                "/v1/payment/login" -> {
                    loginStarted.countDown()
                    assertTrue(releaseLogin.await(5, TimeUnit.SECONDS))
                    jsonResponse(
                        loginEnvelope(
                            requestId = "req_login_same_user_publish",
                            appUserId = "user_a",
                        ),
                    )
                }
                "/v1/payment/receipts/google" -> {
                    receiptCalls.incrementAndGet()
                    jsonResponse(googleReceiptEnvelope(requestId = "req_receipt_same_user_publish", appUserId = "user_a"))
                }

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
            AppActor.onDeferredPurchaseResolved = { productId, customerInfo ->
                deferredProducts += productId
                if (customerInfo.requestId == "req_receipt_same_user_publish") {
                    deferredResolved.countDown()
                }
            }
            AppActor.onCustomerInfoChanged = { info ->
                info.requestId?.let(publishedRequestIds::add)
                if (info.requestId == "req_receipt_same_user_publish") {
                    receiptPublished.countDown()
                }
            }

            val login = async(Dispatchers.Default) { AppActor.logIn("user_a") }
            assertTrue(loginStarted.await(5, TimeUnit.SECONDS))
            purchaseUpdates.emit(
                listOf(
                    AppActorStorePurchase(
                        productId = "com.appactor.pro.monthly",
                        productType = com.appactor.android.models.AppActorProductType.Subscription,
                        purchaseToken = "token_same_user_appactor_deferred_123",
                        orderId = "GPA.same.user.appactor.1234",
                        purchaseTimeMillis = 1_710_000_000_000,
                        purchaseState = AppActorStorePurchaseState.Purchased,
                        basePlanId = "monthly001",
                        offerId = "intro7d",
                        isAcknowledged = false,
                        isAutoRenewing = true,
                        rawPurchaseData = "{\"purchaseToken\":\"token_same_user_appactor_deferred_123\"}",
                        purchaseSignature = "signature_same_user_appactor_deferred_123",
                    )
                )
            )
            releaseLogin.countDown()

            val info = withTimeout(5_000L) { login.await() }

            assertEquals("user_a", info.appUserId)
            assertEquals("user_a", AppActor.appUserId)
            assertTrue(awaitMainThreadCallback(deferredResolved))
            assertTrue(awaitMainThreadCallback(receiptPublished))
            assertEquals(1, receiptCalls.get())
            assertEquals(listOf("com.appactor.pro.monthly"), deferredProducts)
            assertTrue(publishedRequestIds.contains("req_receipt_same_user_publish"))
        }
    }
}
