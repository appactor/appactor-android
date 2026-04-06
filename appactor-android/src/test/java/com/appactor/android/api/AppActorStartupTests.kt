package com.appactor.android.api

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.appactor.android.backend.client.AppActorBackendJson
import com.appactor.android.backend.dto.AppActorGoogleReceiptRequestDTO
import com.appactor.android.models.AppActorConfiguration
import com.appactor.android.models.AppActorStore
import com.appactor.android.models.AppActorStoreCapability
import com.appactor.android.models.AppActorStorefront
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
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
    fun `configure returns with startup customer offerings and store readiness available`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
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
            when (request.path?.substringBefore("?")) {
                "/v1/payment/identify" -> jsonResponse(
                    customerEnvelope(
                        requestId = "req_identify_startup",
                        appUserId = "server_user_123",
                    ),
                )

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

                "/v1/customers/server_user_123" -> jsonResponse(
                    customerEnvelope(
                        requestId = "req_customer_startup",
                        appUserId = "server_user_123",
                    ),
                )

                else -> jsonResponse("{}", 404)
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

            assertEquals("server_user_123", AppActor.appUserId)
            assertEquals("server_user_123", AppActor.customerInfo.appUserId)
            assertTrue(AppActor.cachedOfferings != null)
            assertEquals(fakeStoreAdapter.storefront, AppActor.getStorefront())
            assertEquals(fakeStoreAdapter.capabilities, AppActor.getStoreCapabilities())
            assertTrue(AppActor.canMakePurchases(setOf(AppActorStoreCapability.Purchases)))
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
            when (request.path?.substringBefore("?")) {
                "/v1/payment/identify" -> jsonResponse(
                    customerEnvelope(
                        requestId = "req_identify_callback_startup",
                        appUserId = "callback_user_123",
                    ),
                )

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

                "/v1/customers/callback_user_123" -> jsonResponse(
                    customerEnvelope(
                        requestId = "req_customer_callback_startup",
                        appUserId = "callback_user_123",
                    ),
                )

                else -> jsonResponse("{}", 404)
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
                "/v1/payment/identify" -> jsonResponse(
                    customerEnvelope(
                        requestId = "req_identify_default_startup",
                        appUserId = "user_android_123",
                    ),
                )

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
    fun `startup purchase sync uses identified app user id before posting receipts`() = runBlocking {
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
                "/v1/payment/identify" -> jsonResponse(
                    customerEnvelope(
                        requestId = "req_identify_sync_order",
                        appUserId = "server_user_123",
                    ),
                )

                "/v1/payment/receipts/google" -> {
                    val payload = AppActorBackendJson.instance.decodeFromString<AppActorGoogleReceiptRequestDTO>(
                        request.body.readUtf8()
                    )
                    postedAppUserId.set(payload.appUserId)
                    receiptPosted.countDown()
                    jsonResponse(
                        googleReceiptEnvelope(
                            requestId = "req_receipt_sync_order",
                            appUserId = "server_user_123",
                        ),
                    )
                }

                "/v1/customers/server_user_123" -> jsonResponse(
                    customerEnvelope(
                        requestId = "req_customer_sync_order",
                        appUserId = "server_user_123",
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
            assertEquals("server_user_123", postedAppUserId.get())
        }
    }
}
