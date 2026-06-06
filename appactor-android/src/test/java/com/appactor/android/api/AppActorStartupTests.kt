package com.appactor.android.api

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.appactor.android.backend.client.AppActorBackendJson
import com.appactor.android.backend.dto.AppActorGoogleReceiptRequestDTO
import com.appactor.android.billing.AppActorStoreProduct
import com.appactor.android.models.AppActorConfiguration
import com.appactor.android.models.AppActorProductType
import com.appactor.android.models.AppActorReceiptPipelineEvent
import com.appactor.android.models.AppActorStore
import com.appactor.android.models.AppActorStoreCapability
import com.appactor.android.models.AppActorStorefront
import com.appactor.android.models.appActorPublicReceiptId
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
class AppActorStartupTests {

    @Before
    fun setUp() {
        resetApiTestState()
    }

    @Test
    fun `configure returns with startup customer offerings store readiness and local app user id available`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val requestedCustomerUserId = AtomicReference<String?>()
        val fakeStoreAdapter = FakeStoreAdapter(
            storefront = AppActorStorefront(
                store = AppActorStore.PlayStore,
                countryCode = "US",
            ),
            capabilities = linkedSetOf(
                AppActorStoreCapability.Purchases,
                AppActorStoreCapability.Subscriptions,
                AppActorStoreCapability.Storefront,
            ),
        )
        AppActor.storeAdapterFactory = { fakeStoreAdapter }

