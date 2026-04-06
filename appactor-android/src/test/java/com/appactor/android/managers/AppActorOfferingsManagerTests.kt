package com.appactor.android.managers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.appactor.android.backend.client.AppActorBackendClient
import com.appactor.android.backend.client.AppActorBackendHttpResponse
import com.appactor.android.backend.client.AppActorBackendJson
import com.appactor.android.backend.dto.AppActorOfferingsEnvelopeDTO
import com.appactor.android.backend.dto.AppActorOfferingsPayloadDTO
import com.appactor.android.billing.AppActorStoreAdapter
import com.appactor.android.billing.AppActorStoreProduct
import com.appactor.android.billing.AppActorStoreProductRequest
import com.appactor.android.cache.AppActorCacheDiskStore
import com.appactor.android.cache.AppActorETagManager
import com.appactor.android.cache.AppActorOfferingsCacheStore
import com.appactor.android.models.AppActorProductType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
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
    fun `get offerings drops packages missing play details`() = runBlocking {
        val dto = fixtureOfferings()
        val mockClient = mockk<AppActorBackendClient>(relaxed = true)
        coEvery { mockClient.getOfferings(any()) } returns freshOfferingsResponse(dto)
        val mockStoreAdapter = mockk<AppActorStoreAdapter>(relaxed = true)
        coEvery { mockStoreAdapter.queryProductDetails(any()) } returns emptyList()
        val manager = AppActorOfferingsManager(
            backendClient = mockClient,
            cacheStore = offeringsCacheStore("offerings-filter"),
            storeAdapter = mockStoreAdapter,
        )

        val offerings = manager.getOfferings()

        assertNull(offerings.current)
        assertEquals(0, offerings.all.size)
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
            storeAdapter = mockStoreAdapter,
        )

        val offerings = manager.getOfferings()
        val resolvedPackage = requireNotNull(offerings.current?.monthly)

        assertEquals(AppActorProductType.Subscription, resolvedPackage.productType)
        assertEquals("com.appactor.pro.monthly", resolvedPackage.productId)
        assertEquals("monthly001", resolvedPackage.basePlanId)
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
            storeAdapter = mockStoreAdapter,
        )

        val error = runCatching { manager.getOfferings() }.exceptionOrNull()

        assertTrue(error is com.appactor.android.models.AppActorError.Unknown)
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
