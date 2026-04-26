package com.appactor.android.models

import com.appactor.android.backend.client.AppActorBackendException
import com.appactor.android.backend.client.toAppActorError
import com.appactor.android.backend.dto.AppActorBackendErrorDTO
import com.appactor.android.billing.AppActorStorePurchase
import com.appactor.android.billing.AppActorStorePurchaseState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppActorBridgeModelTests {

    @Test
    fun `purchase params infer subscription type from subscription fields`() {
        val params = AppActorPurchaseParams(
            productId = "com.appactor.pro.monthly",
            storeProductId = "monthly_plan",
            basePlanId = "monthly001",
            offerId = "intro7d",
            oldPurchaseToken = "old_token_123",
            replacementMode = AppActorSubscriptionReplacementMode.Deferred,
            metadata = mapOf("placement" to "hero"),
        )

        val appActorPackage = params.toAppActorPackage()

        assertEquals("monthly_plan", appActorPackage.id)
        assertEquals(AppActorStore.PlayStore, appActorPackage.store)
        assertEquals(AppActorProductType.Subscription, appActorPackage.productType)
        assertEquals("monthly001", appActorPackage.basePlanId)
        assertEquals("intro7d", appActorPackage.offerId)
        assertEquals("old_token_123", appActorPackage.oldPurchaseToken)
        assertEquals(AppActorSubscriptionReplacementMode.Deferred, appActorPackage.replacementMode)
        assertEquals("hero", appActorPackage.metadata["placement"])
    }

    @Test
    fun `package round trip preserves subscription type without subscription metadata`() {
        val appActorPackage = AppActorPackage(
            id = "monthly",
            store = AppActorStore.PlayStore,
            productId = "com.appactor.pro.monthly",
            storeProductId = "monthly_plan",
            productType = AppActorProductType.Subscription,
        )

        val params = appActorPackage.toPurchaseParams()
        val roundTrippedPackage = params.toAppActorPackage()

        assertEquals(AppActorProductType.Subscription, params.productType)
        assertEquals(AppActorProductType.Subscription, roundTrippedPackage.productType)
        assertEquals("monthly_plan", roundTrippedPackage.storeProductId)
    }

    @Test
    fun `resolved package purchase target uses store product id for billing lookup and matching`() {
        val appActorPackage = AppActorPackage(
            id = "monthly",
            store = AppActorStore.PlayStore,
            productId = "logical_monthly",
            storeProductId = "com.appactor.pro.monthly",
            productType = AppActorProductType.Subscription,
            basePlanId = "monthly001",
        )

        val target = appActorPackage.toResolvedPurchaseTarget("user_android_123")

        assertEquals("com.appactor.pro.monthly", target.request.productId)
        assertTrue(
            target.matches(
                AppActorStorePurchase(
                    productId = "com.appactor.pro.monthly",
                    productType = AppActorProductType.Subscription,
                    purchaseToken = "token_store_lookup",
                    purchaseTimeMillis = 1_710_000_000_000,
                    purchaseState = AppActorStorePurchaseState.Purchased,
                    basePlanId = "monthly001",
                )
            )
        )
    }

    @Test
    fun `resolved direct purchase target uses store product id when provided`() {
        val params = AppActorPurchaseParams(
            productId = "logical_monthly",
            storeProductId = "com.appactor.pro.monthly",
            productType = AppActorProductType.Subscription,
            basePlanId = "monthly001",
        )

        val target = params.toResolvedPurchaseTarget("user_android_123")

        assertEquals("com.appactor.pro.monthly", target.request.productId)
        assertEquals("com.appactor.pro.monthly", target.expectedProductId)
    }

    @Test
    fun `package round trip preserves one time product types`() {
        val appActorPackage = AppActorPackage(
            id = "coins_100",
            store = AppActorStore.PlayStore,
            productId = "com.appactor.coins.100",
            productType = AppActorProductType.Consumable,
            metadata = mapOf("placement" to "store"),
        )

        val params = appActorPackage.toPurchaseParams()
        val roundTrippedPackage = params.toAppActorPackage()

        assertEquals(AppActorProductType.Consumable, params.productType)
        assertEquals(AppActorProductType.Consumable, roundTrippedPackage.productType)
        assertEquals("store", roundTrippedPackage.metadata["placement"])
    }

    @Test
    fun `purchase params default to unknown product type for direct purchase fallback`() {
        val params = AppActorPurchaseParams(productId = "com.appactor.coins.100")

        val appActorPackage = params.toAppActorPackage()

        assertEquals(AppActorProductType.Unknown, appActorPackage.productType)
        assertNull(appActorPackage.basePlanId)
        assertNull(appActorPackage.offerId)
    }

    @Test
    fun `resolved direct purchase target requires explicit product type`() {
        val error = runCatching {
            AppActorPurchaseParams(productId = "com.appactor.coins.100")
                .toResolvedPurchaseTarget("user_android_123")
        }.exceptionOrNull()

        assertTrue(error is AppActorError.InvalidConfiguration)
        assertTrue(error?.message?.contains("Underspecified direct purchase") == true)
    }

    @Test
    fun `resolved direct purchase target rejects one time params with subscription fields`() {
        val error = runCatching {
            AppActorPurchaseParams(
                productId = "com.appactor.coins.100",
                productType = AppActorProductType.Consumable,
                basePlanId = "monthly001",
            ).toResolvedPurchaseTarget("user_android_123")
        }.exceptionOrNull()

        assertTrue(error is AppActorError.InvalidConfiguration)
        assertTrue(error?.message?.contains("Invalid direct one-time purchase params") == true)
    }

    @Test
    fun `bridge errors flatten legacy errors and preserve compatibility mapping`() {
        val bridgeError = AppActorError.Server(
            description = "temporary outage",
            statusCode = 503,
            throwable = IllegalStateException("backend unavailable"),
        ).toBridgeError()

        assertEquals(AppActorBridgeError.CODE_SERVER, bridgeError.code)
        assertEquals("temporary outage", bridgeError.message)
        assertEquals(true, bridgeError.isTransient)
        assertEquals(503, bridgeError.statusCode)
        assertEquals("backend unavailable", bridgeError.debugMessage)

        val legacyError = bridgeError.toAppActorError()
        assertTrue(legacyError is AppActorError.Server)
        legacyError as AppActorError.Server
        assertEquals("temporary outage", legacyError.message)
        assertEquals(503, legacyError.statusCode)
    }

    @Test
    fun `bridge errors expose structured diagnostics fields`() {
        val bridgeError = AppActorError.Server(
            description = "too many requests",
            statusCode = 429,
            scope = "app",
            retryAfterSeconds = 12.5,
            throwable = AppActorBackendException.Http(
                statusCode = 429,
                requestId = "req_123",
                error = AppActorBackendErrorDTO(
                    code = "RATE_LIMIT_EXCEEDED",
                    message = "slow down",
                    details = "app scope",
                    scope = "app",
                ),
                retryAfterSeconds = 12.5,
            ),
        ).toBridgeError()

        assertEquals(AppActorBridgeError.CODE_SERVER, bridgeError.code)
        assertEquals("RATE_LIMIT_EXCEEDED", bridgeError.backendCode)
        assertEquals("req_123", bridgeError.requestId)
        assertEquals("app", bridgeError.scope)
        assertEquals(12.5, bridgeError.retryAfterSeconds)
        assertTrue(bridgeError.debugMessage?.contains("code=RATE_LIMIT_EXCEEDED") == true)
        assertTrue(bridgeError.debugMessage?.contains("requestId=req_123") == true)

        val roundTrip = bridgeError.toAppActorError()
        assertTrue(roundTrip is AppActorError.Server)
        roundTrip as AppActorError.Server
        assertEquals(429, roundTrip.statusCode)
        assertEquals("app", roundTrip.scope)
        assertEquals(12.5, roundTrip.retryAfterSeconds)
    }

    @Test
    fun `customer not found preserves request id through mapper and bridge`() {
        val mapped = AppActorBackendException.CustomerNotFound(
            appUserId = "user_123",
            requestId = "req_404",
        ).toAppActorError()

        assertTrue(mapped is AppActorError.CustomerNotFound)
        mapped as AppActorError.CustomerNotFound
        assertEquals("req_404", mapped.requestId)

        val bridgeError = mapped.toBridgeError()
        assertEquals(AppActorBridgeError.CODE_CUSTOMER_NOT_FOUND, bridgeError.code)
        assertEquals("req_404", bridgeError.requestId)

        val roundTrip = bridgeError.toAppActorError()
        assertTrue(roundTrip is AppActorError.CustomerNotFound)
        roundTrip as AppActorError.CustomerNotFound
        assertEquals("req_404", roundTrip.requestId)
    }

    @Test
    fun `bridge receipt events flatten all pipeline variants with sanitized receipt ids`() {
        val testUserId = "test_user_42"
        val testOrderId = "GPA.1234-5678-9012"
        val postedKey = appActorPublicReceiptId("raw_posted_key")
        val retryKey = appActorPublicReceiptId("raw_retry_key")
        val rejectKey = appActorPublicReceiptId("raw_reject_key")
        val deadKey = appActorPublicReceiptId("raw_dead_key")
        val dupKey = appActorPublicReceiptId("raw_dup_key")
        val events = listOf(
            AppActorReceiptPipelineEvent.PostedOk(
                key = postedKey,
                productId = "monthly",
                requestId = "req_123",
                appUserId = testUserId,
                orderId = testOrderId,
            ),
            AppActorReceiptPipelineEvent.RetryScheduled(
                key = retryKey,
                productId = "monthly",
                retryCount = 3,
                nextRetryAtMillis = 456L,
                errorCode = "RATE_LIMITED",
                appUserId = testUserId,
                orderId = testOrderId,
            ),
            AppActorReceiptPipelineEvent.PermanentlyRejected(
                key = rejectKey,
                productId = "monthly",
                code = "INVALID_RECEIPT",
                message = "invalid payload",
                appUserId = testUserId,
                orderId = testOrderId,
            ),
            AppActorReceiptPipelineEvent.DeadLettered(
                key = deadKey,
                productId = "monthly",
                retryCount = 4,
                lastError = "network timeout",
                appUserId = testUserId,
                orderId = testOrderId,
            ),
            AppActorReceiptPipelineEvent.DuplicateSkipped(
                key = dupKey,
                productId = "monthly",
                appUserId = testUserId,
            ),
        )

        val mapped = events.map { it.toBridgeEvent() }

        // transactionId = orderId for non-duplicate events (matches iOS transactionId pattern)
        assertEquals(AppActorBridgeReceiptEvent.TYPE_POSTED_OK, mapped[0].type)
        assertEquals(testUserId, mapped[0].appUserId)
        assertEquals(testOrderId, mapped[0].transactionId)
        assertNull(mapped[0].key)
        assertEquals(AppActorBridgeReceiptEvent.TYPE_RETRY_SCHEDULED, mapped[1].type)
        assertEquals(testOrderId, mapped[1].transactionId)
        assertEquals(3, mapped[1].retryCount)
        assertTrue(mapped[1].nextAttemptAt != null)
        assertEquals("RATE_LIMITED", mapped[1].errorCode)
        assertEquals(AppActorBridgeReceiptEvent.TYPE_PERMANENTLY_REJECTED, mapped[2].type)
        assertEquals(testOrderId, mapped[2].transactionId)
        assertEquals("INVALID_RECEIPT", mapped[2].errorCode)
        assertNull(mapped[2].key)
        assertEquals(AppActorBridgeReceiptEvent.TYPE_DEAD_LETTERED, mapped[3].type)
        assertEquals(testOrderId, mapped[3].transactionId)
        assertEquals(4, mapped[3].retryCount)
        assertEquals("network timeout", mapped[3].errorCode)
        assertNull(mapped[3].key)
        // DuplicateSkipped: transactionId = null, key = queueKey (matches iOS)
        assertEquals(AppActorBridgeReceiptEvent.TYPE_DUPLICATE_SKIPPED, mapped[4].type)
        assertNull(mapped[4].transactionId)
        assertEquals(dupKey, mapped[4].key)
    }
}