        TestBackendServer { request ->
            when (val path = request.path?.substringBefore("?")) {
                "/v1/payment/offerings" -> jsonResponse(
                    """
                        {
                          "requestId": "req_offerings_startup",
                          "data": {
                            "offerings": [],
                            "productEntitlements": {}
                          }
                        }
                    """.trimIndent(),
                )

                else -> if (path?.startsWith("/v1/customers/") == true) {
                    val appUserId = path.substringAfter("/v1/customers/")
                    requestedCustomerUserId.set(appUserId)
                    customerEnvelope(
                        requestId = "req_customer_startup",
                        appUserId = appUserId,
                    ).let(::jsonResponse)
                } else {
                    jsonResponse("{}", 404)
                }
            }
        }.use { backend ->
            AppActor.configure(
                AppActorConfiguration(
                    context = context,
                    apiKey = "pk_test_123",
                    appUserId = null,
                    baseUrl = backend.baseUrl,
                    options = AppActorConfiguration.Options(
                        verifyResponseSignatures = false,
                        requireResponseSignatures = false,
                    ),
                )
            )

            assertTrue(AppActor.isAnonymous)
            assertTrue(AppActor.appUserId?.startsWith("appactor-anon-") == true)
            assertEquals(AppActor.appUserId, AppActor.customerInfo.appUserId)
            assertEquals(AppActor.appUserId, requestedCustomerUserId.get())
            assertEquals(fakeStoreAdapter.storefront, AppActor.getStorefront())
            assertEquals(fakeStoreAdapter.capabilities, AppActor.getStoreCapabilities())
            assertTrue(AppActor.canMakePurchases(setOf(AppActorStoreCapability.Purchases)))
        }
    }

    @Test
    fun `configure does not wait for startup offerings enrichment to finish`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val queryStarted = CountDownLatch(1)
        val releaseQuery = CountDownLatch(1)
        val fakeStoreAdapter = FakeStoreAdapter(
            queryProductDetailsStarted = queryStarted,
            releaseQueryProductDetails = releaseQuery,
            resolvedProducts = listOf(
                AppActorStoreProduct(
                    productId = "com.appactor.pro.monthly",
                    productType = AppActorProductType.Subscription,
                    basePlanId = "monthly001",
                    offerId = "intro7d",
                    localizedPrice = "$4.99",
                ),
                AppActorStoreProduct(
                    productId = "com.appactor.coins.100",
                    productType = AppActorProductType.Consumable,
                    localizedPrice = "$1.99",
                ),
            ),
        )
        AppActor.storeAdapterFactory = { fakeStoreAdapter }

        TestBackendServer { request ->
            when (val path = request.path?.substringBefore("?")) {
                "/v1/payment/offerings" -> jsonResponse(startupOfferingsFixture())

                else -> if (path?.startsWith("/v1/customers/") == true) {
                    val appUserId = path.substringAfter("/v1/customers/")
                    customerEnvelope(
                        requestId = "req_customer_startup_non_blocking",
                        appUserId = appUserId,
                    ).let(::jsonResponse)
                } else {
                    jsonResponse("{}", 404)
                }
            }
        }.use { backend ->
            val configureFinished = CountDownLatch(1)
            val configureError = AtomicReference<Throwable?>()
            Thread {
                try {
                    runBlocking {
                        AppActor.configure(
                            AppActorConfiguration(
                                context = context,
                                apiKey = "pk_test_123",
                                appUserId = "user_android_123",
                                baseUrl = backend.baseUrl,
                                options = testOptionsForLocalBackend(),
                            )
                        )
                    }
                } catch (throwable: Throwable) {
                    configureError.set(throwable)
                } finally {
                    configureFinished.countDown()
                }
            }.start()

            assertTrue(queryStarted.await(5, TimeUnit.SECONDS))
            assertTrue(configureFinished.await(5, TimeUnit.SECONDS))
            configureError.get()?.let { throw AssertionError("configure failed", it) }

            assertEquals("user_android_123", AppActor.appUserId)
            assertEquals(AppActor.customerInfo.appUserId, AppActor.appUserId)
            assertNull(AppActor.cachedOfferings)

            releaseQuery.countDown()
            withTimeout(5_000L) {
                while (AppActor.cachedOfferings == null) {
                    kotlinx.coroutines.delay(10)
                }
            }
        }
    }

    @Test
    fun `configure does not wait for startup offerings api warmup to finish`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val offeringsRequested = CountDownLatch(1)
        val releaseOfferings = CountDownLatch(1)
        val customerRequested = CountDownLatch(1)
        val fakeStoreAdapter = FakeStoreAdapter(
            resolvedProducts = listOf(
                AppActorStoreProduct(
                    productId = "com.appactor.pro.monthly",
                    productType = AppActorProductType.Subscription,
                    basePlanId = "monthly001",
                    offerId = "intro7d",
                    localizedPrice = "$4.99",
                ),
                AppActorStoreProduct(
                    productId = "com.appactor.coins.100",
                    productType = AppActorProductType.Consumable,
                    localizedPrice = "$1.99",
                ),
            ),
        )
        AppActor.storeAdapterFactory = { fakeStoreAdapter }

        TestBackendServer { request ->
            when (val path = request.path?.substringBefore("?")) {
                "/v1/payment/offerings" -> {
                    offeringsRequested.countDown()
                    releaseOfferings.await(5, TimeUnit.SECONDS)
                    jsonResponse(startupOfferingsFixture())
                }

                else -> if (path?.startsWith("/v1/customers/") == true) {
                    val appUserId = path.substringAfter("/v1/customers/")
                    customerRequested.countDown()
                    customerEnvelope(
                        requestId = "req_customer_startup_offer_warmup",
                        appUserId = appUserId,
                    ).let(::jsonResponse)
                } else {
                    jsonResponse("{}", 404)
                }
            }
        }.use { backend ->
            val configureFinished = CountDownLatch(1)
            val configureError = AtomicReference<Throwable?>()
            Thread {
                try {
                    runBlocking {
                        AppActor.configure(
                            AppActorConfiguration(
                                context = context,
                                apiKey = "pk_test_123",
                                appUserId = "user_android_123",
                                baseUrl = backend.baseUrl,
                                options = testOptionsForLocalBackend(),
                            )
                        )
                    }
                } catch (throwable: Throwable) {
                    configureError.set(throwable)
                } finally {
                    configureFinished.countDown()
                }
            }.start()

            assertTrue(offeringsRequested.await(5, TimeUnit.SECONDS))
            assertTrue(customerRequested.await(5, TimeUnit.SECONDS))
            assertTrue(configureFinished.await(5, TimeUnit.SECONDS))
            configureError.get()?.let { throw AssertionError("configure failed", it) }

            assertEquals("user_android_123", AppActor.appUserId)
            assertEquals(AppActor.customerInfo.appUserId, AppActor.appUserId)
            assertNull(AppActor.cachedOfferings)

            releaseOfferings.countDown()
            withTimeout(5_000L) {
                while (AppActor.cachedOfferings == null) {
                    kotlinx.coroutines.delay(10)
                }
            }
        }
    }

    @Test
    fun `receipt pipeline callback can be installed before configure`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val callback: (com.appactor.android.models.AppActorReceiptPipelineEvent) -> Unit = { }
        val fakeStoreAdapter = FakeStoreAdapter()
        AppActor.storeAdapterFactory = { fakeStoreAdapter }

        AppActor.onReceiptPipelineEvent = callback

        assertTrue(AppActor.onReceiptPipelineEvent === callback)

        TestBackendServer { request ->
            when (val path = request.path?.substringBefore("?")) {
                "/v1/payment/offerings" -> jsonResponse(
                    """
                        {
                          "requestId": "req_offerings_callback_startup",
                          "data": {
                            "offerings": [],
                            "productEntitlements": {}
                          }
                        }
                    """.trimIndent(),
                )

                else -> if (path?.startsWith("/v1/customers/") == true) {
                    val appUserId = path.substringAfter("/v1/customers/")
                    customerEnvelope(
                        requestId = "req_customer_callback_startup",
                        appUserId = appUserId,
                    ).let(::jsonResponse)
                } else {
                    jsonResponse("{}", 404)
                }
            }
        }.use { backend ->
            AppActor.configure(
                AppActorConfiguration(
                    context = context,
                    apiKey = "pk_test_123",
                    appUserId = null,
                    baseUrl = backend.baseUrl,
                    options = testOptionsForLocalBackend(),
                )
            )
        }

        assertTrue(AppActor.onReceiptPipelineEvent === callback)
    }

    @Test
    fun `customer info callback survives reset and fires again after reconfigure`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val deliveredUserIds = ConcurrentHashMap.newKeySet<String>()
        val callbackLatch = CountDownLatch(1)
        val fakeStoreAdapter = FakeStoreAdapter()
        AppActor.storeAdapterFactory = { fakeStoreAdapter }

        AppActor.onCustomerInfoChanged = { info ->
            val appUserId = info.appUserId
            if (appUserId == "user_reset_b" && deliveredUserIds.add(appUserId)) {
                callbackLatch.countDown()
            }
        }

        TestBackendServer { request ->
            when (val path = request.path?.substringBefore("?")) {
                "/v1/payment/offerings" -> jsonResponse(
                    """
                        {
                          "requestId": "req_offerings_callback_reconfigure",
                          "data": {
                            "offerings": [],
                            "productEntitlements": {}
                          }
                        }
                    """.trimIndent(),
                )

                else -> if (path?.startsWith("/v1/customers/") == true) {
                    val appUserId = path.substringAfter("/v1/customers/")
                    customerEnvelope(
                        requestId = "req_customer_callback_reconfigure_$appUserId",
                        appUserId = appUserId,
                    ).let(::jsonResponse)
                } else {
                    jsonResponse("{}", 404)
                }
            }
        }.use { backend ->
            AppActor.configure(
                AppActorConfiguration(
                    context = context,
                    apiKey = "pk_test_123",
                    appUserId = "user_reset_a",
                    baseUrl = backend.baseUrl,
                    options = testOptionsForLocalBackend(),
                )
            )

            AppActor.reset()

            AppActor.configure(
                AppActorConfiguration(
                    context = context,
                    apiKey = "pk_test_123",
                    appUserId = "user_reset_b",
                    baseUrl = backend.baseUrl,
                    options = testOptionsForLocalBackend(),
                )
            )
        }

        assertTrue(awaitMainThreadCallback(callbackLatch))
        assertTrue(deliveredUserIds.contains("user_reset_b"))
    }

    @Test
    fun `queued receipt callback from a previous runtime is dropped after reset`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val callbackLatch = CountDownLatch(1)

        AppActor.onReceiptPipelineEvent = {
            callbackLatch.countDown()
        }

        stubBackend().use { backend ->
            AppActor.configure(
                AppActorConfiguration(
                    context = context,
                    apiKey = "pk_test_123",
                    appUserId = "user_runtime_a",
                    baseUrl = backend.baseUrl,
                    options = testOptionsForLocalBackend(),
                )
            )

            val publishMethod = AppActor::class.java.getDeclaredMethod(
                "publishReceiptPipelineEvent",
                java.lang.Long.TYPE,
                AppActorReceiptPipelineEvent::class.java,
            ).apply {
                isAccessible = true
            }

            shadowOf(Looper.getMainLooper()).pause()
            Thread {
                publishMethod.invoke(
                    AppActor,
                    currentRuntimeSessionId(),
                    AppActorReceiptPipelineEvent.RetryScheduled(
                        key = appActorPublicReceiptId("raw_receipt_key_runtime_a"),
                        productId = "com.appactor.pro.monthly",
                        retryCount = 1,
                        nextRetryAtMillis = 1_234L,
                        errorCode = "NETWORK_TIMEOUT",
                        appUserId = "user_runtime_a",
                    ),
                )
            }.apply {
                start()
                join()
            }

            AppActor.reset()
            assertFalse(awaitMainThreadCallback(callbackLatch, timeoutMillis = 500L))
        }
    }

    @Test
    fun `configure returns after startup purchase sync has completed`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val purchaseSyncCalls = AtomicInteger(0)
        val fakeStoreAdapter = FakeStoreAdapter(
            activePurchases = listOf(
                com.appactor.android.billing.AppActorStorePurchase(
                    productId = "com.appactor.pro.monthly",
                    productType = com.appactor.android.models.AppActorProductType.Subscription,
                    purchaseToken = "token_startup_sync_blocked",
                    orderId = "GPA.startup.blocked.1234",
                    purchaseTimeMillis = 1_710_000_000_000,
                    purchaseState = com.appactor.android.billing.AppActorStorePurchaseState.Purchased,
                    basePlanId = "monthly001",
                    offerId = "intro7d",
                    isAcknowledged = false,
                    isAutoRenewing = true,
                    rawPurchaseData = "{\"purchaseToken\":\"token_startup_sync_blocked\"}",
                    purchaseSignature = "signature_startup_sync_blocked",
                )
            ),
        )
        AppActor.storeAdapterFactory = { fakeStoreAdapter }

        TestBackendServer { request ->
            when (request.path?.substringBefore("?")) {
                "/v1/payment/sync/google" -> {
                    purchaseSyncCalls.incrementAndGet()
                    jsonResponse(
                        """
                            {
                              "requestId": "req_sync_default_startup",
                              "appUserId": "user_android_123",
                              "customer": {
                                "entitlements": {},
                                "subscriptions": {},
                                "nonSubscriptions": {}
                              },
                              "syncedCount": 1,
                              "transferred": false,
                              "results": [
                                {
                                  "purchaseToken": "token_startup_sync_blocked",
                                  "productId": "com.appactor.pro.monthly",
                                  "basePlanId": "monthly001",
                                  "offerId": "intro7d",
                                  "status": "synced"
                                }
                              ]
                            }
                        """.trimIndent(),
                    )
                }

                "/v1/payment/offerings" -> jsonResponse(
                    """
                        {
                          "requestId": "req_offerings_default_startup",
                          "data": {
                            "offerings": [],
                            "productEntitlements": {}
                          }
                        }
                    """.trimIndent(),
                )

                "/v1/customers/user_android_123" -> jsonResponse(
                    customerEnvelope(
                        requestId = "req_customer_default_startup",
                        appUserId = "user_android_123",
                    ),
                )

                else -> jsonResponse("{}", 404)
            }
        }.use { backend ->
            AppActor.configure(
                AppActorConfiguration(
                    context = context,
                    apiKey = "pk_test_123",
                    appUserId = "user_android_123",
                    baseUrl = backend.baseUrl,
                    options = AppActorConfiguration.Options(
                        verifyResponseSignatures = false,
                        requireResponseSignatures = false,
                    ),
                )
            )

            assertEquals(1, purchaseSyncCalls.get())
            assertEquals("user_android_123", AppActor.appUserId)
        }
    }

    @Test
    fun `startup purchase sync uses local app user id before posting receipts`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val receiptPosted = CountDownLatch(1)
        val postedAppUserId = AtomicReference<String?>()
        val fakeStoreAdapter = FakeStoreAdapter(
            activePurchases = listOf(
                com.appactor.android.billing.AppActorStorePurchase(
                    productId = "com.appactor.pro.monthly",
                    productType = com.appactor.android.models.AppActorProductType.Subscription,
                    purchaseToken = "token_startup_sync_123",
                    orderId = "GPA.startup.1234",
                    purchaseTimeMillis = 1_710_000_000_000,
                    purchaseState = com.appactor.android.billing.AppActorStorePurchaseState.Purchased,
                    basePlanId = "monthly001",
                    offerId = "intro7d",
                    isAcknowledged = false,
                    isAutoRenewing = true,
                    rawPurchaseData = "{\"purchaseToken\":\"token_startup_sync_123\"}",
                    purchaseSignature = "signature_startup_sync_123",
                )
            ),
        )
        AppActor.storeAdapterFactory = { fakeStoreAdapter }

        TestBackendServer { request ->
            when (request.path?.substringBefore("?")) {
                "/v1/payment/receipts/google" -> {
                    val payload = AppActorBackendJson.instance.decodeFromString<AppActorGoogleReceiptRequestDTO>(
                        request.body.readUtf8()
                    )
                    postedAppUserId.set(payload.appUserId)
                    receiptPosted.countDown()
                    jsonResponse(
                        googleReceiptEnvelope(
                            requestId = "req_receipt_sync_order",
                            appUserId = "local_user_123",
                        ),
                    )
                }

                "/v1/payment/offerings" -> jsonResponse(
                    """
                        {
                          "requestId": "req_offerings_sync_order",
                          "data": {
                            "offerings": [],
                            "productEntitlements": {}
                          }
                        }
                    """.trimIndent(),
                )

                "/v1/customers/local_user_123" -> jsonResponse(
                    customerEnvelope(
                        requestId = "req_customer_sync_order",
                        appUserId = "local_user_123",
                    ),
                )

                else -> jsonResponse("{}", 404)
            }
        }.use { backend ->
            AppActor.configure(
                AppActorConfiguration(
                    context = context,
                    apiKey = "pk_test_123",
                    appUserId = "local_user_123",
                    baseUrl = backend.baseUrl,
                    options = AppActorConfiguration.Options(
                        verifyResponseSignatures = false,
                        requireResponseSignatures = false,
                    ),
                )
            )

            assertTrue(receiptPosted.await(5, TimeUnit.SECONDS))
            assertEquals("local_user_123", postedAppUserId.get())
        }
    }

    private fun startupOfferingsFixture(): String {
        return requireNotNull(
            javaClass.classLoader?.getResource("fixtures/backend/offerings_android_sample.json")
        ).readText()
    }
}
