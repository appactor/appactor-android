package com.appactor.android.managers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.appactor.android.backend.client.AppActorBackendClient
import com.appactor.android.backend.client.AppActorBackendHttpResponse
import com.appactor.android.backend.client.AppActorBackendJson
import com.appactor.android.backend.dto.AppActorCustomerEnvelopeDTO
import com.appactor.android.backend.dto.AppActorOfferingsEnvelopeDTO
import com.appactor.android.billing.AppActorStoreAdapter
import com.appactor.android.billing.AppActorStoreProduct
import com.appactor.android.billing.AppActorStoreProductRequest
import com.appactor.android.billing.AppActorStorePurchase
import com.appactor.android.cache.AppActorCacheDiskStore
import com.appactor.android.cache.AppActorCustomerCacheStore
import com.appactor.android.cache.AppActorETagManager
import com.appactor.android.cache.AppActorOfflineProductCatalogStore
import com.appactor.android.cache.AppActorOfferingsCacheStore
import com.appactor.android.models.AppActorProductType
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class AppActorOfflineEntitlementManagerTests {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `offline manager derives keys from active play purchases first`() = runBlocking {
        val activePurchase = AppActorStorePurchase(
            productId = "com.appactor.pro.monthly",
            productType = AppActorProductType.Subscription,
            purchaseToken = "token_123",
            orderId = "GPA.1234",
            purchaseTimeMillis = 1_710_000_000_000,
            purchaseState = com.appactor.android.billing.AppActorStorePurchaseState.Purchased,
            basePlanId = "monthly001",
        )
        val seedStoreAdapter = createMockStoreAdapter(activePurchases = listOf(activePurchase))
        val offeringsManager = createOfferingsManager(storeAdapter = seedStoreAdapter)
        val testStoreAdapter = createMockStoreAdapter(activePurchases = listOf(activePurchase))
        val manager = AppActorOfflineEntitlementManager(
            customerCacheStore = createCustomerCacheStore(),
            offlineProductCatalogStore = createOfflineProductCatalogStore(),
            offeringsManager = offeringsManager,
            storeAdapter = testStoreAdapter,
        )

        val keys = manager.activeEntitlementKeysOffline("user_android_123")

        assertEquals(setOf("premium"), keys)
    }

    @Test
    fun `offline manager falls back to fresh cached customer when no active purchases exist`() = runBlocking {
        val now = System.currentTimeMillis()
        val offeringsManager = createOfferingsManager()
        val customerCacheStore = createCustomerCacheStore()
        customerCacheStore.save(
            appUserId = "user_android_123",
            payload = AppActorBackendJson.instance.encodeToString(
                fixtureCustomer("fixtures/backend/customer_android_active.json")
            ),
            eTag = "\"etag_customer\"",
            verified = true,
        )
        val mockStoreAdapter = createMockStoreAdapter()
        val manager = AppActorOfflineEntitlementManager(
            customerCacheStore = customerCacheStore,
            offlineProductCatalogStore = createOfflineProductCatalogStore(),
            offeringsManager = offeringsManager,
            storeAdapter = mockStoreAdapter,
            dateProviderMillis = { now },
        )

        val keys = manager.activeEntitlementKeysOffline("user_android_123")

        assertEquals(setOf("premium"), keys)
    }

    @Test
    fun `offline manager unions store-derived keys with server-granted cached keys`() = runBlocking {
        // Local Play ownership resolves to coin_pack_100, while the cached server-authoritative
        // customer holds a separate active entitlement (premium) that has no local Play purchase.
        // The offline fallback must surface BOTH, not just the store-derived subset.
        val now = System.currentTimeMillis()
        val consumablePurchase = AppActorStorePurchase(
            productId = "com.appactor.coins.100",
            productType = AppActorProductType.Consumable,
            purchaseToken = "token_coins_100",
            orderId = "GPA.5555",
            purchaseTimeMillis = 1_710_000_000_000,
            purchaseState = com.appactor.android.billing.AppActorStorePurchaseState.Purchased,
            basePlanId = null,
        )
        val seedStoreAdapter = createMockStoreAdapter(activePurchases = listOf(consumablePurchase))
        val offeringsManager = createOfferingsManager(storeAdapter = seedStoreAdapter)
        val testStoreAdapter = createMockStoreAdapter(activePurchases = listOf(consumablePurchase))
        val customerCacheStore = createCustomerCacheStore()
        customerCacheStore.save(
            appUserId = "user_android_123",
            payload = AppActorBackendJson.instance.encodeToString(
                fixtureCustomer("fixtures/backend/customer_android_active.json")
            ),
            eTag = "\"etag_customer\"",
            verified = true,
        )
        val manager = AppActorOfflineEntitlementManager(
            customerCacheStore = customerCacheStore,
            offlineProductCatalogStore = createOfflineProductCatalogStore(),
            offeringsManager = offeringsManager,
            storeAdapter = testStoreAdapter,
            dateProviderMillis = { now },
        )

        val keys = manager.activeEntitlementKeysOffline("user_android_123")

        assertEquals(setOf("coin_pack_100", "premium"), keys)
    }

    @Test
    fun `offline manager ignores stale cached customer fallback`() = runBlocking {
        val offeringsManager = createOfferingsManager()
        val customerCacheStore = createCustomerCacheStore()
        customerCacheStore.save(
            appUserId = "user_android_123",
            payload = AppActorBackendJson.instance.encodeToString(
                fixtureCustomer("fixtures/backend/customer_android_active.json")
            ),
            eTag = "\"etag_customer\"",
            verified = true,
        )
        val persistedCachedAt = requireNotNull(customerCacheStore.load("user_android_123")).cachedAtMillis
        val mockStoreAdapter = createMockStoreAdapter()
        val manager = AppActorOfflineEntitlementManager(
            customerCacheStore = customerCacheStore,
            offlineProductCatalogStore = createOfflineProductCatalogStore(),
            offeringsManager = offeringsManager,
            storeAdapter = mockStoreAdapter,
            dateProviderMillis = {
                persistedCachedAt + AppActorOfflineEntitlementManager.CUSTOMER_CACHE_TTL_MILLIS + 1
            },
        )

        val keys = manager.activeEntitlementKeysOffline("user_android_123")

        assertTrue(keys.isEmpty())
    }

    private fun createMockStoreAdapter(
        activePurchases: List<AppActorStorePurchase> = emptyList(),
    ): AppActorStoreAdapter {
        val mockStoreAdapter = mockk<AppActorStoreAdapter>(relaxed = true)
        coEvery { mockStoreAdapter.queryProductDetails(any()) } answers {
            val requests = firstArg<List<AppActorStoreProductRequest>>()
            requests.map { request ->
                AppActorStoreProduct(
                    productId = request.productId,
                    productType = request.productType,
                    basePlanId = request.basePlanId,
                    offerId = request.offerId,
                    localizedPrice = "$4.99",
                )
            }
        }
        coEvery { mockStoreAdapter.queryActivePurchases() } returns activePurchases
        return mockStoreAdapter
    }

    private fun createOfferingsManager(
        storeAdapter: AppActorStoreAdapter = createMockStoreAdapter(),
    ): AppActorOfferingsManager {
        val mockClient = mockk<AppActorBackendClient>(relaxed = true)
        coEvery { mockClient.getOfferings(any()) } returns AppActorBackendHttpResponse(
            body = fixtureOfferings(),
            statusCode = 200,
            requestId = "req_offerings_123",
            eTag = "\"etag_offerings\"",
            signatureVerified = true,
        )
        return AppActorOfferingsManager(
            backendClient = mockClient,
            cacheStore = AppActorOfferingsCacheStore(
                AppActorETagManager(
                    diskStore = AppActorCacheDiskStore(
                        context,
                        File(context.cacheDir, "tests/offline-offerings-${UUID.randomUUID()}")
                    ),
                    responseVerificationEnabled = false,
                )
            ),
            offlineProductCatalogStore = createOfflineProductCatalogStore(),
            storeAdapter = storeAdapter,
        ).also { runBlocking { it.getOfferings(forceRefresh = true) } }
    }

    private fun createOfflineProductCatalogStore(): AppActorOfflineProductCatalogStore {
        return AppActorOfflineProductCatalogStore(
            AppActorETagManager(
                diskStore = AppActorCacheDiskStore(
                    context,
                    File(context.cacheDir, "tests/offline-product-catalog-${UUID.randomUUID()}")
                ),
                responseVerificationEnabled = false,
            )
        )
    }

    private fun createCustomerCacheStore(): AppActorCustomerCacheStore {
        return AppActorCustomerCacheStore(
            AppActorETagManager(
                diskStore = AppActorCacheDiskStore(
                    context,
                    File(context.cacheDir, "tests/offline-customer-${UUID.randomUUID()}")
                ),
                responseVerificationEnabled = false,
            )
        )
    }

    private fun fixtureOfferings(): AppActorOfferingsEnvelopeDTO {
        val payload = requireNotNull(
            javaClass.classLoader?.getResource("fixtures/backend/offerings_android_sample.json")
        ).readText()
        return AppActorBackendJson.instance.decodeFromString(payload)
    }

    private fun fixtureCustomer(path: String): AppActorCustomerEnvelopeDTO {
        val payload = requireNotNull(javaClass.classLoader?.getResource(path)).readText()
        return AppActorBackendJson.instance.decodeFromString(payload)
    }
}
