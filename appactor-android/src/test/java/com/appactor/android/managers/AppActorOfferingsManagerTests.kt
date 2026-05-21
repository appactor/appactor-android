package com.appactor.android.managers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.appactor.android.backend.client.AppActorBackendClient
import com.appactor.android.backend.client.AppActorBackendHttpResponse
import com.appactor.android.backend.client.AppActorBackendJson
import com.appactor.android.backend.dto.AppActorOfferingDTO
import com.appactor.android.backend.dto.AppActorOfferingsEnvelopeDTO
import com.appactor.android.backend.dto.AppActorOfferingsPayloadDTO
import com.appactor.android.backend.dto.AppActorPackageDTO
import com.appactor.android.billing.AppActorStoreAdapter
import com.appactor.android.billing.AppActorStoreProduct
import com.appactor.android.billing.AppActorStoreProductRequest
import com.appactor.android.cache.AppActorCacheResource
import com.appactor.android.cache.AppActorCacheDiskStore
import com.appactor.android.cache.AppActorETagManager
import com.appactor.android.cache.AppActorOfflineProductCatalog
import com.appactor.android.cache.AppActorOfferingsCacheStore
import com.appactor.android.models.AppActorDiagnosticsDataSource
import com.appactor.android.models.AppActorError
import com.appactor.android.models.AppActorOfferingsFetchPolicy
import com.appactor.android.models.AppActorProductType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Locale
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class AppActorOfferingsManagerTests {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `get offerings enriches backend packages with play pricing`() = runBlocking {
        val dto = fixtureOfferings()
        val mockClient = mockk<AppActorBackendClient>(relaxed = true)
        coEvery { mockClient.getOfferings(any()) } returns freshOfferingsResponse(dto)
        val mockStoreAdapter = mockk<AppActorStoreAdapter>(relaxed = true)
        coEvery { mockStoreAdapter.queryProductDetails(any()) } answers {
            val requests = firstArg<List<AppActorStoreProductRequest>>()
            requests.mapNotNull { request -> pricedProducts()[requestKey(request)] }
        }
        val manager = AppActorOfferingsManager(
            backendClient = mockClient,
            cacheStore = offeringsCacheStore("offerings-enrich"),
            offlineProductCatalogStore = offlineProductCatalogStore("offerings-enrich"),
            storeAdapter = mockStoreAdapter,
        )

        val offerings = manager.getOfferings()

        assertEquals("off_main_android", offerings.current?.id)
        assertEquals(2, offerings.current?.packages?.size)
        assertEquals("$4.99", offerings.current?.monthly?.localizedPriceString)
        assertEquals("$1.99", offerings.current?.packages?.firstOrNull { it.productId == "com.appactor.coins.100" }?.localizedPriceString)
        assertEquals(listOf("premium"), offerings.productEntitlements["android:com.appactor.pro.monthly:monthly001"])
    }

    @Test
    fun `get offerings surfaces store products missing when all play packages fail to resolve`() = runBlocking {
        val dto = fixtureOfferings()
        val mockClient = mockk<AppActorBackendClient>(relaxed = true)
        coEvery { mockClient.getOfferings(any()) } returns freshOfferingsResponse(dto)
        val mockStoreAdapter = mockk<AppActorStoreAdapter>(relaxed = true)
        coEvery { mockStoreAdapter.queryProductDetails(any()) } returns emptyList()
        val manager = AppActorOfferingsManager(
            backendClient = mockClient,
            cacheStore = offeringsCacheStore("offerings-filter"),
            offlineProductCatalogStore = offlineProductCatalogStore("offerings-filter"),
            storeAdapter = mockStoreAdapter,
        )

        val error = runCatching { manager.getOfferings() }.exceptionOrNull()

        assertTrue(error is AppActorError.StoreProductsMissing)
        assertTrue(error?.message?.contains("com.appactor.pro.monthly") == true)
    }

    @Test
    fun `get offerings keeps partial success when at least one package resolves`() = runBlocking {
        val dto = fixtureOfferings()
        val mockClient = mockk<AppActorBackendClient>(relaxed = true)
        coEvery { mockClient.getOfferings(any()) } returns freshOfferingsResponse(dto)
        val mockStoreAdapter = mockk<AppActorStoreAdapter>(relaxed = true)
        coEvery { mockStoreAdapter.queryProductDetails(any()) } answers {
            val requests = firstArg<List<AppActorStoreProductRequest>>()
            requests.mapNotNull { request ->
                pricedProducts()[requestKey(request)]
                    ?.takeIf { it.productId == "com.appactor.pro.monthly" }
            }
        }
        val manager = AppActorOfferingsManager(
            backendClient = mockClient,
            cacheStore = offeringsCacheStore("offerings-partial-success"),
            offlineProductCatalogStore = offlineProductCatalogStore("offerings-partial-success"),
            storeAdapter = mockStoreAdapter,
        )

        val offerings = manager.getOfferings()

        assertNotNull(offerings.current)
        assertEquals(1, offerings.current?.packages?.size)
        assertEquals("com.appactor.pro.monthly", offerings.current?.packages?.single()?.productId)
    }

    @Test
    fun `get offerings keeps empty success when backend has no play product references`() = runBlocking {
        val fixture = fixtureOfferings()
        val currentOffering = requireNotNull(fixture.data.currentOffering)
        val dto = fixture.copy(
            data = fixture.data.copy(
                currentOffering = currentOffering.copy(
                    packages = currentOffering.packages.map { packageDTO ->
                        packageDTO.copy(
                            products = packageDTO.products.map { product ->
                                product.copy(store = "app_store")
                            }
                        )
                    }
                ),
                offerings = fixture.data.offerings.map { offering ->
                    offering.copy(
                        packages = offering.packages.map { packageDTO ->
                            packageDTO.copy(
                                products = packageDTO.products.map { product ->
                                    product.copy(store = "app_store")
                                }
                            )
                        }
                    )
                }
            )
        )
        val mockClient = mockk<AppActorBackendClient>(relaxed = true)
        coEvery { mockClient.getOfferings(any()) } returns freshOfferingsResponse(dto)
        val mockStoreAdapter = mockk<AppActorStoreAdapter>(relaxed = true)
        val manager = AppActorOfferingsManager(
            backendClient = mockClient,
            cacheStore = offeringsCacheStore("offerings-no-play-products"),
            offlineProductCatalogStore = offlineProductCatalogStore("offerings-no-play-products"),
            storeAdapter = mockStoreAdapter,
        )

        val offerings = manager.getOfferings()

        assertNull(offerings.current)
        assertTrue(offerings.all.isEmpty())
    }

    @Test
    fun `get offerings uses cached payload on not modified responses`() = runBlocking {
        val dto = fixtureOfferings()
        val cacheStore = offeringsCacheStore("offerings-304")
        cacheStore.save(
            payload = AppActorBackendJson.instance.encodeToString(dto),
            eTag = "\"etag_123\"",
            verified = true,
        )
        val mockClient = mockk<AppActorBackendClient>(relaxed = true)
        coEvery { mockClient.getOfferings(any()) } returns AppActorBackendHttpResponse(
            body = null,
            statusCode = 304,
            requestId = "req_304",
            eTag = "\"etag_123\"",
            isNotModified = true,
        )
        val mockStoreAdapter = mockk<AppActorStoreAdapter>(relaxed = true)
        coEvery { mockStoreAdapter.queryProductDetails(any()) } answers {
            val requests = firstArg<List<AppActorStoreProductRequest>>()
            requests.mapNotNull { request ->
                val products = mapOf(
                    requestKey("com.appactor.pro.monthly", AppActorProductType.Subscription, "monthly001", "intro7d") to
                        AppActorStoreProduct(
                            productId = "com.appactor.pro.monthly",
                            productType = AppActorProductType.Subscription,
                            basePlanId = "monthly001",
                            offerId = "intro7d",
                            localizedPrice = "$4.99",
                        ),
                    requestKey("com.appactor.coins.100", AppActorProductType.Consumable, null, null) to
                        AppActorStoreProduct(
                            productId = "com.appactor.coins.100",
                            productType = AppActorProductType.Consumable,
                            localizedPrice = "$1.99",
                        ),
                )
                products[requestKey(request)]
            }
        }
        val manager = AppActorOfferingsManager(
            backendClient = mockClient,
            cacheStore = cacheStore,
            offlineProductCatalogStore = offlineProductCatalogStore("offerings-304"),
            storeAdapter = mockStoreAdapter,
        )

        val offerings = manager.getOfferings(forceRefresh = true)

        assertNotNull(offerings.current)
        assertEquals(2, offerings.current?.packages?.size)
    }

    @Test
    fun `get offerings merges current offering when it is not present in offerings list`() = runBlocking {
        val fixture = fixtureOfferings()
        val currentOnly = requireNotNull(fixture.data.currentOffering)
        val dto = AppActorOfferingsEnvelopeDTO(
            data = AppActorOfferingsPayloadDTO(
                currentOffering = currentOnly,
                offerings = emptyList(),
                productEntitlements = fixture.data.productEntitlements,
            ),
            requestId = fixture.requestId,
        )
        val mockClient = mockk<AppActorBackendClient>(relaxed = true)
        coEvery { mockClient.getOfferings(any()) } returns freshOfferingsResponse(dto)
        val mockStoreAdapter = mockk<AppActorStoreAdapter>(relaxed = true)
        coEvery { mockStoreAdapter.queryProductDetails(any()) } answers {
            val requests = firstArg<List<AppActorStoreProductRequest>>()
            requests.mapNotNull { request ->
                val products = mapOf(
                    requestKey("com.appactor.pro.monthly", AppActorProductType.Subscription, "monthly001", "intro7d") to
                        AppActorStoreProduct(
                            productId = "com.appactor.pro.monthly",
                            productType = AppActorProductType.Subscription,
                            basePlanId = "monthly001",
                            offerId = "intro7d",
                            localizedPrice = "$4.99",
                        ),
                    requestKey("com.appactor.coins.100", AppActorProductType.Consumable, null, null) to
                        AppActorStoreProduct(
                            productId = "com.appactor.coins.100",
                            productType = AppActorProductType.Consumable,
                            localizedPrice = "$1.99",
                        ),
                )
                products[requestKey(request)]
            }
        }
        val manager = AppActorOfferingsManager(
            backendClient = mockClient,
            cacheStore = offeringsCacheStore("offerings-current-only"),
            offlineProductCatalogStore = offlineProductCatalogStore("offerings-current-only"),
            storeAdapter = mockStoreAdapter,
        )

        val offerings = manager.getOfferings()

        assertNotNull(offerings.current)
        assertEquals(1, offerings.all.size)
        assertEquals(currentOnly.id, offerings.current?.id)
    }

    @Test
    fun `get offerings prefers play store product reference when package contains mixed stores`() = runBlocking {
        val fixture = fixtureOfferings()
        val monthlyPackage = fixture.data.offerings.first().packages.first().copy(
            products = listOf(
                com.appactor.android.backend.dto.AppActorProductReferenceDTO(
                    id = "ios_product_ref",
                    store = "app_store",
                    productId = "com.appactor.pro.monthly.ios",
                    storeProductId = "com.appactor.pro.monthly.ios",
                    productType = "subscription",
                ),
                com.appactor.android.backend.dto.AppActorProductReferenceDTO(
                    id = "play_product_ref",
                    store = "play_store",
                    productId = "com.appactor.pro.monthly",
                    storeProductId = "com.appactor.pro.monthly",
                    productType = "subscription",
                    basePlanId = "monthly001",
                    offerId = "intro7d",
                ),
            ),
        )
        val dto = fixture.copy(
            data = fixture.data.copy(
                currentOffering = fixture.data.currentOffering?.copy(
                    packages = listOf(monthlyPackage) + fixture.data.currentOffering.packages.drop(1),
                ),
                offerings = fixture.data.offerings.mapIndexed { index, offering ->
                    if (index == 0) {
                        offering.copy(packages = listOf(monthlyPackage) + offering.packages.drop(1))
                    } else {
                        offering
                    }
                }
            )
        )
        val mockClient = mockk<AppActorBackendClient>(relaxed = true)
        coEvery { mockClient.getOfferings(any()) } returns freshOfferingsResponse(dto)
        val mockStoreAdapter = mockk<AppActorStoreAdapter>(relaxed = true)
        coEvery { mockStoreAdapter.queryProductDetails(any()) } answers {
            val requests = firstArg<List<AppActorStoreProductRequest>>()
            requests.mapNotNull { request -> pricedProducts()[requestKey(request)] }
        }
        val manager = AppActorOfferingsManager(
            backendClient = mockClient,
            cacheStore = offeringsCacheStore("offerings-mixed-store"),
            offlineProductCatalogStore = offlineProductCatalogStore("offerings-mixed-store"),
            storeAdapter = mockStoreAdapter,
        )

        val offerings = manager.getOfferings()
        val resolvedPackage = requireNotNull(offerings.current?.monthly)

        assertEquals(AppActorProductType.Subscription, resolvedPackage.productType)
        assertEquals("com.appactor.pro.monthly", resolvedPackage.productId)
        assertEquals("monthly001", resolvedPackage.basePlanId)
    }

    @Test
    fun `get offerings queries play details with store product id while preserving logical product id`() = runBlocking {
        val fixture = fixtureOfferings()
        val monthlyPackage = fixture.data.offerings.first().packages.first().copy(
            products = listOf(
                com.appactor.android.backend.dto.AppActorProductReferenceDTO(
                    id = "play_product_ref",
                    store = "play_store",
                    productId = "logical_monthly",
                    storeProductId = "com.appactor.pro.monthly",
                    productType = "subscription",
                    basePlanId = "monthly001",
                    offerId = "intro7d",
                ),
            ),
        )
        val dto = fixture.copy(
            data = fixture.data.copy(
                currentOffering = fixture.data.currentOffering?.copy(
                    packages = listOf(monthlyPackage) + fixture.data.currentOffering.packages.drop(1),
                ),
                offerings = fixture.data.offerings.mapIndexed { index, offering ->
                    if (index == 0) {
                        offering.copy(packages = listOf(monthlyPackage) + offering.packages.drop(1))
                    } else {
                        offering
                    }
                }
            )
        )
        val capturedRequests = mutableListOf<AppActorStoreProductRequest>()
        val mockClient = mockk<AppActorBackendClient>(relaxed = true)
        coEvery { mockClient.getOfferings(any()) } returns freshOfferingsResponse(dto)
        val mockStoreAdapter = mockk<AppActorStoreAdapter>(relaxed = true)
        coEvery { mockStoreAdapter.queryProductDetails(any()) } answers {
            val requests = firstArg<List<AppActorStoreProductRequest>>()
            capturedRequests += requests
            requests.mapNotNull { request -> pricedProducts()[requestKey(request)] }
        }
        val manager = AppActorOfferingsManager(
            backendClient = mockClient,
            cacheStore = offeringsCacheStore("offerings-store-product-id"),
            offlineProductCatalogStore = offlineProductCatalogStore("offerings-store-product-id"),
            storeAdapter = mockStoreAdapter,
        )

        val offerings = manager.getOfferings()
        val resolvedPackage = requireNotNull(offerings.current?.monthly)

        assertTrue(capturedRequests.any { it.productId == "com.appactor.pro.monthly" })
        assertFalse(capturedRequests.any { it.productId == "logical_monthly" })
        assertEquals("logical_monthly", resolvedPackage.productId)
        assertEquals("com.appactor.pro.monthly", resolvedPackage.storeProductId)
        assertEquals("$4.99", resolvedPackage.localizedPriceString)
    }

    @Test
    fun `get offerings keeps memory cache fresh longer in background mode`() = runBlocking {
        var now = 1_710_000_000_000L
        val mockClient = mockk<AppActorBackendClient>(relaxed = true)
        coEvery { mockClient.getOfferings(any()) } returns freshOfferingsResponse(fixtureOfferings())
        val mockStoreAdapter = mockk<AppActorStoreAdapter>(relaxed = true)
        coEvery { mockStoreAdapter.queryProductDetails(any()) } answers {
            val requests = firstArg<List<AppActorStoreProductRequest>>()
            requests.mapNotNull { request -> pricedProducts()[requestKey(request)] }
        }
        val manager = AppActorOfferingsManager(
            backendClient = mockClient,
            cacheStore = offeringsCacheStore("offerings-background-ttl"),
            offlineProductCatalogStore = offlineProductCatalogStore("offerings-background-ttl"),
            storeAdapter = mockStoreAdapter,
            dateProviderMillis = { now },
        )

        manager.getOfferings()
        manager.setBackground(true)
        now += 6 * 60 * 1_000
        manager.getOfferings()

        coVerify(exactly = 1) { mockClient.getOfferings(any()) }
    }

    @Test
    fun `get offerings does not hide non transient backend errors behind cache fallback`() = runBlocking {
        val dto = fixtureOfferings()
        val cacheStore = offeringsCacheStore("offerings-no-fallback")
        cacheStore.save(
            payload = AppActorBackendJson.instance.encodeToString(dto),
            eTag = "\"etag_123\"",
            verified = true,
        )
        val mockClient = mockk<AppActorBackendClient>(relaxed = true)
        coEvery { mockClient.getOfferings(any()) } throws IllegalStateException("decode bug")
        val mockStoreAdapter = mockk<AppActorStoreAdapter>(relaxed = true)
        coEvery { mockStoreAdapter.queryProductDetails(any()) } answers {
            val requests = firstArg<List<AppActorStoreProductRequest>>()
            requests.mapNotNull { request -> pricedProducts()[requestKey(request)] }
        }
        val manager = AppActorOfferingsManager(
            backendClient = mockClient,
            cacheStore = cacheStore,
            offlineProductCatalogStore = offlineProductCatalogStore("offerings-no-fallback"),
            storeAdapter = mockStoreAdapter,
        )

        val error = runCatching { manager.getOfferings() }.exceptionOrNull()

        assertTrue(error is com.appactor.android.models.AppActorError.Unknown)
    }

    @Test
    fun `cache only rejects disk offerings cache when locale metadata mismatches current locale`() = runBlocking {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("en-US"))
            val cacheStore = offeringsCacheStore("offerings-cache-only-locale-mismatch")
            cacheStore.save(
                payload = AppActorBackendJson.instance.encodeToString(fixtureOfferings()),
                eTag = "\"etag_123\"",
                verified = true,
                preferredLocales = listOf("en-US"),
            )
            val mockClient = mockk<AppActorBackendClient>(relaxed = true)
            val manager = AppActorOfferingsManager(
                backendClient = mockClient,
                cacheStore = cacheStore,
                offlineProductCatalogStore = offlineProductCatalogStore("offerings-cache-only-locale-mismatch"),
                storeAdapter = mockk(relaxed = true),
            )

            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val error = runCatching {
                manager.getOfferings(fetchPolicy = AppActorOfferingsFetchPolicy.CacheOnly)
            }.exceptionOrNull()

            assertTrue(error is AppActorError.InvalidConfiguration)
            assertEquals("Offerings cache miss.", error?.message)
            coVerify(exactly = 0) { mockClient.getOfferings(any()) }
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun `cache only serves legacy raw offerings cache during locale safe upgrade path`() = runBlocking {
        val directory = File(context.cacheDir, "tests/offerings-cache-only-legacy-${UUID.randomUUID()}").apply {
            mkdirs()
        }
        val diskStore = AppActorCacheDiskStore(context, directory)
        val eTagManager = AppActorETagManager(diskStore = diskStore, responseVerificationEnabled = false)
        eTagManager.storeFresh(
            resource = AppActorCacheResource.Offerings,
            payload = AppActorBackendJson.instance.encodeToString(fixtureOfferings()),
            eTag = "\"etag_legacy\"",
            verified = true,
        )
        val cacheStore = AppActorOfferingsCacheStore(eTagManager)
        val mockClient = mockk<AppActorBackendClient>(relaxed = true)
        val mockStoreAdapter = mockk<AppActorStoreAdapter>(relaxed = true)
        coEvery { mockStoreAdapter.queryProductDetails(any()) } answers {
            val requests = firstArg<List<AppActorStoreProductRequest>>()
            requests.mapNotNull { request -> pricedProducts()[requestKey(request)] }
        }
        val manager = AppActorOfferingsManager(
            backendClient = mockClient,
            cacheStore = cacheStore,
            offlineProductCatalogStore = offlineProductCatalogStore("offerings-cache-only-legacy"),
            storeAdapter = mockStoreAdapter,
        )

        val offerings = manager.getOfferings(fetchPolicy = AppActorOfferingsFetchPolicy.CacheOnly)

        assertNotNull(offerings.current)
        assertEquals("off_main_android", offerings.current?.id)
        coVerify(exactly = 0) { mockClient.getOfferings(any()) }
    }

    @Test
    fun `cache only rejects locale mismatched warm memory cache`() = runBlocking {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("en-US"))
            val dto = fixtureOfferings()
            val mockClient = mockk<AppActorBackendClient>(relaxed = true)
            coEvery { mockClient.getOfferings(any()) } returns freshOfferingsResponse(dto)
            val mockStoreAdapter = mockk<AppActorStoreAdapter>(relaxed = true)
            coEvery { mockStoreAdapter.queryProductDetails(any()) } answers {
                val requests = firstArg<List<AppActorStoreProductRequest>>()
                requests.mapNotNull { request -> pricedProducts()[requestKey(request)] }
            }
            val manager = AppActorOfferingsManager(
                backendClient = mockClient,
                cacheStore = offeringsCacheStore("offerings-memory-cache-only-locale-mismatch"),
                offlineProductCatalogStore = offlineProductCatalogStore("offerings-memory-cache-only-locale-mismatch"),
                storeAdapter = mockStoreAdapter,
            )

            manager.getOfferings()

            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val error = runCatching {
                manager.getOfferings(fetchPolicy = AppActorOfferingsFetchPolicy.CacheOnly)
            }.exceptionOrNull()

            assertTrue(error is AppActorError.InvalidConfiguration)
            assertEquals("Offerings cache miss.", error?.message)
            coVerify(exactly = 1) { mockClient.getOfferings(any()) }
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun `locale mismatched disk cache does not participate in refresh etag flow`() = runBlocking {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("en-US"))
            val dto = fixtureOfferings()
            val cacheStore = offeringsCacheStore("offerings-refresh-locale-mismatch")
            cacheStore.save(
                payload = AppActorBackendJson.instance.encodeToString(dto),
                eTag = "\"etag_123\"",
                verified = true,
                preferredLocales = listOf("en-US"),
            )
            val mockClient = mockk<AppActorBackendClient>(relaxed = true)
            coEvery { mockClient.getOfferings(any()) } returns freshOfferingsResponse(dto)
            val mockStoreAdapter = mockk<AppActorStoreAdapter>(relaxed = true)
            coEvery { mockStoreAdapter.queryProductDetails(any()) } answers {
                val requests = firstArg<List<AppActorStoreProductRequest>>()
                requests.mapNotNull { request -> pricedProducts()[requestKey(request)] }
            }
            val manager = AppActorOfferingsManager(
                backendClient = mockClient,
                cacheStore = cacheStore,
                offlineProductCatalogStore = offlineProductCatalogStore("offerings-refresh-locale-mismatch"),
                storeAdapter = mockStoreAdapter,
            )

            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            manager.getOfferings()

            coVerify(exactly = 1) { mockClient.getOfferings(null) }
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun `return cached then refresh does not reuse locale mismatched warm memory cache`() = runBlocking {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("en-US"))
            val dto = fixtureOfferings()
            val mockClient = mockk<AppActorBackendClient>(relaxed = true)
            coEvery { mockClient.getOfferings(any()) } returns freshOfferingsResponse(dto)
            val mockStoreAdapter = mockk<AppActorStoreAdapter>(relaxed = true)
            coEvery { mockStoreAdapter.queryProductDetails(any()) } answers {
                val requests = firstArg<List<AppActorStoreProductRequest>>()
                requests.mapNotNull { request -> pricedProducts()[requestKey(request)] }
            }
            val manager = AppActorOfferingsManager(
                backendClient = mockClient,
                cacheStore = offeringsCacheStore("offerings-memory-refresh-locale-mismatch"),
                offlineProductCatalogStore = offlineProductCatalogStore("offerings-memory-refresh-locale-mismatch"),
                storeAdapter = mockStoreAdapter,
            )

            manager.getOfferings()

            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            manager.getOfferings(fetchPolicy = AppActorOfferingsFetchPolicy.ReturnCachedThenRefresh)

            coVerify(exactly = 2) { mockClient.getOfferings(null) }
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun `current product entitlements prefers cached offerings payload before stale offline catalog`() = runBlocking {
        val cacheStore = offeringsCacheStore("offerings-entitlement-precedence")
        val offlineCatalogStore = offlineProductCatalogStore("offerings-entitlement-precedence")
        val dto = fixtureOfferings().copy(
            data = fixtureOfferings().data.copy(
                productEntitlements = mapOf(
                    "android:com.appactor.pro.monthly:monthly001" to listOf("fresh_premium")
                )
            )
        )
        cacheStore.save(
            payload = AppActorBackendJson.instance.encodeToString(dto),
            eTag = "\"etag_123\"",
            verified = true,
        )
        offlineCatalogStore.save(
            AppActorOfflineProductCatalog(
                productEntitlements = mapOf(
                    "android:com.appactor.pro.monthly:monthly001" to listOf("stale_premium")
                )
            )
        )
        val manager = AppActorOfferingsManager(
            backendClient = mockk(relaxed = true),
            cacheStore = cacheStore,
            offlineProductCatalogStore = offlineCatalogStore,
            storeAdapter = mockk(relaxed = true),
        )

        val entitlements = manager.currentProductEntitlements()

        assertEquals(
            listOf("fresh_premium"),
            entitlements["android:com.appactor.pro.monthly:monthly001"]
        )
    }

    @Test
    fun `current one time product type prefers cached offerings payload before stale offline catalog`() = runBlocking {
        val cacheStore = offeringsCacheStore("offerings-one-time-type-precedence")
        val offlineCatalogStore = offlineProductCatalogStore("offerings-one-time-type-precedence")
        val dto = fixtureOfferingsWithLogicalCoinProductId()
        cacheStore.save(
            payload = AppActorBackendJson.instance.encodeToString(dto),
            eTag = "\"etag_123\"",
            verified = true,
        )
        offlineCatalogStore.save(
            AppActorOfflineProductCatalog(
                oneTimeProductKinds = mapOf(
                    AppActorOfflineProductCatalog.oneTimeKey("com.appactor.coins.100") to
                        AppActorProductType.NonConsumable.wireValue
                )
            )
        )
        val manager = AppActorOfferingsManager(
            backendClient = mockk(relaxed = true),
            cacheStore = cacheStore,
            offlineProductCatalogStore = offlineCatalogStore,
            storeAdapter = mockk(relaxed = true),
        )

        val productType = manager.currentOneTimeProductType("com.appactor.coins.100")

        assertEquals(AppActorProductType.Consumable, productType)
    }

    @Test
    fun `current one time product type uses store product id from enriched offerings`() = runBlocking {
        val dto = fixtureOfferingsWithLogicalCoinProductId()
        val mockClient = mockk<AppActorBackendClient>(relaxed = true)
        coEvery { mockClient.getOfferings(any()) } returns freshOfferingsResponse(dto)
        val mockStoreAdapter = mockk<AppActorStoreAdapter>(relaxed = true)
        coEvery { mockStoreAdapter.queryProductDetails(any()) } answers {
            val requests = firstArg<List<AppActorStoreProductRequest>>()
            requests.mapNotNull { request -> pricedProducts()[requestKey(request)] }
        }
        val manager = AppActorOfferingsManager(
            backendClient = mockClient,
            cacheStore = offeringsCacheStore("offerings-one-time-store-id"),
            offlineProductCatalogStore = offlineProductCatalogStore("offerings-one-time-store-id"),
            storeAdapter = mockStoreAdapter,
        )

        manager.getOfferings()

        assertEquals(
            AppActorProductType.Consumable,
            manager.currentOneTimeProductType("com.appactor.coins.100"),
        )
        assertNull(manager.currentOneTimeProductType("logical_coin_pack_100"))
    }

    @Test
    fun `current one time product type falls back to persisted offline catalog when offerings cache is unavailable`() = runBlocking {
        val offlineCatalogStore = offlineProductCatalogStore("offerings-one-time-type-offline-fallback")
        offlineCatalogStore.save(
            AppActorOfflineProductCatalog(
                oneTimeProductKinds = mapOf(
                    AppActorOfflineProductCatalog.oneTimeKey("com.appactor.coins.100") to
                        AppActorProductType.Consumable.wireValue
                )
            )
        )
        val manager = AppActorOfferingsManager(
            backendClient = mockk(relaxed = true),
            cacheStore = offeringsCacheStore("offerings-one-time-type-offline-fallback"),
            offlineProductCatalogStore = offlineCatalogStore,
            storeAdapter = mockk(relaxed = true),
        )

        val productType = manager.currentOneTimeProductType("com.appactor.coins.100")

        assertEquals(AppActorProductType.Consumable, productType)
    }

    @Test
    fun `bootstrap prefetch seeds offline catalog before store enrichment completes`() = runBlocking {
        val dto = fixtureOfferings()
        val mockClient = mockk<AppActorBackendClient>(relaxed = true)
        coEvery { mockClient.getOfferings(any()) } returns freshOfferingsResponse(dto)
        val queryStarted = CompletableDeferred<Unit>()
        val releaseQuery = CompletableDeferred<Unit>()
        val mockStoreAdapter = mockk<AppActorStoreAdapter>(relaxed = true)
        coEvery { mockStoreAdapter.queryProductDetails(any()) } coAnswers {
            queryStarted.complete(Unit)
            releaseQuery.await()
            val requests = firstArg<List<AppActorStoreProductRequest>>()
            requests.mapNotNull { request -> pricedProducts()[requestKey(request)] }
        }
        val manager = AppActorOfferingsManager(
            backendClient = mockClient,
            cacheStore = offeringsCacheStore("offerings-bootstrap-prefetch"),
            offlineProductCatalogStore = offlineProductCatalogStore("offerings-bootstrap-prefetch"),
            storeAdapter = mockStoreAdapter,
        )

        val source = manager.prefetchForBootstrap()
        queryStarted.await()

        assertEquals(AppActorDiagnosticsDataSource.Network, source)
        assertEquals(
            listOf("premium"),
            manager.currentProductEntitlements()["android:com.appactor.pro.monthly:monthly001"]
        )
        assertNull(manager.cached())

        val earlyLookup = async(Dispatchers.Default) {
            manager.getOfferings(fetchPolicy = AppActorOfferingsFetchPolicy.ReturnCachedThenRefresh)
        }
        assertFalse(earlyLookup.isCompleted)

        releaseQuery.complete(Unit)
        val offerings = earlyLookup.await()

        assertEquals("off_main_android", offerings.current?.id)
        assertNotNull(manager.cached())
    }

    @Test
    fun `clear cache prevents stale in flight enrich from repopulating offline product catalog`() = runBlocking {
        val dto = fixtureOfferings()
        val mockClient = mockk<AppActorBackendClient>(relaxed = true)
        coEvery { mockClient.getOfferings(any()) } returns freshOfferingsResponse(dto)
        val queryStarted = CompletableDeferred<Unit>()
        val releaseQuery = CompletableDeferred<Unit>()
        val mockStoreAdapter = mockk<AppActorStoreAdapter>(relaxed = true)
        coEvery { mockStoreAdapter.queryProductDetails(any()) } coAnswers {
            queryStarted.complete(Unit)
            releaseQuery.await()
            val requests = firstArg<List<AppActorStoreProductRequest>>()
            requests.mapNotNull { request -> pricedProducts()[requestKey(request)] }
        }
        val offlineCatalogStore = offlineProductCatalogStore("offerings-clear-race")
        val manager = AppActorOfferingsManager(
            backendClient = mockClient,
            cacheStore = offeringsCacheStore("offerings-clear-race"),
            offlineProductCatalogStore = offlineCatalogStore,
            storeAdapter = mockStoreAdapter,
        )

        val fetch = async(Dispatchers.Default) { manager.getOfferings() }
        queryStarted.await()
        manager.clearCache()
        releaseQuery.complete(Unit)
        fetch.await()

        assertNull(offlineCatalogStore.load())
        assertTrue(manager.currentProductEntitlements().isEmpty())
    }

    private fun freshOfferingsResponse(dto: AppActorOfferingsEnvelopeDTO): AppActorBackendHttpResponse<AppActorOfferingsEnvelopeDTO> {
        return AppActorBackendHttpResponse(
            body = dto,
            statusCode = 200,
            requestId = dto.requestId,
            eTag = "\"etag_123\"",
            isNotModified = false,
            signatureVerified = true,
        )
    }

    private fun fixtureOfferings(): AppActorOfferingsEnvelopeDTO {
        val payload = requireNotNull(
            javaClass.classLoader?.getResource("fixtures/backend/offerings_android_sample.json")
        ).readText()
        return AppActorBackendJson.instance.decodeFromString(payload)
    }

    private fun fixtureOfferingsWithLogicalCoinProductId(): AppActorOfferingsEnvelopeDTO {
        val fixture = fixtureOfferings()

        fun rewritePackage(packageDTO: AppActorPackageDTO): AppActorPackageDTO {
            if (packageDTO.id != "pkg_coin_pack_100") return packageDTO
            return packageDTO.copy(
                products = packageDTO.products.map { productRef ->
                    if (productRef.id == "prod_google_coins_100") {
                        productRef.copy(
                            productId = "logical_coin_pack_100",
                            storeProductId = "com.appactor.coins.100",
                        )
                    } else {
                        productRef
                    }
                }
            )
        }

        fun rewriteOffering(offeringDTO: AppActorOfferingDTO): AppActorOfferingDTO {
            return offeringDTO.copy(packages = offeringDTO.packages.map(::rewritePackage))
        }

        return fixture.copy(
            data = fixture.data.copy(
                currentOffering = fixture.data.currentOffering?.let(::rewriteOffering),
                offerings = fixture.data.offerings.map(::rewriteOffering),
                productEntitlements = mapOf(
                    "android:com.appactor.pro.monthly:monthly001" to listOf("premium"),
                    "android:com.appactor.coins.100" to listOf("coin_pack_100"),
                ),
            )
        )
    }

    private fun offeringsCacheStore(name: String): AppActorOfferingsCacheStore {
        val directory = File(context.cacheDir, "tests/$name-${UUID.randomUUID()}")
        directory.mkdirs()
        val diskStore = AppActorCacheDiskStore(context, directory)
        return AppActorOfferingsCacheStore(
            AppActorETagManager(
                diskStore = diskStore,
                responseVerificationEnabled = false,
            )
        )
    }

    private fun offlineProductCatalogStore(name: String): com.appactor.android.cache.AppActorOfflineProductCatalogStore {
        val directory = File(context.cacheDir, "tests/$name-offline-catalog-${UUID.randomUUID()}")
        directory.mkdirs()
        val diskStore = AppActorCacheDiskStore(context, directory)
        return com.appactor.android.cache.AppActorOfflineProductCatalogStore(
            AppActorETagManager(
                diskStore = diskStore,
                responseVerificationEnabled = false,
            )
        )
    }

    private fun requestKey(
        productId: String,
        productType: AppActorProductType,
        basePlanId: String?,
        offerId: String?,
    ): String {
        return listOf(productType.name, productId, basePlanId.orEmpty(), offerId.orEmpty()).joinToString("|")
    }

    private fun requestKey(request: AppActorStoreProductRequest): String {
        return listOf(
            request.productType.name,
            request.productId,
            request.basePlanId.orEmpty(),
            request.offerId.orEmpty(),
        ).joinToString("|")
    }

    private fun pricedProducts(): Map<String, AppActorStoreProduct> {
        return mapOf(
            requestKey("com.appactor.pro.monthly", AppActorProductType.Subscription, "monthly001", "intro7d") to
                AppActorStoreProduct(
                    productId = "com.appactor.pro.monthly",
                    productType = AppActorProductType.Subscription,
                    basePlanId = "monthly001",
                    offerId = "intro7d",
                    localizedPrice = "$4.99",
                    priceAmountMicros = 4_990_000,
                    currencyCode = "USD",
                    displayName = "AppActor Pro Monthly",
                    description = "Premium monthly plan",
                ),
            requestKey("com.appactor.coins.100", AppActorProductType.Consumable, null, null) to
                AppActorStoreProduct(
                    productId = "com.appactor.coins.100",
                    productType = AppActorProductType.Consumable,
                    localizedPrice = "$1.99",
                    priceAmountMicros = 1_990_000,
                    currencyCode = "USD",
                    displayName = "100 Coins",
                    description = "Coin pack",
                ),
        )
    }
}
