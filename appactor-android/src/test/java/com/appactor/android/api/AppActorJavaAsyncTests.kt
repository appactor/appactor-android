package com.appactor.android.api

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.appactor.android.models.AppActorCompletionCallback
import com.appactor.android.models.AppActorError
import com.appactor.android.models.AppActorErrorCallback
import com.appactor.android.models.AppActorOptions
import com.appactor.android.models.AppActorSuccessCallback
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
class AppActorJavaAsyncTests {

    @Before
    fun setUp() {
        resetApiTestState()
    }

    @Test
    fun `configureAsync completion callback is delivered on main thread`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val latch = CountDownLatch(1)
        val callbackOnMain = AtomicReference<Boolean?>()
        val error = AtomicReference<AppActorError?>()
        AppActor.storeAdapterFactory = { FakeStoreAdapter() }

        TestBackendServer { request ->
            when (request.path?.substringBefore("?")) {
                "/v1/payment/identify" -> jsonResponse(
                    customerEnvelope(requestId = "req_id_async", appUserId = "anon_async"),
                )
                "/v1/payment/offerings" -> jsonResponse(
                    """{"requestId":"req_off","data":{"offerings":[],"productEntitlements":{}}}""",
                )
                else -> {
                    val path = request.path?.substringBefore("?") ?: ""
                    if (path.startsWith("/v1/customers/")) {
                        jsonResponse(customerEnvelope(requestId = "req_cust_async", appUserId = "anon_async"))
                    } else {
                        jsonResponse("{}", 404)
                    }
                }
            }
        }.use { backend ->
            AppActor.configure(
                com.appactor.android.models.AppActorConfiguration(
                    context = context,
                    apiKey = "pk_test_async",
                    baseUrl = backend.baseUrl,
                    options = testOptionsForLocalBackend(),
                )
            )
            // Verify main-thread delivery by calling an async Java method after configure
            AppActorJava.getCustomerInfoAsync(
                AppActorSuccessCallback {
                    callbackOnMain.set(Looper.myLooper() == Looper.getMainLooper())
                    latch.countDown()
                },
                AppActorErrorCallback {
                    error.set(it)
                    latch.countDown()
                },
            )

            assertTrue(awaitMainThreadCallback(latch))
            assertNull(error.get())
            assertEquals(true, callbackOnMain.get())
        }
    }

    @Test
    fun `async error callback is delivered on main thread`() {
        val latch = CountDownLatch(1)
        val callbackOnMain = AtomicReference<Boolean?>()
        val capturedError = AtomicReference<AppActorError?>()

        AppActorJava.logOutAsync(
            onSuccess = AppActorSuccessCallback { latch.countDown() },
            onError = AppActorErrorCallback { error ->
                callbackOnMain.set(Looper.myLooper() == Looper.getMainLooper())
                capturedError.set(error)
                latch.countDown()
            },
        )

        assertTrue(awaitMainThreadCallback(latch))
        assertTrue(capturedError.get() is AppActorError.NotConfigured)
        assertEquals(true, callbackOnMain.get())
    }

    @Test
    fun `java async backend 503 errors are classified as transient server errors`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val latch = CountDownLatch(1)
        val callbackOnMain = AtomicReference<Boolean?>()
        val capturedError = AtomicReference<AppActorError?>()

        TestBackendServer { request ->
            when (request.path?.substringBefore("?")) {
                "/v1/customers/user_java_async_error" -> jsonResponse(
                    body = """{"message":"temporary outage"}""",
                    statusCode = 503,
                )

                else -> jsonResponse("{}", 404)
            }
        }.use { backend ->
            AppActor.configure(
                com.appactor.android.models.AppActorConfiguration(
                    context = context,
                    apiKey = "pk_test_async",
                    appUserId = "user_java_async_error",
                    baseUrl = backend.baseUrl,
                    options = testOptionsForLocalBackend(),
                )
            )

            AppActorJava.getCustomerInfoAsync(
                onSuccess = AppActorSuccessCallback { latch.countDown() },
                onError = AppActorErrorCallback { error ->
                    callbackOnMain.set(Looper.myLooper() == Looper.getMainLooper())
                    capturedError.set(error)
                    latch.countDown()
                },
            )

            assertTrue(awaitMainThreadCallback(latch, timeoutMillis = 15_000L))
            assertTrue(capturedError.get() is AppActorError.Server)
            assertEquals(true, callbackOnMain.get())
        }
    }

    @Test
    fun `java auth async success callbacks are delivered on main thread`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val loginCallbackOnMain = AtomicReference<Boolean?>()
        val logoutCallbackOnMain = AtomicReference<Boolean?>()
        val loginUserId = AtomicReference<String?>()
        val logoutAcknowledged = AtomicReference<Boolean?>()
        val loginError = AtomicReference<AppActorError?>()
        val logoutError = AtomicReference<AppActorError?>()
        val logoutCalls = AtomicInteger(0)

        TestBackendServer { request ->
            when (request.path?.substringBefore("?")) {
                "/v1/payment/login" -> jsonResponse(
                    loginEnvelope(
                        requestId = "req_login_java_async",
                        appUserId = "user_b",
                    ),
                )

                "/v1/payment/logout" -> jsonResponse(
                    logoutEnvelope(requestId = "req_logout_java_async").also {
                        logoutCalls.incrementAndGet()
                    },
                )

                "/v1/payment/identify" -> jsonResponse(
                    customerEnvelope(
                        requestId = "req_identify_java_async",
                        appUserId = "appactor-anon-java",
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

            val loginLatch = CountDownLatch(1)
            AppActorJava.logInAsync(
                "user_b",
                AppActorSuccessCallback { info ->
                    loginCallbackOnMain.set(Looper.myLooper() == Looper.getMainLooper())
                    loginUserId.set(info.appUserId)
                    loginLatch.countDown()
                },
                AppActorErrorCallback { error ->
                    loginError.set(error)
                    loginLatch.countDown()
                },
            )

            assertTrue(awaitMainThreadCallback(loginLatch))
            assertNull(loginError.get())
            assertEquals("user_b", loginUserId.get())
            assertEquals(true, loginCallbackOnMain.get())

            val logoutLatch = CountDownLatch(1)
            AppActorJava.logOutAsync(
                AppActorSuccessCallback { acknowledged ->
                    logoutCallbackOnMain.set(Looper.myLooper() == Looper.getMainLooper())
                    logoutAcknowledged.set(acknowledged)
                    logoutLatch.countDown()
                },
                AppActorErrorCallback { error ->
                    logoutError.set(error)
                    logoutLatch.countDown()
                },
            )

            assertTrue(awaitMainThreadCallback(logoutLatch))
            assertNull(logoutError.get())
            assertEquals(true, logoutAcknowledged.get())
            assertEquals(true, logoutCallbackOnMain.get())
            assertEquals(0, logoutCalls.get())
        }
    }

    @Test
    fun `java sync and restore async surface forwards success and listener callbacks`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val activePurchase = com.appactor.android.billing.AppActorStorePurchase(
            productId = "com.appactor.pro.monthly",
            productType = com.appactor.android.models.AppActorProductType.Subscription,
            purchaseToken = "token_java_sync_123",
            orderId = "GPA.java.sync.1234",
            purchaseTimeMillis = 1_710_000_000_000,
            purchaseState = com.appactor.android.billing.AppActorStorePurchaseState.Purchased,
            basePlanId = "monthly001",
            offerId = "intro7d",
            isAcknowledged = false,
            isAutoRenewing = true,
            rawPurchaseData = "{\"purchaseToken\":\"token_java_sync_123\"}",
            purchaseSignature = "signature_java_sync_123",
        )
        val fakeStoreAdapter = FakeStoreAdapter(activePurchases = listOf(activePurchase))
        val syncCallbackOnMain = AtomicReference<Boolean?>()
        val restoreCallbackOnMain = AtomicReference<Boolean?>()
        val customerListenerOnMain = AtomicReference<Boolean?>()
        val syncUserId = AtomicReference<String?>()
        val restoreUserId = AtomicReference<String?>()
        val customerListenerUserId = AtomicReference<String?>()
        val syncError = AtomicReference<AppActorError?>()
        val restoreError = AtomicReference<AppActorError?>()
        AppActor.storeAdapterFactory = { fakeStoreAdapter }

        TestBackendServer { request ->
            when (request.path?.substringBefore("?")) {
                "/v1/payment/receipts/google" -> jsonResponse(
                    googleReceiptEnvelope(
                        requestId = "req_receipt_java_sync",
                        appUserId = "server_user_123",
                    ),
                )

                "/v1/payment/sync/google" -> jsonResponse(
                    googleRestoreEnvelope(requestId = "req_sync_java_sync"),
                )

                "/v1/payment/restore/google" -> jsonResponse(
                    googleRestoreEnvelope(requestId = "req_restore_java_sync"),
                )

                "/v1/customers/server_user_123" -> jsonResponse(
                    customerEnvelope(
                        requestId = "req_customer_java_sync",
                        appUserId = "server_user_123",
                    ),
                )

                else -> jsonResponse("{}", 404)
            }
        }.use { backend ->
            AppActor.configure(
                com.appactor.android.models.AppActorConfiguration(
                    context = context,
                    apiKey = "pk_test_123",
                    appUserId = "server_user_123",
                    baseUrl = backend.baseUrl,
                    options = testOptionsForLocalBackend(),
                )
            )

            val customerListenerLatch = CountDownLatch(1)
            AppActorJava.setOnCustomerInfoChangedListener(
                AppActorSuccessCallback { info ->
                    customerListenerOnMain.set(Looper.myLooper() == Looper.getMainLooper())
                    customerListenerUserId.set(info.appUserId)
                    customerListenerLatch.countDown()
                }
            )

            val syncLatch = CountDownLatch(1)
            AppActorJava.syncPurchasesAsync(
                AppActorSuccessCallback { info ->
                    syncCallbackOnMain.set(Looper.myLooper() == Looper.getMainLooper())
                    syncUserId.set(info.appUserId)
                    syncLatch.countDown()
                },
                AppActorErrorCallback { error ->
                    syncError.set(error)
                    syncLatch.countDown()
                },
            )

            assertTrue(awaitMainThreadCallback(syncLatch))
            assertNull(syncError.get())
            assertEquals("server_user_123", syncUserId.get())
            assertEquals(true, syncCallbackOnMain.get())
            assertTrue(awaitMainThreadCallback(customerListenerLatch))
            assertEquals("server_user_123", customerListenerUserId.get())
            assertEquals(true, customerListenerOnMain.get())

            val restoreLatch = CountDownLatch(1)
            AppActorJava.restorePurchasesAsync(
                AppActorSuccessCallback { info ->
                    restoreCallbackOnMain.set(Looper.myLooper() == Looper.getMainLooper())
                    restoreUserId.set(info.appUserId)
                    restoreLatch.countDown()
                },
                AppActorErrorCallback { error ->
                    restoreError.set(error)
                    restoreLatch.countDown()
                },
            )

            assertTrue(awaitMainThreadCallback(restoreLatch))
            assertNull(restoreError.get())
            assertEquals("server_user_123", restoreUserId.get())
            assertEquals(true, restoreCallbackOnMain.get())
        }
    }

    @Test
    fun `sync purchases refreshes customer info with canonical app user id`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val activePurchase = com.appactor.android.billing.AppActorStorePurchase(
            productId = "com.appactor.pro.monthly",
            productType = com.appactor.android.models.AppActorProductType.Subscription,
            purchaseToken = "token_java_sync_canonical",
            orderId = "GPA.java.sync.canonical",
            purchaseTimeMillis = 1_710_000_000_000,
            purchaseState = com.appactor.android.billing.AppActorStorePurchaseState.Purchased,
            basePlanId = "monthly001",
            offerId = "intro7d",
            isAcknowledged = false,
            isAutoRenewing = true,
            rawPurchaseData = "{\"purchaseToken\":\"token_java_sync_canonical\"}",
            purchaseSignature = "signature_java_sync_canonical",
        )
        val fakeStoreAdapter = FakeStoreAdapter(activePurchases = listOf(activePurchase))
        val syncRequestCount = AtomicInteger(0)
        val oldCustomerRequestCount = AtomicInteger(0)
        val canonicalCustomerRequestCount = AtomicInteger(0)
        AppActor.storeAdapterFactory = { fakeStoreAdapter }

        TestBackendServer { request ->
            when (request.path?.substringBefore("?")) {
                "/v1/payment/identify" -> jsonResponse(
                    customerEnvelope(
                        requestId = "req_identify_sync_canonical",
                        appUserId = "server_user_123",
                    ),
                )

                "/v1/payment/offerings" -> jsonResponse(
                    """{"requestId":"req_off","data":{"offerings":[],"productEntitlements":{}}}""",
                )

                "/v1/payment/sync/google" -> {
                    if (syncRequestCount.incrementAndGet() == 1) {
                        jsonResponse(
                            """
                            {
                              "customer": {
                                "entitlements": {},
                                "subscriptions": {},
                                "nonSubscriptions": {}
                              },
                              "syncedCount": 1,
                              "transferred": false,
                              "requestId": "req_sync_startup"
                            }
                            """.trimIndent(),
                        )
                    } else {
                        jsonResponse(
                            """
                            {
                              "appUserId": "user_google_canonical",
                              "customer": {
                                "entitlements": {},
                                "subscriptions": {},
                                "nonSubscriptions": {}
                              },
                              "syncedCount": 1,
                              "transferred": true,
                              "requestId": "req_sync_explicit"
                            }
                            """.trimIndent(),
                        )
                    }
                }

                "/v1/customers/server_user_123" -> {
                    oldCustomerRequestCount.incrementAndGet()
                    jsonResponse(
                        customerEnvelope(
                            requestId = "req_customer_old_${oldCustomerRequestCount.get()}",
                            appUserId = "server_user_123",
                        ),
                    )
                }

                "/v1/customers/user_google_canonical" -> {
                    canonicalCustomerRequestCount.incrementAndGet()
                    jsonResponse(
                        customerEnvelope(
                            requestId = "req_customer_canonical",
                            appUserId = "user_google_canonical",
                        ),
                    )
                }

                else -> jsonResponse("{}", 404)
            }
        }.use { backend ->
            AppActor.configure(
                com.appactor.android.models.AppActorConfiguration(
                    context = context,
                    apiKey = "pk_test_sync_canonical",
                    appUserId = "server_user_123",
                    baseUrl = backend.baseUrl,
                    options = testOptionsForLocalBackend(),
                )
            )

            val info = AppActor.syncPurchases()

            assertEquals("user_google_canonical", info.appUserId)
            assertEquals(2, syncRequestCount.get())
            assertEquals(1, oldCustomerRequestCount.get())
            assertEquals(1, canonicalCustomerRequestCount.get())
        }
    }
}
