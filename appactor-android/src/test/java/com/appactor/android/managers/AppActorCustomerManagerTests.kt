package com.appactor.android.managers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.appactor.android.backend.client.AppActorBackendClient
import com.appactor.android.backend.client.AppActorBackendHttpResponse
import com.appactor.android.backend.client.AppActorBackendJson
import com.appactor.android.backend.dto.AppActorCustomerEnvelopeDTO
import com.appactor.android.backend.dto.AppActorIdentifyRequestDTO
import com.appactor.android.backend.dto.AppActorLoginRequestDTO
import com.appactor.android.backend.dto.AppActorLoginResponseDTO
import com.appactor.android.backend.dto.AppActorLogoutRequestDTO
import com.appactor.android.backend.dto.AppActorLogoutResponseDTO
import com.appactor.android.backend.dto.AppActorOfferingsEnvelopeDTO
import com.appactor.android.billing.AppActorStoreAdapter
import com.appactor.android.billing.AppActorStoreProduct
import com.appactor.android.billing.AppActorStoreProductRequest
import com.appactor.android.billing.AppActorStorePurchase
import com.appactor.android.cache.AppActorCacheDiskStore
import com.appactor.android.cache.AppActorCustomerCacheStore
import com.appactor.android.cache.AppActorETagManager
import com.appactor.android.cache.AppActorOfferingsCacheStore
import com.appactor.android.internal.AppActorSDK
import com.appactor.android.models.AppActorConfiguration
import com.appactor.android.models.AppActorEnvironment
import com.appactor.android.models.AppActorProductType
import com.appactor.android.storage.AppActorIdentityStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class AppActorCustomerManagerTests {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `identify seeds cache and updates identity`() = runBlocking {
        val identifyRequestSlot = slot<AppActorIdentifyRequestDTO>()
        val mockClient = mockk<AppActorBackendClient>(relaxed = true)
        coEvery { mockClient.getOfferings(any()) } returns freshOfferingsResponse(fixtureOfferings())
        coEvery { mockClient.identify(capture(identifyRequestSlot)) } returns freshCustomerResponse(
            fixtureCustomer("fixtures/backend/customer_android_active.json")
        )
        val manager = createCustomerManager(mockClient)

        val info = manager.identify()

        assertEquals("user_android_123", info.appUserId)
        assertEquals("user_android_123", identifyRequestSlot.captured.appUserId)
        assertEquals("user_android_123", manager.cachedInfo("user_android_123")?.appUserId)
    }

    @Test
    fun `identify sends sdk version telemetry`() = runBlocking {
        val identifyRequestSlot = slot<AppActorIdentifyRequestDTO>()
        val mockClient = mockk<AppActorBackendClient>(relaxed = true)
        coEvery { mockClient.getOfferings(any()) } returns freshOfferingsResponse(fixtureOfferings())
        coEvery { mockClient.identify(capture(identifyRequestSlot)) } returns freshCustomerResponse(
            fixtureCustomer("fixtures/backend/customer_android_active.json")
        )
        val manager = createCustomerManager(mockClient)

        manager.identify()

        assertEquals(AppActorSDK.version, identifyRequestSlot.captured.sdkVersion)
    }

    @Test
    fun `get customer info returns fresh cache without network call`() = runBlocking {
        val mockClient = mockk<AppActorBackendClient>(relaxed = true)
        coEvery { mockClient.getOfferings(any()) } returns freshOfferingsResponse(fixtureOfferings())
        coEvery { mockClient.identify(any()) } returns freshCustomerResponse(
            fixtureCustomer("fixtures/backend/customer_android_active.json")
        )
        val manager = createCustomerManager(mockClient)
        manager.identify()

        val info = manager.getCustomerInfo("user_android_123")

        assertTrue(info.hasActiveEntitlement("premium"))
        coVerify(exactly = 0) { mockClient.getCustomer(any(), any()) }
    }

    @Test
    fun `active entitlement keys offline derives from active play purchases and offerings mapping`() = runBlocking {
        val mockClient = mockk<AppActorBackendClient>(relaxed = true)
        coEvery { mockClient.getOfferings(any()) } returns freshOfferingsResponse(fixtureOfferings())
        coEvery { mockClient.identify(any()) } returns freshCustomerResponse(
            fixtureCustomer("fixtures/backend/customer_android_active.json")
        )
        val mockStoreAdapter = mockk<AppActorStoreAdapter>(relaxed = true)
        coEvery { mockStoreAdapter.queryProductDetails(any()) } answers {
            val requests = firstArg<List<AppActorStoreProductRequest>>()
            requests.map { request ->
                AppActorStoreProduct(
                    productId = request.productId,
                    productType = request.productType,
                    basePlanId = request.basePlanId,
                    offerId = request.offerId,
                    localizedPrice = if (request.productType == AppActorProductType.Subscription) "$4.99" else "$1.99",
                )
            }
        }
        coEvery { mockStoreAdapter.queryActivePurchases() } returns listOf(
            AppActorStorePurchase(
                productId = "com.appactor.pro.monthly",
                productType = AppActorProductType.Subscription,
                purchaseToken = "token_123",
                orderId = "GPA.1234",
                purchaseTimeMillis = 1_710_000_000_000,
                purchaseState = com.appactor.android.billing.AppActorStorePurchaseState.Purchased,
                basePlanId = "monthly001",
                offerId = "intro7d",
                isAcknowledged = true,
                isAutoRenewing = true,
            )
        )
        val manager = createCustomerManager(mockClient, mockStoreAdapter)
        manager.identify()

        val keys = manager.activeEntitlementKeysOffline("user_android_123")

        assertEquals(setOf("premium"), keys)
    }

    @Test
    fun `get customer info on 304 returns cached payload and refresh request id`() = runBlocking {
        val cachedEnvelope = fixtureCustomer("fixtures/backend/customer_android_active.json")
        val mockClient = mockk<AppActorBackendClient>(relaxed = true)
        coEvery { mockClient.getOfferings(any()) } returns freshOfferingsResponse(fixtureOfferings())
        coEvery { mockClient.identify(any()) } returns freshCustomerResponse(cachedEnvelope)
        coEvery { mockClient.getCustomer(any(), any()) } returns AppActorBackendHttpResponse(
            body = null,
            statusCode = 304,
            requestId = "req_customer_304",
            eTag = "\"etag_customer\"",
            isNotModified = true,
        )
        val manager = createCustomerManager(mockClient)
        manager.identify()

        val info = manager.getCustomerInfo("user_android_123", forceRefresh = true)

        assertEquals("req_customer_304", info.requestId)
        assertTrue(info.hasActiveEntitlement("premium"))
    }

    @Test
    fun `force refresh does not fall back to stale cache on network error`() = runBlocking {
        var now = 1_710_000_000_000L
        val mockClient = mockk<AppActorBackendClient>(relaxed = true)
        coEvery { mockClient.getOfferings(any()) } returns freshOfferingsResponse(fixtureOfferings())
        coEvery { mockClient.identify(any()) } returns freshCustomerResponse(
            fixtureCustomer("fixtures/backend/customer_android_active.json")
        )
        coEvery { mockClient.getCustomer(any(), any()) } throws java.io.IOException("offline")
        val manager = createCustomerManager(
            backendClient = mockClient,
            dateProviderMillis = { now },
        )
        manager.identify()
        now += 10 * 60 * 1_000

        val error = runCatching {
            manager.getCustomerInfo("user_android_123", forceRefresh = true)
        }.exceptionOrNull()

        assertTrue(error is java.io.IOException)
    }

    @Test
    fun `login uses backend login response and updates identity state`() = runBlocking {
        val loginRequestSlot = slot<AppActorLoginRequestDTO>()
        val mockClient = mockk<AppActorBackendClient>(relaxed = true)
        coEvery { mockClient.getOfferings(any()) } returns freshOfferingsResponse(fixtureOfferings())
        coEvery { mockClient.identify(any()) } returns freshCustomerResponse(
            fixtureCustomer("fixtures/backend/customer_android_active.json")
        )
        coEvery { mockClient.login(capture(loginRequestSlot)) } returns AppActorBackendHttpResponse(
            body = AppActorLoginResponseDTO(
                requestId = "req_login_001",
                appUserId = "user_android_456",
                serverUserId = "server_user_456",
                customer = fixtureCustomer("fixtures/backend/customer_android_active.json").customer,
            ),
            statusCode = 200,
            requestId = "req_login_001",
            eTag = "\"etag_login\"",
            signatureVerified = true,
        )
        val manager = createCustomerManager(mockClient)

        val info = manager.logIn(
            currentAppUserId = "user_android_123",
            newAppUserId = "user_android_456",
        )

        assertEquals("user_android_123", loginRequestSlot.captured.currentAppUserId)
        assertEquals("user_android_456", loginRequestSlot.captured.newAppUserId)
        assertEquals("user_android_456", info.appUserId)
        assertEquals("user_android_456", manager.cachedInfo("user_android_456")?.appUserId)
    }

    @Test
    fun `logout posts dedicated backend request and returns success flag`() = runBlocking {
        val logoutRequestSlot = slot<AppActorLogoutRequestDTO>()
        val mockClient = mockk<AppActorBackendClient>(relaxed = true)
        coEvery { mockClient.getOfferings(any()) } returns freshOfferingsResponse(fixtureOfferings())
        coEvery { mockClient.identify(any()) } returns freshCustomerResponse(
            fixtureCustomer("fixtures/backend/customer_android_active.json")
        )
        coEvery { mockClient.logout(capture(logoutRequestSlot)) } returns AppActorBackendHttpResponse(
            body = AppActorLogoutResponseDTO(
                requestId = "req_logout_001",
                success = true,
            ),
            statusCode = 200,
            requestId = "req_logout_001",
            signatureVerified = true,
        )
        val manager = createCustomerManager(mockClient)

        val acknowledged = manager.logOut("user_android_123")

        assertTrue(acknowledged)
        assertEquals("user_android_123", logoutRequestSlot.captured.appUserId)
    }

    @Test
    fun `customer cache freshness reflects foreground ttl`() = runBlocking {
        val mockClient = mockk<AppActorBackendClient>(relaxed = true)
        coEvery { mockClient.getOfferings(any()) } returns freshOfferingsResponse(fixtureOfferings())
        coEvery { mockClient.identify(any()) } returns freshCustomerResponse(
            fixtureCustomer("fixtures/backend/customer_android_active.json")
        )
        val manager = createCustomerManager(backendClient = mockClient)
        manager.identify()

        assertTrue(manager.isCustomerCacheFresh("user_android_123"))
        assertFalse(manager.isCustomerCacheFresh("missing_user"))
    }

    private fun createCustomerManager(
        backendClient: AppActorBackendClient,
        storeAdapter: AppActorStoreAdapter = mockk<AppActorStoreAdapter>(relaxed = true).also { mock ->
            coEvery { mock.queryProductDetails(any()) } answers {
                val requests = firstArg<List<AppActorStoreProductRequest>>()
                requests.map { request ->
                    AppActorStoreProduct(
                        productId = request.productId,
                        productType = request.productType,
                        basePlanId = request.basePlanId,
                        offerId = request.offerId,
                        localizedPrice = if (request.productType == AppActorProductType.Subscription) "$4.99" else "$1.99",
                    )
                }
            }
            coEvery { mock.queryActivePurchases() } returns emptyList()
        },
        dateProviderMillis: () -> Long = { System.currentTimeMillis() },
    ): AppActorCustomerManager {
        val identityStore = mockk<AppActorIdentityStore>().also { store ->
            var storedAppUserId: String? = "user_android_123"
            var storedServerUserId: String? = null
            var storedLastRequestId: String? = null

            every { store.currentAppUserId } answers { storedAppUserId }
            every { store.installId } returns "install_123"
            every { store.serverUserId } answers { storedServerUserId }
            every { store.lastRequestId } answers { storedLastRequestId }
            every { store.installReferrer } returns null
            every { store.ensureAppUserId() } answers {
                storedAppUserId ?: "user_android_123".also { storedAppUserId = it }
            }
            every { store.setAppUserId(any()) } answers { storedAppUserId = firstArg() }
            every { store.setServerUserId(any()) } answers { storedServerUserId = firstArg() }
            every { store.setLastRequestId(any()) } answers { storedLastRequestId = firstArg() }
            every { store.setInstallReferrer(any()) } answers { }
            every { store.clearIdentity() } answers {
                storedAppUserId = null
                storedServerUserId = null
                storedLastRequestId = null
            }
        }

        val offeringsManager = AppActorOfferingsManager(
            backendClient = backendClient,
            cacheStore = AppActorOfferingsCacheStore(
                AppActorETagManager(
                    diskStore = AppActorCacheDiskStore(
                        context,
                        File(context.cacheDir, "tests/off-${UUID.randomUUID()}")
                    ),
                    responseVerificationEnabled = false,
                )
            ),
            storeAdapter = storeAdapter,
        )
        runBlocking { offeringsManager.getOfferings() }
        return AppActorCustomerManager(
            configuration = AppActorConfiguration(
                context = context,
                apiKey = "pk_test_123",
                appUserId = "user_android_123",
                environment = AppActorEnvironment.Production,
            ),
            backendClient = backendClient,
            cacheStore = AppActorCustomerCacheStore(
                AppActorETagManager(
                    diskStore = AppActorCacheDiskStore(
                        context,
                        File(context.cacheDir, "tests/customer-${UUID.randomUUID()}")
                    ),
                    responseVerificationEnabled = false,
                )
            ),
            identityStore = identityStore,
            offeringsManager = offeringsManager,
            storeAdapter = storeAdapter,
            dateProviderMillis = dateProviderMillis,
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

    private fun freshOfferingsResponse(dto: AppActorOfferingsEnvelopeDTO): AppActorBackendHttpResponse<AppActorOfferingsEnvelopeDTO> {
        return AppActorBackendHttpResponse(
            body = dto,
            statusCode = 200,
            requestId = dto.requestId,
            eTag = "\"etag_123\"",
            signatureVerified = true,
        )
    }

    private fun freshCustomerResponse(dto: AppActorCustomerEnvelopeDTO): AppActorBackendHttpResponse<AppActorCustomerEnvelopeDTO> {
        return AppActorBackendHttpResponse(
            body = dto,
            statusCode = 200,
            requestId = dto.requestId,
            eTag = "\"etag_customer\"",
            signatureVerified = true,
        )
    }
}
