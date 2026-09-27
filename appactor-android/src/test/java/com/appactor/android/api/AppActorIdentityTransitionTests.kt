package com.appactor.android.api

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.appactor.android.backend.client.AppActorBackendJson
import com.appactor.android.billing.AppActorStorePurchase
import com.appactor.android.billing.AppActorStorePurchaseState
import com.appactor.android.models.AppActorConfiguration
import com.appactor.android.pipeline.AppActorClientPurchaseContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import java.util.concurrent.CopyOnWriteArrayList
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
    fun `login refreshes automatic profile context for new identity`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val attributeRequests = CopyOnWriteArrayList<Pair<String, String>>()
        AppActor.storeAdapterFactory = { FakeStoreAdapter() }

        TestBackendServer { request ->
            val path = request.path?.substringBefore("?") ?: ""
            when {
                path == "/v1/payment/offerings" -> {
                    jsonResponse("""{"requestId":"req_off","data":{"offerings":[],"productEntitlements":{}}}""")
                }
                path == "/v1/payment/login" -> {
                    jsonResponse(loginEnvelope(requestId = "req_login_profile_context", appUserId = "user_b"))
                }
                path.startsWith("/v1/payment/users/") && path.endsWith("/attributes") -> {
                    attributeRequests += path to request.body.readUtf8()
                    jsonResponse("""{"requestId":"req_attributes"}""")
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

            val info = withTimeout(5_000L) { AppActor.logIn("user_b") }

            assertEquals("user_b", info.appUserId)
            withTimeout(5_000L) {
                while (attributeRequests.none { (path, body) ->
                        path == "/v1/payment/users/user_b/attributes" &&
                            body.contains("\"\$sdkVersion\"") &&
                            body.contains("\"\$platform\":\"android\"")
                    }) {
                    delay(25)
                }
            }
        }
    }

    @Test
    fun `logout refreshes automatic profile context for new anonymous identity`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val attributeRequests = CopyOnWriteArrayList<Pair<String, String>>()
        AppActor.storeAdapterFactory = { FakeStoreAdapter() }

        TestBackendServer { request ->
            val path = request.path?.substringBefore("?") ?: ""
            when {
                path == "/v1/payment/offerings" -> {
                    jsonResponse("""{"requestId":"req_off","data":{"offerings":[],"productEntitlements":{}}}""")
                }
                path.startsWith("/v1/payment/users/") && path.endsWith("/attributes") -> {
                    attributeRequests += path to request.body.readUtf8()
                    jsonResponse("""{"requestId":"req_attributes"}""")
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

            assertTrue(withTimeout(5_000L) { AppActor.logOut() })
            val newAppUserId = AppActor.appUserId.orEmpty()

            assertTrue(newAppUserId.startsWith("appactor-anon-"))
            withTimeout(5_000L) {
                while (attributeRequests.none { (path, body) ->
                        path == "/v1/payment/users/$newAppUserId/attributes" &&
                            body.contains("\"\$sdkVersion\"") &&
                            body.contains("\"\$platform\":\"android\"")
                    }) {
                    delay(25)
                }
            }
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
    fun `login is not blocked by an attribute write that keeps failing`() = runBlocking {
        val loginCalls = AtomicInteger(0)
        withUserAWhoseAttributeWritesFail(loginCalls) {
            val info = withTimeout(5_000L) { AppActor.logIn("user_b") }

            assertEquals("user_b", info.appUserId)
            assertEquals("user_b", AppActor.appUserId)
            assertEquals(1, loginCalls.get())
        }
    }

    @Test
    fun `logout is not blocked by an attribute write that keeps failing`() = runBlocking {
        withUserAWhoseAttributeWritesFail {
            assertTrue(withTimeout(5_000L) { AppActor.logOut() })

            assertTrue(AppActor.appUserId != "user_a")
        }
    }

    /**
     * Configures user_a, makes one attribute write the backend refuses with a 403 (not a rejected
     * payload, so it stays queued), then runs [transition]. Login answers as user_b.
     */
    private suspend fun withUserAWhoseAttributeWritesFail(
        loginCalls: AtomicInteger = AtomicInteger(0),
        transition: suspend () -> Unit,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AppActor.storeAdapterFactory = { FakeStoreAdapter() }

        TestBackendServer { request ->
            val path = request.path?.substringBefore("?") ?: ""
            when (path) {
                "/v1/payment/identify" -> {
                    val userId = identifyAppUserId(request, "user_a")
                    jsonResponse(customerEnvelope(requestId = "req_identify_failing_flush", appUserId = userId))
                }
                "/v1/payment/offerings" -> jsonResponse("""{"requestId":"req_off","data":{"offerings":[],"productEntitlements":{}}}""")
                "/v1/payment/users/user_a/attributes" -> jsonResponse(
                    """{"error":{"code":"FORBIDDEN","message":"Forbidden"}}""",
                    403,
                )
                "/v1/payment/login" -> {
                    loginCalls.incrementAndGet()
                    jsonResponse(loginEnvelope(requestId = "req_login_failing_flush", appUserId = "user_b"))
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
            val writeFailure = runCatching {
                AppActor.setAttribute("tier", com.appactor.android.models.AppActorAttributeValue.string("gold"))
            }.exceptionOrNull()
            assertTrue(writeFailure != null)

            transition()
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
                    // Info that differs from the login's in more than its requestId, so the
                    // listener, which already had the login's, is called again.
                    jsonResponse(
                        googleReceiptEnvelope(
                            requestId = "req_receipt_same_user_publish",
                            appUserId = "user_a",
                            managementUrl = "https://play.google.com/store/account/subscriptions",
                        )
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

    @Test
    fun `a pending purchase approved after an anonymous user logs in resolves for them`() = runBlocking {
        withPendingPurchaseOfAnonymousBuyer { purchaseUpdates, observed ->
            AppActor.logIn(LOGGED_IN_USER)
            purchaseUpdates.emit(listOf(approvedPendingPurchase()))

            observed.assertResolvedForLoggedInUser()
            assertTrue(awaitMainThreadCallback(observed.refreshedInfoPublished))
        }
    }

    @Test
    fun `a pending purchase approved while an anonymous user logs in resolves for them`() = runBlocking {
        val loginStarted = CountDownLatch(1)
        val releaseLogin = CountDownLatch(1)
        withPendingPurchaseOfAnonymousBuyer(loginGate = loginStarted to releaseLogin) { purchaseUpdates, observed ->
            coroutineScope {
                val login = async(Dispatchers.Default) { AppActor.logIn(LOGGED_IN_USER) }
                assertTrue(loginStarted.await(5, TimeUnit.SECONDS))
                purchaseUpdates.emit(listOf(approvedPendingPurchase()))
                // The updates are handled one at a time, so once the second empty one is taken the
                // purchase has been held for the login to post when it ends.
                purchaseUpdates.emit(emptyList())
                purchaseUpdates.emit(emptyList())
                assertEquals(1L, observed.receiptPosted.count)
                releaseLogin.countDown()
                withTimeout(5_000L) { login.await() }
            }

            observed.assertResolvedForLoggedInUser()
            assertTrue(awaitMainThreadCallback(observed.refreshedInfoPublished))
        }
    }

    @Test
    fun `a pending purchase approved after a relaunch still resolves for the user logged in to`() = runBlocking {
        withPendingPurchaseOfAnonymousBuyer(relaunchedAfterLogin = true) { _, observed ->
            observed.assertResolvedForLoggedInUser()
        }
    }

    @Test
    fun `a pending purchase whose receipt is retried after the login resolves for the user`() = runBlocking {
        withPendingPurchaseOfAnonymousBuyer(failFirstReceiptPost = true) { purchaseUpdates, observed ->
            AppActor.logIn(LOGGED_IN_USER)
            purchaseUpdates.emit(listOf(approvedPendingPurchase()))

            // The retry wake posts it again after the backoff.
            observed.assertResolvedForLoggedInUser(timeoutMillis = 15_000L, receiptPosts = 2)
            assertTrue(awaitMainThreadCallback(observed.refreshedInfoPublished))
        }
    }

    @Test
    fun `a pending purchase approved after the user logs out does not resolve for them`() = runBlocking {
        withPendingPurchaseOfAnonymousBuyer { purchaseUpdates, observed ->
            AppActor.logIn(LOGGED_IN_USER)
            AppActor.logOut()
            purchaseUpdates.emit(listOf(approvedPendingPurchase()))

            assertTrue(observed.receiptPosted.await(5, TimeUnit.SECONDS))
            assertFalse(awaitMainThreadCallback(observed.deferredResolved, timeoutMillis = 500L))
            assertEquals(listOf(ANONYMOUS_BUYER), observed.receiptAppUserIds)
        }
    }

    private class PendingPurchaseObservations {
        val deferredResolved = CountDownLatch(1)
        val refreshedInfoPublished = CountDownLatch(1)
        val receiptPosted = CountDownLatch(1)
        val deferredPurchases = CopyOnWriteArrayList<Triple<String, String?, String?>>()
        val receiptAppUserIds = CopyOnWriteArrayList<String>()

        /**
         * The receipt went to the backend under the anonymous id, which decides whose it is, and
         * the callback got the logged-in user's info, fetched fresh rather than relabelled.
         */
        fun assertResolvedForLoggedInUser(timeoutMillis: Long = 5_000L, receiptPosts: Int = 1) {
            assertTrue(awaitMainThreadCallback(deferredResolved, timeoutMillis))
            assertEquals(
                listOf(Triple(PENDING_PRODUCT_ID, LOGGED_IN_USER, REFRESHED_REQUEST_ID)),
                deferredPurchases,
            )
            assertEquals(List(receiptPosts) { ANONYMOUS_BUYER }, receiptAppUserIds)
        }
    }

    /**
     * Configures [ANONYMOUS_BUYER], who has a purchase pending approval from an earlier session,
     * then runs [scenario]. Login answers as [LOGGED_IN_USER], held by [loginGate] when given.
     * [relaunchedAfterLogin] configures instead as a previous session left it after logging the
     * buyer in, with the purchase approved and listed by Play. [failFirstReceiptPost] answers the
     * first receipt post with a retryable error.
     */
    private suspend fun withPendingPurchaseOfAnonymousBuyer(
        loginGate: Pair<CountDownLatch, CountDownLatch>? = null,
        relaunchedAfterLogin: Boolean = false,
        failFirstReceiptPost: Boolean = false,
        scenario: suspend (MutableSharedFlow<List<AppActorStorePurchase>>, PendingPurchaseObservations) -> Unit,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val purchaseUpdates = MutableSharedFlow<List<AppActorStorePurchase>>(extraBufferCapacity = 1)
        val observed = PendingPurchaseObservations()
        val activePurchases = if (relaunchedAfterLogin) listOf(approvedPendingPurchase()) else emptyList()
        AppActor.storeAdapterFactory = {
            FakeStoreAdapter(activePurchases = activePurchases, purchaseUpdatesFlow = purchaseUpdates)
        }
        val identity = context.getSharedPreferences("appactor_identity", Context.MODE_PRIVATE).edit()
        if (relaunchedAfterLogin) {
            identity.putString("appactor_billing_app_user_id", LOGGED_IN_USER)
            // As the login stored it.
            identity.putString(
                "appactor_billing_folded_app_user",
                """{"anonymousId":"$ANONYMOUS_BUYER","into":"$LOGGED_IN_USER"}""",
            )
        } else {
            identity.putString("appactor_billing_app_user_id", ANONYMOUS_BUYER)
        }
        identity.commit()
        val now = System.currentTimeMillis()
        context.getSharedPreferences("com.appactor.android.pending_purchases", Context.MODE_PRIVATE)
            .edit()
            .putString(
                PENDING_TOKEN,
                AppActorClientPurchaseContext.purchaseAttempt(startedAtMillis = now)
                    .toPendingEntry(productId = PENDING_PRODUCT_ID, recordedAtMillis = now, appUserId = ANONYMOUS_BUYER),
            )
            .commit()
        val receiptPosts = AtomicInteger(0)

        TestBackendServer { request ->
            val path = request.path?.substringBefore("?") ?: ""
            when {
                path == "/v1/payment/identify" -> jsonResponse(
                    customerEnvelope(requestId = "req_identify_pending", appUserId = identifyAppUserId(request, ANONYMOUS_BUYER)),
                )
                path == "/v1/payment/offerings" -> jsonResponse("""{"requestId":"req_off","data":{"offerings":[],"productEntitlements":{}}}""")
                path == "/v1/customers/$LOGGED_IN_USER" -> jsonResponse(
                    // Differs from the login's info in more than its requestId, so the listener is called again.
                    customerEnvelope(
                        requestId = REFRESHED_REQUEST_ID,
                        appUserId = LOGGED_IN_USER,
                        managementUrl = "https://play.google.com/store/account/subscriptions",
                    ),
                )
                path.startsWith("/v1/customers/") -> jsonResponse(
                    customerEnvelope(requestId = "req_customer_other", appUserId = path.substringAfterLast('/')),
                )
                path == "/v1/payment/login" -> {
                    loginGate?.let { (started, release) ->
                        started.countDown()
                        assertTrue(release.await(5, TimeUnit.SECONDS))
                    }
                    jsonResponse(loginEnvelope(requestId = "req_login_pending", appUserId = LOGGED_IN_USER))
                }
                path == "/v1/payment/receipts/google" -> {
                    observed.receiptAppUserIds += AppActorBackendJson.instance.parseToJsonElement(request.body.readUtf8())
                        .jsonObject.getValue("appUserId").jsonPrimitive.content
                    observed.receiptPosted.countDown()
                    if (failFirstReceiptPost && receiptPosts.incrementAndGet() == 1) {
                        jsonResponse("""{"status":"retryable_error","requestId":"req_receipt_retry","error":{"code":"UPSTREAM","message":"Try again."}}""")
                    } else {
                        jsonResponse(googleReceiptEnvelope(requestId = "req_receipt_pending", appUserId = ANONYMOUS_BUYER))
                    }
                }
                else -> jsonResponse("{}", 404)
            }
        }.use { backend ->
            AppActor.onDeferredPurchaseResolved = { productId, customerInfo ->
                observed.deferredPurchases += Triple(productId, customerInfo.appUserId, customerInfo.requestId)
                observed.deferredResolved.countDown()
            }
            AppActor.onCustomerInfoChanged = { info ->
                if (info.requestId == REFRESHED_REQUEST_ID) observed.refreshedInfoPublished.countDown()
            }
            AppActor.configure(
                AppActorConfiguration(
                    context = context,
                    apiKey = "pk_test_123",
                    baseUrl = backend.baseUrl,
                    options = testOptionsForLocalBackend(),
                )
            )
            scenario(purchaseUpdates, observed)
        }
    }

    private fun approvedPendingPurchase(): AppActorStorePurchase = AppActorStorePurchase(
        productId = PENDING_PRODUCT_ID,
        productType = com.appactor.android.models.AppActorProductType.Subscription,
        purchaseToken = PENDING_TOKEN,
        orderId = "GPA.pending.approved.1234",
        purchaseTimeMillis = 1_710_000_000_000,
        purchaseState = AppActorStorePurchaseState.Purchased,
        basePlanId = "monthly001",
        isAcknowledged = false,
        isAutoRenewing = true,
        rawPurchaseData = "{\"purchaseToken\":\"$PENDING_TOKEN\"}",
        purchaseSignature = "signature_pending_approved",
    )

    private companion object {
        const val ANONYMOUS_BUYER = "appactor-anon-pending-buyer"
        const val LOGGED_IN_USER = "user_folded"
        const val PENDING_PRODUCT_ID = "com.appactor.pro.monthly"
        const val PENDING_TOKEN = "token_pending_approved_after_login"
        const val REFRESHED_REQUEST_ID = "req_customer_user_folded"
    }
}
