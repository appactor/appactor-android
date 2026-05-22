package com.appactor.android.backend

import com.appactor.android.backend.client.buildAppActorUrl
import com.appactor.android.backend.client.AppActorBackendJson
import com.appactor.android.backend.dto.AppActorCustomerEnvelopeDTO
import com.appactor.android.backend.dto.AppActorGoogleReceiptRequestDTO
import com.appactor.android.backend.dto.AppActorOfferingDTO
import com.appactor.android.backend.dto.AppActorOfferingsPayloadDTO
import com.appactor.android.backend.dto.AppActorGoogleReceiptPostResult
import com.appactor.android.backend.dto.AppActorGoogleReceiptResponseDTO
import com.appactor.android.backend.dto.AppActorGoogleRestoreResponseDTO
import com.appactor.android.backend.dto.AppActorOfferingsEnvelopeDTO
import com.appactor.android.backend.mappers.toModel
import com.appactor.android.backend.mappers.toResult
import com.appactor.android.models.AppActorPackageType
import com.appactor.android.models.AppActorProductType
import com.appactor.android.models.AppActorStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppActorBackendContractTests {

    @Test
    fun `offerings fixture decodes and maps to public model`() {
        val dto = AppActorBackendJson.instance.decodeFromString<AppActorOfferingsEnvelopeDTO>(
            fixture("fixtures/backend/offerings_android_sample.json")
        )

        val offerings = dto.toModel()
        val current = offerings.current

        assertNotNull(current)
        assertEquals("off_main_android", current?.id)
        assertEquals("main", current?.lookupKey)
        assertEquals("Main", current?.displayName)
        assertEquals(2, current?.packages?.size)
        assertEquals("premium", offerings.productEntitlements["android:com.appactor.pro.monthly:monthly001"]?.first())

        val monthlyPackage = current?.monthly
        assertNotNull(monthlyPackage)
        assertEquals(AppActorPackageType.Monthly, monthlyPackage?.packageType)
        assertEquals(AppActorStore.PlayStore, monthlyPackage?.store)
        assertEquals(AppActorProductType.Subscription, monthlyPackage?.productType)

        val consumablePackage = current?.packages?.get(1)
        assertEquals(AppActorPackageType.Consumable, consumablePackage?.packageType)
        assertEquals(AppActorProductType.Consumable, consumablePackage?.productType)
        assertEquals(100, consumablePackage?.tokenAmount)
    }

    @Test
    fun `current offering is merged into all offerings map when backend omits it from array`() {
        val dto = AppActorOfferingsEnvelopeDTO(
            data = AppActorOfferingsPayloadDTO(
                currentOffering = AppActorOfferingDTO(
                    id = "off_current_only",
                    lookupKey = "main",
                    displayName = "Main",
                    isCurrent = true,
                ),
                offerings = emptyList(),
                productEntitlements = mapOf("android:sku:plan" to listOf("premium")),
            ),
            requestId = "req_merge",
        )

        val offerings = dto.toModel()

        assertEquals("off_current_only", offerings.current?.id)
        assertEquals("off_current_only", offerings.offering("off_current_only")?.id)
        assertEquals("off_current_only", offerings.offeringByLookupKey("main")?.id)
    }

    @Test
    fun `google receipt request encodes price snapshot fields`() {
        val payload = AppActorBackendJson.instance.encodeToString(
            AppActorGoogleReceiptRequestDTO(
                appUserId = "user_android_123",
                packageName = "com.appactor.android",
                environment = "production",
                productId = "com.appactor.pro.monthly",
                productType = "subscription",
                purchaseToken = "token_123",
                purchaseTime = "1710000000000",
                purchaseState = "PURCHASED",
                priceAmountMicros = 4_990_000,
                currency = "USD",
                placement = "paywall_hero",
                sourceIntent = "purchase",
                idempotencyKey = "google:com.appactor.pro.monthly:monthly001:token_123",
            )
        )

        assertTrue(payload.contains("\"priceAmountMicros\":4990000"))
        assertTrue(payload.contains("\"currency\":\"USD\""))
        assertTrue(payload.contains("\"placement\":\"paywall_hero\""))
        assertTrue(payload.contains("\"sourceIntent\":\"purchase\""))
    }

    @Test
    fun `google receipt request omits null placement`() {
        val payload = AppActorBackendJson.instance.encodeToString(
            AppActorGoogleReceiptRequestDTO(
                appUserId = "user_android_123",
                packageName = "com.appactor.android",
                environment = "production",
                productId = "com.appactor.pro.monthly",
                productType = "subscription",
                purchaseToken = "token_123",
                purchaseTime = "1710000000000",
                purchaseState = "PURCHASED",
                sourceIntent = "sync",
                idempotencyKey = "google:com.appactor.pro.monthly:monthly001:token_123",
            )
        )

        assertFalse(payload.contains("\"placement\""))
    }

    @Test
    fun `identify fixture decodes and maps to customer info`() {
        val dto = AppActorBackendJson.instance.decodeFromString<AppActorCustomerEnvelopeDTO>(
            fixture("fixtures/backend/identify_android_sample.json")
        )

        val customerInfo = dto.toModel()

        assertEquals("user_android_123", customerInfo.appUserId)
        assertEquals("req_android_identify_001", customerInfo.requestId)
        assertTrue(customerInfo.hasActiveEntitlement("premium"))
        assertEquals(100, customerInfo.tokenBalance?.total)
        assertEquals(1, customerInfo.subscriptions.size)
        assertEquals(1, customerInfo.nonSubscriptions["com.appactor.coins.100"]?.size)
    }

    @Test
    fun `customer envelope supports legacy data payload shape`() {
        val dto = AppActorBackendJson.instance.decodeFromString<AppActorCustomerEnvelopeDTO>(
            """
            {
              "requestDate": "2026-03-14T12:00:00.000Z",
              "requestId": "req_customer_data_shape",
              "appUserId": "user_android_123",
              "data": {
                "entitlements": {
                  "premium": {
                    "isActive": true,
                    "productId": "com.appactor.pro.monthly",
                    "store": "play_store"
                  }
                },
                "subscriptions": {},
                "nonSubscriptions": {}
              }
            }
            """.trimIndent()
        )

        assertEquals("req_customer_data_shape", dto.requestId)
        assertTrue(dto.customer.entitlements["premium"]?.isActive == true)
    }

    @Test
    fun `receipt fixtures classify correctly`() {
        val ok = AppActorBackendJson.instance.decodeFromString<AppActorGoogleReceiptResponseDTO>(
            fixture("fixtures/backend/google_receipt_ok.json")
        ).toResult()
        val retryable = AppActorBackendJson.instance.decodeFromString<AppActorGoogleReceiptResponseDTO>(
            fixture("fixtures/backend/google_receipt_retryable.json")
        ).toResult()
        val permanent = AppActorBackendJson.instance.decodeFromString<AppActorGoogleReceiptResponseDTO>(
            fixture("fixtures/backend/google_receipt_permanent.json")
        ).toResult()

        assertTrue(ok is AppActorGoogleReceiptPostResult.Success)
        assertTrue(retryable is AppActorGoogleReceiptPostResult.RetryableError)
        assertTrue(permanent is AppActorGoogleReceiptPostResult.PermanentError)

        val okResult = ok as AppActorGoogleReceiptPostResult.Success
        assertTrue(okResult.acknowledgePurchase)
        assertFalse(okResult.consumePurchase)
        assertTrue(okResult.customerInfo?.hasActiveEntitlement("premium") == true)
    }

    @Test
    fun `customer mapping preserves product entitlements when caller supplies them`() {
        val productEntitlements = mapOf(
            "android:com.appactor.pro.monthly:monthly001" to listOf("premium")
        )

        val dto = AppActorBackendJson.instance.decodeFromString<AppActorCustomerEnvelopeDTO>(
            fixture("fixtures/backend/identify_android_sample.json")
        )

        val customerInfo = dto.toModel(productEntitlements = productEntitlements)

        assertEquals(productEntitlements, customerInfo.productEntitlements)
    }

    @Test
    fun `receipt result preserves product entitlements when caller supplies them`() {
        val productEntitlements = mapOf(
            "android:com.appactor.pro.monthly:monthly001" to listOf("premium")
        )

        val result = AppActorBackendJson.instance.decodeFromString<AppActorGoogleReceiptResponseDTO>(
            fixture("fixtures/backend/google_receipt_ok.json")
        ).toResult(productEntitlements = productEntitlements)

        val success = result as AppActorGoogleReceiptPostResult.Success
        assertEquals(productEntitlements, success.customerInfo?.productEntitlements)
    }

    @Test
    fun `restore fixture decodes and maps to restore result`() {
        val dto = AppActorBackendJson.instance.decodeFromString<AppActorGoogleRestoreResponseDTO>(
            fixture("fixtures/backend/google_restore_sample.json")
        )

        val result = dto.toResult()

        assertEquals("req_google_restore_001", result.requestId)
        assertEquals(2, result.restoredCount)
        assertFalse(result.transferred)
        assertTrue(result.customerInfo.hasActiveEntitlement("premium"))
    }

    @Test
    fun `customer url builder encodes app user ids as path segments`() {
        val encodedUrl = buildAppActorUrl(
            baseUrl = "https://api.appactor.com",
            "v1",
            "customers",
            "user/with spaces?and#symbols",
        )

        assertEquals(
            "https://api.appactor.com/v1/customers/user%2Fwith%20spaces%3Fand%23symbols",
            encodedUrl
        )
    }

    private fun fixture(path: String): String {
        return requireNotNull(javaClass.classLoader?.getResource(path)) {
            "Missing fixture: $path"
        }.readText()
    }
}
