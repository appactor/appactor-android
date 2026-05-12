package com.appactor.android.pipeline

import android.app.Activity
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.appactor.android.backend.client.AppActorBackendClient
import com.appactor.android.backend.client.AppActorBackendHttpResponse
import com.appactor.android.backend.client.AppActorBackendJson
import com.appactor.android.backend.dto.AppActorCustomerEnvelopeDTO
import com.appactor.android.backend.dto.AppActorGoogleBatchResultDTO
import com.appactor.android.backend.dto.AppActorGoogleReceiptRequestDTO
import com.appactor.android.backend.dto.AppActorGoogleReceiptResponseDTO
import com.appactor.android.backend.dto.AppActorGoogleRestoreRequestDTO
import com.appactor.android.backend.dto.AppActorGoogleRestoreResponseDTO
import com.appactor.android.backend.dto.AppActorGoogleSyncRequestDTO
import com.appactor.android.backend.dto.AppActorGoogleSyncResponseDTO
import com.appactor.android.backend.dto.AppActorIdentifyRequestDTO
import com.appactor.android.backend.dto.AppActorLoginRequestDTO
import com.appactor.android.backend.dto.AppActorLoginResponseDTO
import com.appactor.android.backend.dto.AppActorOfferingDTO
import com.appactor.android.backend.dto.AppActorOfferingsEnvelopeDTO
import com.appactor.android.billing.AppActorStoreAdapter
import com.appactor.android.billing.AppActorStoreProduct
import com.appactor.android.billing.AppActorStoreProductRequest
import com.appactor.android.billing.AppActorStorePurchase
import com.appactor.android.billing.AppActorStorePurchaseLaunchResult
import com.appactor.android.cache.AppActorCacheDiskStore
import com.appactor.android.cache.AppActorCustomerCacheStore
import com.appactor.android.cache.AppActorETagManager
import com.appactor.android.cache.AppActorOfflineProductCatalogStore
import com.appactor.android.cache.AppActorOfferingsCacheStore
import com.appactor.android.managers.AppActorCustomerManager
import com.appactor.android.managers.AppActorOfferingsManager
import com.appactor.android.models.AppActorConfiguration
import com.appactor.android.models.AppActorEnvironment
import com.appactor.android.models.AppActorError
import com.appactor.android.models.AppActorPackage
import com.appactor.android.models.AppActorPackageType
import com.appactor.android.models.AppActorProductType
import com.appactor.android.models.AppActorPurchaseParams
import com.appactor.android.models.AppActorPurchaseResult
import com.appactor.android.models.AppActorReceiptPipelineEvent
import com.appactor.android.models.AppActorStore
import com.appactor.android.models.AppActorStorefront
import com.appactor.android.models.AppActorSubscriptionReplacementMode
import com.appactor.android.models.appActorGoogleObfuscatedAccountId
import com.appactor.android.storage.AppActorAtomicJsonPostedLedgerStore
import com.appactor.android.storage.AppActorAtomicJsonReceiptQueueStore
import com.appactor.android.storage.AppActorIdentityStore
import com.appactor.android.storage.AppActorReceiptQueueItem
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class AppActorPaymentProcessorTests {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `purchase success posts receipt acknowledges purchase and clears queue`() = runBlocking {
        val receiptResponse = fixtureReceiptResponse("fixtures/backend/google_receipt_ok.json")
        val dependencies = createDependencies(
            receiptResponse = AppActorBackendHttpResponse(
                body = receiptResponse,
                statusCode = 200,
                requestId = receiptResponse.requestId,
                signatureVerified = true,
            )
        )

        val result = dependencies.processor.purchase(Activity(), monthlyPackage())

        val success = result as AppActorPurchaseResult.Success
        assertTrue(success.customerInfo.hasActiveEntitlement("premium"))
        assertEquals(listOf("token_123"), dependencies.acknowledgedTokens)
        assertTrue(dependencies.consumedTokens.isEmpty())
        assertTrue(dependencies.queueStore.snapshot().isEmpty())
        assertTrue(dependencies.ledgerStore.isPosted("google:com.appactor.pro.monthly:monthly001:token_123"))
    }

    @Test
    fun `purchase receipt includes cached storefront country code`() = runBlocking {
        val receiptResponse = fixtureReceiptResponse("fixtures/backend/google_receipt_ok.json")
        val dependencies = createDependencies(
            receiptResponse = AppActorBackendHttpResponse(
                body = receiptResponse,
                statusCode = 200,
                requestId = receiptResponse.requestId,
                signatureVerified = true,
            ),
            storefront = AppActorStorefront(
                store = AppActorStore.PlayStore,
                countryCode = "TR",
            ),
        )

        dependencies.processor.purchase(Activity(), monthlyPackage())

        assertEquals("TR", dependencies.postedReceipts.single().countryCode)
    }

    @Test
    fun `purchase retryable error keeps queue and returns offline entitlement snapshot`() = runBlocking {
        val receiptResponse = fixtureReceiptResponse("fixtures/backend/google_receipt_retryable.json")
        val dependencies = createDependencies(
            receiptResponse = AppActorBackendHttpResponse(
                body = receiptResponse,
                statusCode = 200,
                requestId = receiptResponse.requestId,
                signatureVerified = true,
            )
        )

        val result = dependencies.processor.purchase(Activity(), monthlyPackage())

        val success = result as AppActorPurchaseResult.Success
        assertTrue(success.customerInfo.isComputedOffline)
        assertTrue(success.customerInfo.hasActiveEntitlement("premium"))
        assertEquals(1, dependencies.queueStore.pendingCount())
        assertEquals(4_990_000L, dependencies.queueStore.snapshot().single().priceAmountMicros)
        assertEquals("USD", dependencies.queueStore.snapshot().single().currencyCode)
        assertTrue(dependencies.acknowledgedTokens.isEmpty())
    }

    @Test
    fun `purchase permanent error dead letters queue item and throws`() = runBlocking {
        val receiptResponse = fixtureReceiptResponse("fixtures/backend/google_receipt_permanent.json")
        val dependencies = createDependencies(
            receiptResponse = AppActorBackendHttpResponse(
                body = receiptResponse,
                statusCode = 200,
                requestId = receiptResponse.requestId,
                signatureVerified = true,
            )
        )

        val error = runCatching {
            dependencies.processor.purchase(Activity(), monthlyPackage())
        }.exceptionOrNull()

        assertTrue(error is AppActorError.Unknown)
        val snapshot = dependencies.queueStore.snapshot()
        assertEquals(1, snapshot.size)
        assertEquals(com.appactor.android.storage.AppActorReceiptQueuePhase.DeadLettered, snapshot.single().phase)
        assertTrue(snapshot.single().lastError?.isNotBlank() == true)
        assertEquals(listOf("token_123"), dependencies.acknowledgedTokens)
        assertTrue(dependencies.ledgerStore.isPosted("google:com.appactor.pro.monthly:monthly001:token_123"))
    }

    @Test
    fun `purchase skips repost when duplicate token is already marked posted`() = runBlocking {
        val customerEnvelope = fixtureCustomerEnvelope("fixtures/backend/customer_android_active.json")
        val dependencies = createDependencies(
            receiptResponse = AppActorBackendHttpResponse(
                body = fixtureReceiptResponse("fixtures/backend/google_receipt_ok.json"),
                statusCode = 200,
                requestId = "unused",
                signatureVerified = true,
            ),
            customerResponse = AppActorBackendHttpResponse(
                body = customerEnvelope,
                statusCode = 200,
                requestId = customerEnvelope.requestId,
                signatureVerified = true,
            ),
        )
        dependencies.ledgerStore.markPosted("google:com.appactor.pro.monthly:monthly001:token_123")

        val result = dependencies.processor.purchase(Activity(), monthlyPackage())

        val success = result as AppActorPurchaseResult.Success
        assertTrue(success.customerInfo.hasActiveEntitlement("premium"))
        assertEquals(0, dependencies.postedReceipts.size)
        assertEquals(listOf("user_android_123"), dependencies.fetchedCustomers)
        assertTrue(dependencies.queueStore.snapshot().isEmpty())
    }

    @Test
    fun `purchase updates post immediately with resolved local identity`() = runBlocking {
        val purchase = AppActorStorePurchase(
            productId = "com.appactor.pro.monthly",
            productType = AppActorProductType.Subscription,
            purchaseToken = "token_gate_123",
            orderId = "GPA.gate.123",
            purchaseTimeMillis = 1_710_000_000_000,
            purchaseState = com.appactor.android.billing.AppActorStorePurchaseState.Purchased,
            basePlanId = "monthly001",
            offerId = "intro7d",
            priceAmountMicros = 4_990_000,
            currencyCode = "USD",
            isAcknowledged = false,
            isAutoRenewing = true,
            obfuscatedAccountId = appActorGoogleObfuscatedAccountId("user_android_123"),
            rawPurchaseData = "{\"purchaseToken\":\"token_gate_123\"}",
            purchaseSignature = "signature_gate_123",
        )
        val dependencies = createDependencies(
            receiptResponse = AppActorBackendHttpResponse(
                body = fixtureReceiptResponse("fixtures/backend/google_receipt_ok.json"),
                statusCode = 200,
                requestId = "req_identity_gate",
                signatureVerified = true,
            ),
        )

        val info = dependencies.processor.processPurchaseUpdates(listOf(purchase))

        assertTrue(info?.hasActiveEntitlement("premium") == true)
        assertEquals(1, dependencies.postedReceipts.size)
        assertEquals("queue", dependencies.postedReceipts.single().sourceIntent)
        assertEquals(4_990_000L, dependencies.postedReceipts.single().priceAmountMicros)
        assertEquals("USD", dependencies.postedReceipts.single().currency)
    }

    @Test
    fun `purchase updates post merged higher priority source intent`() = runBlocking {
        val purchase = AppActorStorePurchase(
            productId = "com.appactor.pro.monthly",
            productType = AppActorProductType.Subscription,
            purchaseToken = "token_gate_123",
            orderId = "GPA.gate.123",
            purchaseTimeMillis = 1_710_000_000_000,
            purchaseState = com.appactor.android.billing.AppActorStorePurchaseState.Purchased,
            basePlanId = "monthly001",
            offerId = "intro7d",
            priceAmountMicros = 4_990_000,
            currencyCode = "USD",
            isAcknowledged = false,
            isAutoRenewing = true,
            rawPurchaseData = "{\"purchaseToken\":\"token_gate_123\"}",
            purchaseSignature = "signature_gate_123",
        )
        val dependencies = createDependencies(
            receiptResponse = AppActorBackendHttpResponse(
                body = fixtureReceiptResponse("fixtures/backend/google_receipt_ok.json"),
                statusCode = 200,
                requestId = "req_merged_intent",
                signatureVerified = true,
            ),
        )
        dependencies.queueStore.upsert(
            AppActorReceiptQueueItem(
                key = AppActorReceiptQueueItem.makeKey(
                    purchaseToken = purchase.purchaseToken,
                    productId = purchase.productId,
                    basePlanId = purchase.basePlanId,
                ),
                appUserId = "user_android_123",
                packageName = context.packageName,
                environment = "production",
                productId = purchase.productId,
                productType = AppActorProductType.Subscription.wireValue,
                purchaseToken = purchase.purchaseToken,
                purchaseTime = purchase.purchaseTimeMillis.toString(),
                purchaseState = "PURCHASED",
                orderId = purchase.orderId,
                basePlanId = purchase.basePlanId,
                offerId = purchase.offerId,
                sourceIntent = "purchase",
                idempotencyKey = "google:${purchase.productId}:${purchase.basePlanId}:${purchase.purchaseToken}",
                createdAtMillis = 1_710_000_000_000,
                lastUpdatedAtMillis = 1_710_000_000_000,
                phase = com.appactor.android.storage.AppActorReceiptQueuePhase.NeedsPost,
            )
        )

        dependencies.processor.processPurchaseUpdates(listOf(purchase))

        assertEquals(1, dependencies.postedReceipts.size)
        assertEquals("purchase", dependencies.postedReceipts.single().sourceIntent)
    }

    @Test
    fun `purchase update after cancelled foreground purchase remains purchase intent`() = runBlocking {
        val receiptResponse = fixtureReceiptResponse("fixtures/backend/google_receipt_ok.json")
        val dependencies = createDependencies(
            receiptResponse = AppActorBackendHttpResponse(
                body = receiptResponse,
                statusCode = 200,
                requestId = receiptResponse.requestId,
                signatureVerified = true,
            ),
        )
        coEvery { dependencies.storeAdapter.launchPurchase(any(), any()) } throws CancellationException("purchase cancelled")

        val cancelled = runCatching {
            dependencies.processor.purchase(Activity(), monthlyPackage())
        }.exceptionOrNull()
        assertTrue(cancelled is CancellationException)

        dependencies.processor.processPurchaseUpdates(
            listOf(
                AppActorStorePurchase(
                    productId = "com.appactor.pro.monthly",
                    productType = AppActorProductType.Subscription,
                    purchaseToken = "token_cancelled_callback_123",
                    orderId = "GPA.cancelled.callback.123",
                    purchaseTimeMillis = 1_710_000_000_000,
                    purchaseState = com.appactor.android.billing.AppActorStorePurchaseState.Purchased,
                    basePlanId = "monthly001",
                    offerId = "intro7d",
                    priceAmountMicros = 4_990_000,
                    currencyCode = "USD",
                    isAcknowledged = false,
                    isAutoRenewing = true,
                    rawPurchaseData = "{\"purchaseToken\":\"token_cancelled_callback_123\"}",
                    purchaseSignature = "signature_cancelled_callback_123",
                )
            )
        )

        assertEquals(1, dependencies.postedReceipts.size)
        assertEquals("purchase", dependencies.postedReceipts.single().sourceIntent)
    }

    @Test
    fun `purchase update after cancelled foreground grace expires is queue intent`() = runBlocking {
        var now = 1_000L
        val receiptResponse = fixtureReceiptResponse("fixtures/backend/google_receipt_ok.json")
        val dependencies = createDependencies(
            receiptResponse = AppActorBackendHttpResponse(
                body = receiptResponse,
                statusCode = 200,
                requestId = receiptResponse.requestId,
                signatureVerified = true,
            ),
            dateProviderMillis = { now },
        )
        coEvery { dependencies.storeAdapter.launchPurchase(any(), any()) } throws CancellationException("purchase cancelled")

        val cancelled = runCatching {
            dependencies.processor.purchase(Activity(), monthlyPackage())
        }.exceptionOrNull()
        assertTrue(cancelled is CancellationException)

        now += 31_000L
        dependencies.processor.processPurchaseUpdates(
            listOf(
                AppActorStorePurchase(
                    productId = "com.appactor.pro.monthly",
                    productType = AppActorProductType.Subscription,
                    purchaseToken = "token_cancelled_late_123",
                    orderId = "GPA.cancelled.late.123",
                    purchaseTimeMillis = 1_710_000_000_000,
                    purchaseState = com.appactor.android.billing.AppActorStorePurchaseState.Purchased,
                    basePlanId = "monthly001",
                    offerId = "intro7d",
                    priceAmountMicros = 4_990_000,
                    currencyCode = "USD",
                    isAcknowledged = false,
                    isAutoRenewing = true,
                    rawPurchaseData = "{\"purchaseToken\":\"token_cancelled_late_123\"}",
                    purchaseSignature = "signature_cancelled_late_123",
                )
            )
        )

        assertEquals(1, dependencies.postedReceipts.size)
        assertEquals("queue", dependencies.postedReceipts.single().sourceIntent)
    }

    @Test
    fun `drain ready queue finishes already posted receipts without reposting`() = runBlocking {
        val customerEnvelope = fixtureCustomerEnvelope("fixtures/backend/customer_android_active.json")
        val dependencies = createDependencies(
            receiptResponse = AppActorBackendHttpResponse(
                body = fixtureReceiptResponse("fixtures/backend/google_receipt_ok.json"),
                statusCode = 200,
                requestId = "unused",
                signatureVerified = true,
            ),
            customerResponse = AppActorBackendHttpResponse(
                body = customerEnvelope,
                statusCode = 200,
                requestId = customerEnvelope.requestId,
                signatureVerified = true,
            ),
        )
        val queuedItem = AppActorAtomicJsonReceiptQueueStore(context, dependencies.directory).let { _ ->
            com.appactor.android.storage.AppActorReceiptQueueItem(
                key = "google:com.appactor.pro.monthly:monthly001:token_123",
                appUserId = "user_android_123",
                packageName = context.packageName,
                environment = "production",
                productId = "com.appactor.pro.monthly",
                productType = "subscription",
                purchaseToken = "token_123",
                purchaseTime = "1710000000000",
                purchaseState = "PURCHASED",
                orderId = "GPA.1234",
                basePlanId = "monthly001",
                offerId = "intro7d",
                idempotencyKey = "google:com.appactor.pro.monthly:monthly001:token_123",
                createdAtMillis = System.currentTimeMillis(),
                lastUpdatedAtMillis = System.currentTimeMillis(),
                shouldAcknowledge = true,
                phase = com.appactor.android.storage.AppActorReceiptQueuePhase.NeedsFinish,
            )
        }
        dependencies.queueStore.upsert(queuedItem)
        dependencies.ledgerStore.markPosted(queuedItem.key)

        dependencies.processor.drainReadyQueue()

        assertEquals(1, dependencies.acknowledgedTokens.size)
        assertTrue(AppActorAtomicJsonReceiptQueueStore(context, dependencies.directory).snapshot().isEmpty())
        assertEquals(0, dependencies.postedReceipts.size)
        assertEquals(1, dependencies.fetchedCustomers.size)
    }

    @Test
    fun `drain all retries queued receipt until it succeeds`() = runBlocking {
        val retryableResponse = fixtureReceiptResponse("fixtures/backend/google_receipt_retryable.json")
        val successResponse = fixtureReceiptResponse("fixtures/backend/google_receipt_ok.json")
        val dependencies = createDependencies(
            receiptResponse = AppActorBackendHttpResponse(
                body = successResponse,
                statusCode = 200,
                requestId = successResponse.requestId,
                signatureVerified = true,
            ),
            receiptResponses = listOf(
                AppActorBackendHttpResponse(
                    body = retryableResponse,
                    statusCode = 200,
                    requestId = retryableResponse.requestId,
                    signatureVerified = true,
                ),
                AppActorBackendHttpResponse(
                    body = successResponse,
                    statusCode = 200,
                    requestId = successResponse.requestId,
                    signatureVerified = true,
                ),
            ),
        )

        dependencies.processor.purchase(Activity(), monthlyPackage())
        assertEquals("purchase should leave one queued receipt", 1, dependencies.queueStore.snapshot().size)
        val queued = dependencies.queueStore.snapshot().single()
        dependencies.queueStore.update(
            queued.copy(
                nextRetryAtMillis = 0L,
                phase = com.appactor.android.storage.AppActorReceiptQueuePhase.NeedsPost,
            )
        )

        val drained = dependencies.processor.drainAll()

        assertTrue("drainAll should return fresh premium customer", drained?.hasActiveEntitlement("premium") == true)
        assertEquals("receipt should be posted once during purchase and once during retry drain", 2, dependencies.postedReceipts.size)
        assertEquals("successful retry should acknowledge purchase", listOf("token_123"), dependencies.acknowledgedTokens)
        assertTrue("queue should be empty after retry succeeds", dependencies.queueStore.snapshot().isEmpty())
    }

    @Test
    fun `retry dead lettered items drains revived receipts immediately`() = runBlocking {
        val receiptResponse = fixtureReceiptResponse("fixtures/backend/google_receipt_ok.json")
        val dependencies = createDependencies(
            receiptResponse = AppActorBackendHttpResponse(
                body = receiptResponse,
                statusCode = 200,
                requestId = receiptResponse.requestId,
                signatureVerified = true,
            ),
        )
        val deadLetteredItem = AppActorReceiptQueueItem(
            key = "google:com.appactor.pro.monthly:monthly001:token_dead_123",
            appUserId = "user_android_123",
            packageName = context.packageName,
            environment = "production",
            productId = "com.appactor.pro.monthly",
            productType = "subscription",
            purchaseToken = "token_dead_123",
            purchaseTime = "1710000000000",
            purchaseState = "PURCHASED",
            orderId = "GPA.dead.1234",
            basePlanId = "monthly001",
            offerId = "intro7d",
            idempotencyKey = "google:com.appactor.pro.monthly:monthly001:token_dead_123",
            rawPurchaseData = "{\"purchaseToken\":\"token_dead_123\"}",
            purchaseSignature = "signature_dead_123",
            createdAtMillis = System.currentTimeMillis(),
            lastUpdatedAtMillis = System.currentTimeMillis(),
            phase = com.appactor.android.storage.AppActorReceiptQueuePhase.DeadLettered,
            lastError = "temporary outage",
        )
        dependencies.queueStore.upsert(deadLetteredItem)

        val retried = dependencies.processor.retryDeadLetteredItems()

        assertTrue(retried?.hasActiveEntitlement("premium") == true)
        assertEquals(1, dependencies.postedReceipts.size)
        assertEquals(listOf("token_dead_123"), dependencies.acknowledgedTokens)
        assertTrue(dependencies.queueStore.snapshot().isEmpty())
    }

    @Test
    fun `sync current purchases scans active store purchases and posts missing receipts`() = runBlocking {
        val receiptResponse = fixtureReceiptResponse("fixtures/backend/google_receipt_ok.json")
        val activePurchase = AppActorStorePurchase(
            productId = "com.appactor.pro.monthly",
            productType = AppActorProductType.Subscription,
            purchaseToken = "token_sync_123",
            orderId = "GPA.sync.1234",
            purchaseTimeMillis = 1_710_000_000_000,
            purchaseState = com.appactor.android.billing.AppActorStorePurchaseState.Purchased,
            basePlanId = "monthly001",
            offerId = "intro7d",
            priceAmountMicros = 4_990_000,
            currencyCode = "USD",
            isAcknowledged = false,
            isAutoRenewing = true,
            rawPurchaseData = "{\"purchaseToken\":\"token_sync_123\"}",
            purchaseSignature = "signature_sync_123",
        )
        val dependencies = createDependencies(
            receiptResponse = AppActorBackendHttpResponse(
                body = receiptResponse,
                statusCode = 200,
                requestId = receiptResponse.requestId,
                signatureVerified = true,
            ),
            activePurchases = listOf(activePurchase),
        )

        val info = dependencies.processor.syncCurrentPurchases()

        assertTrue(info?.hasActiveEntitlement("premium") == true)
        assertEquals(1, dependencies.syncRequests.size)
        assertEquals("sync", dependencies.syncRequests.single().sourceIntent)
        assertEquals("token_sync_123", dependencies.syncRequests.single().purchases.single().purchaseToken)
        assertEquals(4_990_000L, dependencies.syncRequests.single().purchases.single().priceAmountMicros)
        assertEquals("USD", dependencies.syncRequests.single().purchases.single().currency)
    }

    @Test
    fun `sync current purchases only finalizes purchases confirmed by batch sync`() = runBlocking {
        val restoreResponse = fixtureRestoreResponse("fixtures/backend/google_restore_sample.json")
        val successfulPurchase = AppActorStorePurchase(
            productId = "com.appactor.pro.monthly",
            productType = AppActorProductType.Subscription,
            purchaseToken = "token_sync_ok",
            orderId = "GPA.sync.ok",
            purchaseTimeMillis = 1_710_000_000_000,
            purchaseState = com.appactor.android.billing.AppActorStorePurchaseState.Purchased,
            basePlanId = "monthly001",
            offerId = "intro7d",
            priceAmountMicros = 4_990_000,
            currencyCode = "USD",
            isAcknowledged = false,
            isAutoRenewing = true,
        )
        val conflictedPurchase = successfulPurchase.copy(
            purchaseToken = "token_sync_conflict",
            orderId = "GPA.sync.conflict",
            purchaseTimeMillis = 1_710_000_000_001,
        )
        val dependencies = createDependencies(
            receiptResponse = AppActorBackendHttpResponse(
                body = fixtureReceiptResponse("fixtures/backend/google_receipt_ok.json"),
                statusCode = 200,
                requestId = "unused",
                signatureVerified = true,
            ),
            activePurchases = listOf(successfulPurchase, conflictedPurchase),
            syncResponse = AppActorBackendHttpResponse(
                body = AppActorGoogleSyncResponseDTO(
                    customer = restoreResponse.customer,
                    syncedCount = 1,
                    transferred = false,
                    results = listOf(
                        AppActorGoogleBatchResultDTO(
                            purchaseToken = successfulPurchase.purchaseToken,
                            productId = successfulPurchase.productId,
                            basePlanId = successfulPurchase.basePlanId,
                            offerId = successfulPurchase.offerId,
                            status = "synced",
                        ),
                        AppActorGoogleBatchResultDTO(
                            purchaseToken = conflictedPurchase.purchaseToken,
                            productId = conflictedPurchase.productId,
                            basePlanId = conflictedPurchase.basePlanId,
                            offerId = conflictedPurchase.offerId,
                            status = "conflict",
                            message = "owner conflict",
                        ),
                    ),
                    requestId = "req_sync_partial",
                ),
                statusCode = 200,
                requestId = "req_sync_partial",
                signatureVerified = true,
            ),
        )

        val info = dependencies.processor.syncCurrentPurchases()

        assertTrue(info?.hasActiveEntitlement("premium") == true)
        // Synced purchase is finalized via batch path; conflicted purchase is
        // individually enqueued and processed through the receipt pipeline,
        // ensuring it gets acknowledged so Google does not auto-refund after 3 days.
        assertEquals(listOf("token_sync_ok", "token_sync_conflict"), dependencies.acknowledgedTokens)
        assertTrue(dependencies.ledgerStore.isPosted("google:com.appactor.pro.monthly:monthly001:token_sync_conflict"))
    }

    @Test
    fun `sync current purchases adopts canonical app user id from backend response`() = runBlocking {
        val restoreResponse = fixtureRestoreResponse("fixtures/backend/google_restore_sample.json")
        val activePurchase = AppActorStorePurchase(
            productId = "com.appactor.pro.monthly",
            productType = AppActorProductType.Subscription,
            purchaseToken = "token_sync_canonical",
            orderId = "GPA.sync.canonical",
            purchaseTimeMillis = 1_710_000_000_000,
            purchaseState = com.appactor.android.billing.AppActorStorePurchaseState.Purchased,
            basePlanId = "monthly001",
            offerId = "intro7d",
            priceAmountMicros = 4_990_000,
            currencyCode = "USD",
            isAcknowledged = false,
            isAutoRenewing = true,
        )
        val identityStore = createMockIdentityStore(initialAppUserId = "appactor-anon-old")
        val dependencies = createDependencies(
            receiptResponse = AppActorBackendHttpResponse(
                body = fixtureReceiptResponse("fixtures/backend/google_receipt_ok.json"),
                statusCode = 200,
                requestId = "unused",
                signatureVerified = true,
            ),
            activePurchases = listOf(activePurchase),
            syncResponse = AppActorBackendHttpResponse(
                body = AppActorGoogleSyncResponseDTO(
                    appUserId = "user_google_canonical",
                    customer = restoreResponse.customer,
                    syncedCount = 1,
                    transferred = true,
                    results = listOf(
                        AppActorGoogleBatchResultDTO(
                            purchaseToken = activePurchase.purchaseToken,
                            productId = activePurchase.productId,
                            basePlanId = activePurchase.basePlanId,
                            offerId = activePurchase.offerId,
                            status = "synced",
                        ),
                    ),
                    requestId = "req_sync_canonical",
                ),
                statusCode = 200,
                requestId = "req_sync_canonical",
                signatureVerified = true,
            ),
            identityStore = identityStore,
        )

        val info = dependencies.processor.syncCurrentPurchases()

        assertEquals("user_google_canonical", dependencies.identityStore.currentAppUserId)
        assertEquals("user_google_canonical", info?.appUserId)
    }

    @Test
    fun `startup sync and purchase updates serialize receipt posting for the same token`() = runBlocking {
        val receiptResponse = fixtureReceiptResponse("fixtures/backend/google_receipt_ok.json")
        val purchase = AppActorStorePurchase(
            productId = "com.appactor.pro.monthly",
            productType = AppActorProductType.Subscription,
            purchaseToken = "token_serialized_123",
            orderId = "GPA.serialized.1234",
            purchaseTimeMillis = 1_710_000_000_000,
            purchaseState = com.appactor.android.billing.AppActorStorePurchaseState.Purchased,
            basePlanId = "monthly001",
            offerId = "intro7d",
            isAcknowledged = false,
            isAutoRenewing = true,
            rawPurchaseData = "{\"purchaseToken\":\"token_serialized_123\"}",
            purchaseSignature = "signature_serialized_123",
        )
        val postStarted = CountDownLatch(1)
        val releasePost = CountDownLatch(1)
        val dependencies = createDependencies(
            receiptResponse = AppActorBackendHttpResponse(
                body = receiptResponse,
                statusCode = 200,
                requestId = receiptResponse.requestId,
                signatureVerified = true,
            ),
            activePurchases = listOf(purchase),
            postReceiptStarted = postStarted,
            releasePostedReceipt = releasePost,
        )

        val syncDeferred = async(Dispatchers.Default) { dependencies.processor.syncCurrentPurchases() }
        assertTrue(postStarted.await(5, TimeUnit.SECONDS))

        val updatesDeferred = async(Dispatchers.Default) {
            dependencies.processor.processPurchaseUpdates(listOf(purchase))
        }
        releasePost.countDown()

        syncDeferred.await()
        updatesDeferred.await()

        assertEquals(1, dependencies.syncRequests.size)
        assertEquals(1, dependencies.maxConcurrentReceiptPosts.get())
    }

    @Test
    fun `process purchase updates prefers current identity over stale purchase obfuscated account id`() = runBlocking {
        val receiptResponse = fixtureReceiptResponse("fixtures/backend/google_receipt_ok.json")
        val dependencies = createDependencies(
            receiptResponse = AppActorBackendHttpResponse(
                body = receiptResponse,
                statusCode = 200,
                requestId = receiptResponse.requestId,
                signatureVerified = true,
            ),
            identityStore = createMockIdentityStore(initialAppUserId = "user_new"),
        )
        val purchase = AppActorStorePurchase(
            productId = "com.appactor.pro.monthly",
            productType = AppActorProductType.Subscription,
            purchaseToken = "token_obfuscated_user_123",
            orderId = "GPA.obfuscated.1234",
            purchaseTimeMillis = 1_710_000_000_000,
            purchaseState = com.appactor.android.billing.AppActorStorePurchaseState.Purchased,
            basePlanId = "monthly001",
            offerId = "intro7d",
            isAcknowledged = false,
            isAutoRenewing = true,
            obfuscatedAccountId = appActorGoogleObfuscatedAccountId("user_original"),
            rawPurchaseData = "{\"purchaseToken\":\"token_obfuscated_user_123\"}",
            purchaseSignature = "signature_obfuscated_user_123",
        )

        dependencies.processor.processPurchaseUpdates(listOf(purchase))

        assertEquals("user_new", dependencies.postedReceipts.single().appUserId)
    }

    @Test
    fun `live purchase updates during identity transition are posted with captured old identity`() = runBlocking {
        val receiptResponse = fixtureReceiptResponse("fixtures/backend/google_receipt_ok.json")
        val dependencies = createDependencies(
            receiptResponse = AppActorBackendHttpResponse(
                body = receiptResponse,
                statusCode = 200,
                requestId = receiptResponse.requestId,
                signatureVerified = true,
            ),
            identityStore = createMockIdentityStore(initialAppUserId = "user_old"),
        )
        val purchase = AppActorStorePurchase(
            productId = "com.appactor.pro.monthly",
            productType = AppActorProductType.Subscription,
            purchaseToken = "token_transition_buffer_123",
            orderId = "GPA.transition.1234",
            purchaseTimeMillis = 1_710_000_000_000,
            purchaseState = com.appactor.android.billing.AppActorStorePurchaseState.Purchased,
            basePlanId = "monthly001",
            offerId = "intro7d",
            isAcknowledged = false,
            isAutoRenewing = true,
            rawPurchaseData = "{\"purchaseToken\":\"token_transition_buffer_123\"}",
            purchaseSignature = "signature_transition_buffer_123",
        )

        dependencies.processor.beginIdentityTransition()
        val liveResult = dependencies.processor.processLivePurchaseUpdates(listOf(purchase))
        dependencies.identityStore.setAppUserId("user_new")
        dependencies.processor.endIdentityTransition()

        assertNull(liveResult)
        assertEquals("user_old", dependencies.postedReceipts.single().appUserId)
    }

    @Test
    fun `buffered identity transition purchases do not emit deferred callback for old user`() = runBlocking {
        val pendingPrefs = context.getSharedPreferences(
            "com.appactor.android.pending_purchases",
            Context.MODE_PRIVATE,
        )
        pendingPrefs.edit()
            .clear()
            .putString("token_transition_buffer_deferred_123", "com.appactor.pro.monthly|${System.currentTimeMillis()}")
            .commit()

        try {
            val receiptResponse = fixtureReceiptResponse("fixtures/backend/google_receipt_ok.json")
            val dependencies = createDependencies(
                receiptResponse = AppActorBackendHttpResponse(
                    body = receiptResponse,
                    statusCode = 200,
                    requestId = receiptResponse.requestId,
                    signatureVerified = true,
                ),
                identityStore = createMockIdentityStore(initialAppUserId = "user_old"),
            )
            val deferredCallbacks = mutableListOf<Pair<String, String?>>()
            dependencies.processor.onDeferredPurchaseResolved = { productId, customerInfo ->
                deferredCallbacks += productId to customerInfo.appUserId
            }
            val purchase = AppActorStorePurchase(
                productId = "com.appactor.pro.monthly",
                productType = AppActorProductType.Subscription,
                purchaseToken = "token_transition_buffer_deferred_123",
                orderId = "GPA.transition.deferred.1234",
                purchaseTimeMillis = 1_710_000_000_000,
                purchaseState = com.appactor.android.billing.AppActorStorePurchaseState.Purchased,
                basePlanId = "monthly001",
                offerId = "intro7d",
                isAcknowledged = false,
                isAutoRenewing = true,
                rawPurchaseData = "{\"purchaseToken\":\"token_transition_buffer_deferred_123\"}",
                purchaseSignature = "signature_transition_buffer_deferred_123",
            )

            dependencies.processor.beginIdentityTransition()
            val liveResult = dependencies.processor.processLivePurchaseUpdates(listOf(purchase))
            dependencies.identityStore.setAppUserId("user_new")
            dependencies.processor.endIdentityTransition()

            assertNull(liveResult)
            assertEquals("user_old", dependencies.postedReceipts.single().appUserId)
            assertEquals("purchase", dependencies.postedReceipts.single().sourceIntent)
            assertTrue(deferredCallbacks.isEmpty())
            assertFalse(pendingPrefs.contains("token_transition_buffer_deferred_123"))
        } finally {
            pendingPrefs.edit().clear().commit()
        }
    }

    @Test
    fun `buffered same user transition purchases emit deferred callback`() = runBlocking {
        val pendingPrefs = context.getSharedPreferences(
            "com.appactor.android.pending_purchases",
            Context.MODE_PRIVATE,
        )
        pendingPrefs.edit()
            .clear()
            .putString("token_same_user_transition_deferred_123", "com.appactor.pro.monthly|${System.currentTimeMillis()}")
            .commit()

        try {
            val receiptResponse = fixtureReceiptResponse("fixtures/backend/google_receipt_ok.json")
            val dependencies = createDependencies(
                receiptResponse = AppActorBackendHttpResponse(
                    body = receiptResponse,
                    statusCode = 200,
                    requestId = receiptResponse.requestId,
                    signatureVerified = true,
                ),
                identityStore = createMockIdentityStore(initialAppUserId = "user_same"),
            )
            val deferredCallbacks = mutableListOf<Pair<String, String?>>()
            dependencies.processor.onDeferredPurchaseResolved = { productId, customerInfo ->
                deferredCallbacks += productId to customerInfo.appUserId
            }
            val purchase = AppActorStorePurchase(
                productId = "com.appactor.pro.monthly",
                productType = AppActorProductType.Subscription,
                purchaseToken = "token_same_user_transition_deferred_123",
                orderId = "GPA.same.user.transition.1234",
                purchaseTimeMillis = 1_710_000_000_000,
                purchaseState = com.appactor.android.billing.AppActorStorePurchaseState.Purchased,
                basePlanId = "monthly001",
                offerId = "intro7d",
                isAcknowledged = false,
                isAutoRenewing = true,
                rawPurchaseData = "{\"purchaseToken\":\"token_same_user_transition_deferred_123\"}",
                purchaseSignature = "signature_same_user_transition_deferred_123",
            )

            dependencies.processor.beginIdentityTransition()
            val liveResult = dependencies.processor.processLivePurchaseUpdates(listOf(purchase))
            val transitionResults = dependencies.processor.endIdentityTransition()

            assertNull(liveResult)
            assertEquals("user_same", dependencies.postedReceipts.single().appUserId)
            assertEquals("purchase", dependencies.postedReceipts.single().sourceIntent)
            assertEquals(1, transitionResults.size)
            assertEquals("user_same", transitionResults.single().appUserId)
            assertEquals(listOf("com.appactor.pro.monthly"), deferredCallbacks.map { it.first })
            assertFalse(pendingPrefs.contains("token_same_user_transition_deferred_123"))
        } finally {
            pendingPrefs.edit().clear().commit()
        }
    }

    @Test
    fun `sync current purchases uses explicit app user override instead of mutable identity`() = runBlocking {
        val receiptResponse = fixtureReceiptResponse("fixtures/backend/google_receipt_ok.json")
        val activePurchase = AppActorStorePurchase(
            productId = "com.appactor.pro.monthly",
            productType = AppActorProductType.Subscription,
            purchaseToken = "token_snapshot_override_123",
            orderId = "GPA.snapshot.override.1234",
            purchaseTimeMillis = 1_710_000_000_000,
            purchaseState = com.appactor.android.billing.AppActorStorePurchaseState.Purchased,
            basePlanId = "monthly001",
            offerId = "intro7d",
            isAcknowledged = false,
            isAutoRenewing = true,
            rawPurchaseData = "{\"purchaseToken\":\"token_snapshot_override_123\"}",
            purchaseSignature = "signature_snapshot_override_123",
        )
        val dependencies = createDependencies(
            receiptResponse = AppActorBackendHttpResponse(
                body = receiptResponse,
                statusCode = 200,
                requestId = receiptResponse.requestId,
                signatureVerified = true,
            ),
            activePurchases = listOf(activePurchase),
            identityStore = createMockIdentityStore(initialAppUserId = "user_live"),
        )

        dependencies.processor.syncCurrentPurchases(appUserIdOverride = "user_snapshot")

        assertEquals("user_snapshot", dependencies.syncRequests.single().appUserId)
    }

    @Test
    fun `purchase with explicit direct params resolves store level target`() = runBlocking {
        val receiptResponse = fixtureReceiptResponse("fixtures/backend/google_receipt_ok.json")
        val dependencies = createDependencies(
            receiptResponse = AppActorBackendHttpResponse(
                body = receiptResponse,
                statusCode = 200,
                requestId = receiptResponse.requestId,
                signatureVerified = true,
            ),
            directPurchaseRequests = mapOf(
                "com.appactor.raw.product" to AppActorStoreProductRequest(
                    productId = "com.appactor.raw.product",
                    productType = AppActorProductType.NonConsumable,
                )
            ),
        )

        val result = dependencies.processor.purchase(
            activity = Activity(),
            params = AppActorPurchaseParams(
                productId = "com.appactor.raw.product",
                productType = AppActorProductType.NonConsumable,
            ),
        )

        val success = result as AppActorPurchaseResult.Success
        assertTrue(success.customerInfo.hasActiveEntitlement("premium"))
        assertEquals("com.appactor.raw.product", dependencies.postedReceipts.single().productId)
    }

    @Test
    fun `purchase with explicit direct params keeps explicit app user override`() = runBlocking {
        val receiptResponse = fixtureReceiptResponse("fixtures/backend/google_receipt_ok.json")
        val dependencies = createDependencies(
            receiptResponse = AppActorBackendHttpResponse(
                body = receiptResponse,
                statusCode = 200,
                requestId = receiptResponse.requestId,
                signatureVerified = true,
            ),
            identityStore = createMockIdentityStore(initialAppUserId = "user_live"),
        )

        dependencies.processor.purchase(
            activity = Activity(),
            params = AppActorPurchaseParams(
                productId = "com.appactor.pro.monthly",
                productType = AppActorProductType.Subscription,
                basePlanId = "monthly001",
                offerId = "intro7d",
            ),
            appUserIdOverride = "user_snapshot",
        )

        assertEquals("user_snapshot", dependencies.postedReceipts.single().appUserId)
    }

    @Test
    fun `purchase with package reuses cached resolved request without direct store resolution`() = runBlocking {
        val receiptResponse = fixtureReceiptResponse("fixtures/backend/google_receipt_ok.json")
        val dependencies = createDependencies(
            receiptResponse = AppActorBackendHttpResponse(
                body = receiptResponse,
                statusCode = 200,
                requestId = receiptResponse.requestId,
                signatureVerified = true,
            ),
            directPurchaseResolutionError = IllegalStateException("package purchase should not re-resolve"),
        )

        val result = dependencies.processor.purchase(Activity(), monthlyPackage())

        assertTrue(result is AppActorPurchaseResult.Success)
        assertEquals("com.appactor.pro.monthly", dependencies.postedReceipts.single().productId)
        coVerify(exactly = 0) {
            dependencies.storeAdapter.resolveDirectPurchaseRequest(any())
        }
    }

    @Test
    fun `purchase with underspecified direct params fails fast`() = runBlocking {
        val receiptResponse = fixtureReceiptResponse("fixtures/backend/google_receipt_ok.json")
        val dependencies = createDependencies(
            receiptResponse = AppActorBackendHttpResponse(
                body = receiptResponse,
                statusCode = 200,
                requestId = receiptResponse.requestId,
                signatureVerified = true,
            ),
        )

        val error = runCatching {
            dependencies.processor.purchase(
                activity = Activity(),
                params = AppActorPurchaseParams(productId = "com.appactor.raw.product"),
            )
        }.exceptionOrNull()

        assertTrue(error is AppActorError.InvalidConfiguration)
        assertTrue(error?.message?.contains("Underspecified direct purchase") == true)
    }

    @Test
    fun `purchase with invalid subscription replacement params fails fast`() = runBlocking {
        val receiptResponse = fixtureReceiptResponse("fixtures/backend/google_receipt_ok.json")
        val dependencies = createDependencies(
            receiptResponse = AppActorBackendHttpResponse(
                body = receiptResponse,
                statusCode = 200,
                requestId = receiptResponse.requestId,
                signatureVerified = true,
            ),
        )

        val error = runCatching {
            dependencies.processor.purchase(
                activity = Activity(),
                params = AppActorPurchaseParams(
                    productId = "com.appactor.pro.monthly",
                    productType = AppActorProductType.Subscription,
                    basePlanId = "monthly001",
                    replacementMode = AppActorSubscriptionReplacementMode.Deferred,
                ),
            )
        }.exceptionOrNull()

        assertTrue(error is AppActorError.InvalidConfiguration)
        assertTrue(error?.message?.contains("Invalid subscription replacement params") == true)
    }

    @Test
    fun `payment processor emits receipt pipeline events`() = runBlocking {
        val retryableResponse = fixtureReceiptResponse("fixtures/backend/google_receipt_retryable.json")
        val events = mutableListOf<AppActorReceiptPipelineEvent>()
        val dependencies = createDependencies(
            receiptResponse = AppActorBackendHttpResponse(
                body = retryableResponse,
                statusCode = 200,
                requestId = retryableResponse.requestId,
                signatureVerified = true,
            ),
            pipelineEvents = events,
        )

        dependencies.processor.purchase(Activity(), monthlyPackage())

        assertTrue(events.any { it is AppActorReceiptPipelineEvent.RetryScheduled })
    }

    @Test
    fun `retryable receipt remains queued after repeated failures`() = runBlocking {
        val retryableResponse = fixtureReceiptResponse("fixtures/backend/google_receipt_retryable.json")
        val events = mutableListOf<AppActorReceiptPipelineEvent>()
        val dependencies = createDependencies(
            receiptResponse = AppActorBackendHttpResponse(
                body = retryableResponse,
                statusCode = 200,
                requestId = retryableResponse.requestId,
                signatureVerified = true,
            ),
            pipelineEvents = events,
        )

        dependencies.queueStore.upsert(
            AppActorReceiptQueueItem(
                key = AppActorReceiptQueueItem.makeKey(
                    purchaseToken = "token_retry_dead",
                    productId = "com.appactor.pro.monthly",
                    basePlanId = "monthly001",
                ),
                appUserId = "user_android_123",
                packageName = context.packageName,
                environment = "production",
                productId = "com.appactor.pro.monthly",
                productType = "subscription",
                purchaseToken = "token_retry_dead",
                purchaseTime = "1710000000000",
                purchaseState = "PURCHASED",
                orderId = "GPA.retry.dead",
                basePlanId = "monthly001",
                offerId = "intro7d",
                idempotencyKey = "google:com.appactor.pro.monthly:monthly001:token_retry_dead",
                createdAtMillis = 1_710_000_000_000,
                lastUpdatedAtMillis = 1_710_000_000_000,
                retryCount = 2,
                nextRetryAtMillis = 0L,
                phase = com.appactor.android.storage.AppActorReceiptQueuePhase.NeedsPost,
            )
        )

        dependencies.processor.drainReadyQueue()

        val item = dependencies.queueStore.snapshot().single()
        assertEquals(com.appactor.android.storage.AppActorReceiptQueuePhase.NeedsPost, item.phase)
        assertEquals(3, item.retryCount)
        assertTrue(events.any { it is AppActorReceiptPipelineEvent.RetryScheduled })
        assertTrue(dependencies.acknowledgedTokens.isEmpty())
        assertFalse(dependencies.ledgerStore.isPosted(item.key))
    }

    @Test
    fun `sync current purchases resolves unknown inapp purchases from offerings metadata before posting`() = runBlocking {
        val receiptResponse = fixtureReceiptResponse("fixtures/backend/google_receipt_ok.json")
        val activePurchase = AppActorStorePurchase(
            productId = "com.appactor.coins.100",
            productType = AppActorProductType.Unknown,
            purchaseToken = "token_unknown_coins_123",
            orderId = "GPA.unknown.coins.1234",
            purchaseTimeMillis = 1_710_000_000_000,
            purchaseState = com.appactor.android.billing.AppActorStorePurchaseState.Purchased,
            isAcknowledged = false,
            rawPurchaseData = "{\"purchaseToken\":\"token_unknown_coins_123\"}",
            purchaseSignature = "signature_unknown_coins_123",
        )
        val dependencies = createDependencies(
            receiptResponse = AppActorBackendHttpResponse(
                body = receiptResponse,
                statusCode = 200,
                requestId = receiptResponse.requestId,
                signatureVerified = true,
            ),
            activePurchases = listOf(activePurchase),
        )

        dependencies.processor.syncCurrentPurchases()

        assertEquals(1, dependencies.syncRequests.size)
        assertEquals("consumable", dependencies.syncRequests.single().purchases.single().productType)
    }

    @Test
    fun `sync current purchases lazily hydrates offerings metadata when catalog starts empty`() = runBlocking {
        val receiptResponse = fixtureReceiptResponse("fixtures/backend/google_receipt_ok.json")
        val activePurchase = AppActorStorePurchase(
            productId = "com.appactor.coins.100",
            productType = AppActorProductType.Unknown,
            purchaseToken = "token_unknown_lazy_123",
            orderId = "GPA.unknown.lazy.1234",
            purchaseTimeMillis = 1_710_000_000_000,
            purchaseState = com.appactor.android.billing.AppActorStorePurchaseState.Purchased,
            isAcknowledged = false,
            rawPurchaseData = "{\"purchaseToken\":\"token_unknown_lazy_123\"}",
            purchaseSignature = "signature_unknown_lazy_123",
        )
        val dependencies = createDependencies(
            receiptResponse = AppActorBackendHttpResponse(
                body = receiptResponse,
                statusCode = 200,
                requestId = receiptResponse.requestId,
                signatureVerified = true,
            ),
            activePurchases = listOf(activePurchase),
        )

        dependencies.offeringsManager.clearCache()

        dependencies.processor.syncCurrentPurchases()

        assertEquals(1, dependencies.syncRequests.size)
        assertEquals("consumable", dependencies.syncRequests.single().purchases.single().productType)
        coVerify(exactly = 2) { dependencies.backendClient.getOfferings(any()) }
    }

    @Test
    fun `replayed unknown inapp update revives dead letter once offerings metadata becomes available`() = runBlocking {
        val receiptResponse = fixtureReceiptResponse("fixtures/backend/google_receipt_ok.json")
        val activePurchase = AppActorStorePurchase(
            productId = "com.appactor.coins.100",
            productType = AppActorProductType.Unknown,
            purchaseToken = "token_unknown_replay_123",
            orderId = "GPA.unknown.replay.1234",
            purchaseTimeMillis = 1_710_000_000_000,
            purchaseState = com.appactor.android.billing.AppActorStorePurchaseState.Purchased,
            isAcknowledged = false,
            rawPurchaseData = "{\"purchaseToken\":\"token_unknown_replay_123\"}",
            purchaseSignature = "signature_unknown_replay_123",
        )
        val dependencies = createDependencies(
            receiptResponse = AppActorBackendHttpResponse(
                body = receiptResponse,
                statusCode = 200,
                requestId = receiptResponse.requestId,
                signatureVerified = true,
            ),
            offeringsEnvelope = fixtureOfferingsWithoutProduct("com.appactor.coins.100"),
        )

        dependencies.queueStore.upsert(
            AppActorReceiptQueueItem(
                key = AppActorReceiptQueueItem.makeKey(
                    purchaseToken = activePurchase.purchaseToken,
                    productId = activePurchase.productId,
                ),
                appUserId = "user_android_123",
                packageName = context.packageName,
                environment = "production",
                productId = activePurchase.productId,
                productType = AppActorProductType.Unknown.wireValue,
                purchaseToken = activePurchase.purchaseToken,
                purchaseTime = activePurchase.purchaseTimeMillis.toString(),
                purchaseState = "PURCHASED",
                orderId = activePurchase.orderId,
                idempotencyKey = "google:${activePurchase.productId}:${activePurchase.purchaseToken}",
                createdAtMillis = 1_710_000_000_000,
                lastUpdatedAtMillis = 1_710_000_000_000,
                retryCount = 3,
                phase = com.appactor.android.storage.AppActorReceiptQueuePhase.DeadLettered,
                lastError = "unknown_product_type: Unable to resolve Google Play one-time product type.",
                rawPurchaseData = activePurchase.rawPurchaseData,
                purchaseSignature = activePurchase.purchaseSignature,
            )
        )

        // Re-stub getOfferings to return the full offerings with the product included
        val fullOfferings = fixtureOfferings()
        coEvery { dependencies.backendClient.getOfferings(any()) } returns AppActorBackendHttpResponse(
            body = fullOfferings,
            statusCode = 200,
            requestId = "req_offerings_refresh_unknown",
            signatureVerified = true,
        )
        dependencies.offeringsManager.clearCache()
        dependencies.offeringsManager.getOfferings(forceRefresh = true)

        val info = dependencies.processor.processPurchaseUpdates(listOf(activePurchase))

        assertTrue(info?.hasActiveEntitlement("premium") == true)
        assertEquals(1, dependencies.postedReceipts.size)
        assertEquals("consumable", dependencies.postedReceipts.single().productType)
        assertTrue(dependencies.queueStore.snapshot().isEmpty())
        assertTrue(
            dependencies.ledgerStore.isPosted(
                AppActorReceiptQueueItem.makeKey(
                    purchaseToken = activePurchase.purchaseToken,
                    productId = activePurchase.productId,
                )
            )
        )
    }

    @Test
    fun `app restart style sync can recover previously dead lettered unknown inapp purchase`() = runBlocking {
        val receiptResponse = fixtureReceiptResponse("fixtures/backend/google_receipt_ok.json")
        val sharedDirectory = File(context.filesDir, "tests/payment-restart-${UUID.randomUUID()}").apply { mkdirs() }
        val purchase = AppActorStorePurchase(
            productId = "com.appactor.coins.100",
            productType = AppActorProductType.Unknown,
            purchaseToken = "token_unknown_restart_123",
            orderId = "GPA.unknown.restart.1234",
            purchaseTimeMillis = 1_710_000_000_000,
            purchaseState = com.appactor.android.billing.AppActorStorePurchaseState.Purchased,
            isAcknowledged = false,
            rawPurchaseData = "{\"purchaseToken\":\"token_unknown_restart_123\"}",
            purchaseSignature = "signature_unknown_restart_123",
        )
        val firstBoot = createDependencies(
            receiptResponse = AppActorBackendHttpResponse(
                body = receiptResponse,
                statusCode = 200,
                requestId = receiptResponse.requestId,
                signatureVerified = true,
            ),
            offeringsEnvelope = fixtureOfferingsWithoutProduct("com.appactor.coins.100"),
            directory = sharedDirectory,
            activePurchases = listOf(purchase),
        )

        firstBoot.processor.syncCurrentPurchases()
        val unresolved = firstBoot.queueStore.snapshot().single()
        assertEquals(com.appactor.android.storage.AppActorReceiptQueuePhase.NeedsPost, unresolved.phase)
        assertEquals("sync", unresolved.sourceIntent)

        firstBoot.queueStore.update(
            unresolved.copy(
                retryCount = 3,
                phase = com.appactor.android.storage.AppActorReceiptQueuePhase.DeadLettered,
                nextRetryAtMillis = 0L,
                lastError = "unknown_product_type: dead-lettered before restart",
            )
        )

        val secondBoot = createDependencies(
            receiptResponse = AppActorBackendHttpResponse(
                body = receiptResponse,
                statusCode = 200,
                requestId = "req_restart_recovery",
                signatureVerified = true,
            ),
            offeringsEnvelope = fixtureOfferings(),
            directory = sharedDirectory,
            activePurchases = listOf(purchase),
        )

        val recovered = secondBoot.processor.syncCurrentPurchases()

        assertTrue(recovered?.hasActiveEntitlement("premium") == true)
        assertEquals(1, secondBoot.syncRequests.size)
        assertEquals("consumable", secondBoot.syncRequests.single().purchases.single().productType)
        assertTrue(secondBoot.queueStore.snapshot().isEmpty())
    }

    @Test
    fun `drain ready queue prioritizes immediately ready receipts over delayed retries`() = runBlocking {
        val receiptResponse = fixtureReceiptResponse("fixtures/backend/google_receipt_ok.json")
        val postStarted = CountDownLatch(1)
        val dependencies = createDependencies(
            receiptResponse = AppActorBackendHttpResponse(
                body = receiptResponse,
                statusCode = 200,
                requestId = receiptResponse.requestId,
                signatureVerified = true,
            ),
            postReceiptStarted = postStarted,
        )
        val now = System.currentTimeMillis()
        val delayedKey = AppActorReceiptQueueItem.makeKey(
            purchaseToken = "token_delayed_future_123",
            productId = "com.appactor.pro.monthly",
            basePlanId = "monthly001",
        )
        dependencies.queueStore.upsert(
            AppActorReceiptQueueItem(
                key = delayedKey,
                appUserId = "user_android_123",
                packageName = context.packageName,
                environment = "production",
                productId = "com.appactor.pro.monthly",
                productType = AppActorProductType.Subscription.wireValue,
                purchaseToken = "token_delayed_future_123",
                purchaseTime = "1710000000000",
                purchaseState = "PURCHASED",
                orderId = "GPA.delayed.future",
                basePlanId = "monthly001",
                offerId = "intro7d",
                idempotencyKey = "google:com.appactor.pro.monthly:monthly001:token_delayed_future_123",
                createdAtMillis = now,
                lastUpdatedAtMillis = now,
                nextRetryAtMillis = now + 60_000L,
                phase = com.appactor.android.storage.AppActorReceiptQueuePhase.NeedsPost,
            )
        )

        dependencies.processor.drainReadyQueue()

        dependencies.queueStore.upsert(
            AppActorReceiptQueueItem(
                key = AppActorReceiptQueueItem.makeKey(
                    purchaseToken = "token_waiting_identity_123",
                    productId = "com.appactor.pro.monthly",
                    basePlanId = "monthly001",
                ),
                appUserId = "user_waiting",
                packageName = context.packageName,
                environment = "production",
                productId = "com.appactor.pro.monthly",
                productType = AppActorProductType.Subscription.wireValue,
                purchaseToken = "token_waiting_identity_123",
                purchaseTime = "1710000000000",
                purchaseState = "PURCHASED",
                orderId = "GPA.waiting.identity",
                basePlanId = "monthly001",
                offerId = "intro7d",
                idempotencyKey = "google:com.appactor.pro.monthly:monthly001:token_waiting_identity_123",
                createdAtMillis = now,
                lastUpdatedAtMillis = now,
                phase = com.appactor.android.storage.AppActorReceiptQueuePhase.NeedsPost,
            )
        )

        dependencies.processor.drainReadyQueue()

        assertTrue(postStarted.await(1, TimeUnit.SECONDS))
        assertEquals(1, dependencies.postedReceipts.size)
        assertEquals("token_waiting_identity_123", dependencies.postedReceipts.single().purchaseToken)

        dependencies.queueStore.remove(delayedKey)
        dependencies.processor.drainReadyQueue()
        Unit
    }

    @Test
    fun `restore purchases posts bulk restore and returns customer snapshot`() = runBlocking {
        val restoreResponse = fixtureRestoreResponse("fixtures/backend/google_restore_sample.json")
        val activePurchase = AppActorStorePurchase(
            productId = "com.appactor.pro.monthly",
            productType = AppActorProductType.Subscription,
            purchaseToken = "token_restore_123",
            orderId = "GPA.restore.1234",
            purchaseTimeMillis = 1_710_000_000_000,
            purchaseState = com.appactor.android.billing.AppActorStorePurchaseState.Purchased,
            basePlanId = "monthly001",
            offerId = "intro7d",
            priceAmountMicros = 4_990_000,
            currencyCode = "USD",
            isAcknowledged = false,
            isAutoRenewing = true,
        )
        val dependencies = createDependencies(
            receiptResponse = AppActorBackendHttpResponse(
                body = fixtureReceiptResponse("fixtures/backend/google_receipt_ok.json"),
                statusCode = 200,
                requestId = "unused",
                signatureVerified = true,
            ),
            activePurchases = listOf(activePurchase),
            restoreResponse = AppActorBackendHttpResponse(
                body = restoreResponse,
                statusCode = 200,
                requestId = restoreResponse.requestId,
                signatureVerified = true,
            ),
        )

        val info = dependencies.processor.restorePurchases()

        assertTrue(info.hasActiveEntitlement("premium"))
        assertEquals(1, dependencies.restoreRequests.size)
        assertEquals("restore", dependencies.restoreRequests.single().sourceIntent)
        assertEquals("token_restore_123", dependencies.restoreRequests.single().purchases.single().purchaseToken)
        assertEquals(4_990_000L, dependencies.restoreRequests.single().purchases.single().priceAmountMicros)
        assertEquals("USD", dependencies.restoreRequests.single().purchases.single().currency)
        assertEquals(0, dependencies.postedReceipts.size)
        assertEquals(listOf("token_restore_123"), dependencies.acknowledgedTokens)
        assertTrue(dependencies.queueStore.snapshot().isEmpty())
        assertTrue(dependencies.ledgerStore.isPosted("google:com.appactor.pro.monthly:monthly001:token_restore_123"))
    }

    @Test
    fun `restore purchases only finalizes active purchases confirmed by restore batch`() = runBlocking {
        val restoreResponse = fixtureRestoreResponse("fixtures/backend/google_restore_sample.json")
        val successfulPurchase = AppActorStorePurchase(
            productId = "com.appactor.pro.monthly",
            productType = AppActorProductType.Subscription,
            purchaseToken = "token_restore_ok",
            orderId = "GPA.restore.ok",
            purchaseTimeMillis = 1_710_000_000_000,
            purchaseState = com.appactor.android.billing.AppActorStorePurchaseState.Purchased,
            basePlanId = "monthly001",
            offerId = "intro7d",
            isAcknowledged = false,
            isAutoRenewing = true,
        )
        val conflictedPurchase = successfulPurchase.copy(
            purchaseToken = "token_restore_conflict",
            orderId = "GPA.restore.conflict",
            purchaseTimeMillis = 1_710_000_000_001,
        )
        val dependencies = createDependencies(
            receiptResponse = AppActorBackendHttpResponse(
                body = fixtureReceiptResponse("fixtures/backend/google_receipt_ok.json"),
                statusCode = 200,
                requestId = "unused",
                signatureVerified = true,
            ),
            activePurchases = listOf(successfulPurchase, conflictedPurchase),
            restoreResponse = AppActorBackendHttpResponse(
                body = restoreResponse.copy(
                    restoredCount = 1,
                    results = listOf(
                        AppActorGoogleBatchResultDTO(
                            purchaseToken = successfulPurchase.purchaseToken,
                            productId = successfulPurchase.productId,
                            basePlanId = successfulPurchase.basePlanId,
                            offerId = successfulPurchase.offerId,
                            status = "synced",
                        ),
                        AppActorGoogleBatchResultDTO(
                            purchaseToken = conflictedPurchase.purchaseToken,
                            productId = conflictedPurchase.productId,
                            basePlanId = conflictedPurchase.basePlanId,
                            offerId = conflictedPurchase.offerId,
                            status = "conflict",
                            message = "owner conflict",
                        ),
                    ),
                ),
                statusCode = 200,
                requestId = restoreResponse.requestId,
                signatureVerified = true,
            ),
        )

        val info = dependencies.processor.restorePurchases()

        assertTrue(info.hasActiveEntitlement("premium"))
        // Synced purchase is finalized via batch path; conflicted purchase is
        // individually enqueued and processed, ensuring Google acknowledgement.
        assertEquals(listOf("token_restore_ok", "token_restore_conflict"), dependencies.acknowledgedTokens)
        assertTrue(dependencies.ledgerStore.isPosted("google:com.appactor.pro.monthly:monthly001:token_restore_conflict"))
    }

    @Test
    fun `restore purchases adopts canonical app user id from backend response`() = runBlocking {
        val restoreResponse = fixtureRestoreResponse("fixtures/backend/google_restore_sample.json")
        val activePurchase = AppActorStorePurchase(
            productId = "com.appactor.pro.monthly",
            productType = AppActorProductType.Subscription,
            purchaseToken = "token_restore_canonical",
            orderId = "GPA.restore.canonical",
            purchaseTimeMillis = 1_710_000_000_000,
            purchaseState = com.appactor.android.billing.AppActorStorePurchaseState.Purchased,
            basePlanId = "monthly001",
            offerId = "intro7d",
            isAcknowledged = false,
            isAutoRenewing = true,
        )
        val identityStore = createMockIdentityStore(initialAppUserId = "appactor-anon-old")
        val dependencies = createDependencies(
            receiptResponse = AppActorBackendHttpResponse(
                body = fixtureReceiptResponse("fixtures/backend/google_receipt_ok.json"),
                statusCode = 200,
                requestId = "unused",
                signatureVerified = true,
            ),
            activePurchases = listOf(activePurchase),
            restoreResponse = AppActorBackendHttpResponse(
                body = restoreResponse.copy(
                    appUserId = "user_google_canonical",
                    restoredCount = 1,
                    transferred = true,
                    results = listOf(
                        AppActorGoogleBatchResultDTO(
                            purchaseToken = activePurchase.purchaseToken,
                            productId = activePurchase.productId,
                            basePlanId = activePurchase.basePlanId,
                            offerId = activePurchase.offerId,
                            status = "synced",
                        ),
                    ),
                ),
                statusCode = 200,
                requestId = restoreResponse.requestId,
                signatureVerified = true,
            ),
            identityStore = identityStore,
        )

        val info = dependencies.processor.restorePurchases()

        assertEquals("user_google_canonical", dependencies.identityStore.currentAppUserId)
        assertEquals("user_google_canonical", info.appUserId)
    }

    @Test
    fun `restore purchases can restore from history without active purchases`() = runBlocking {
        val restoreResponse = fixtureRestoreResponse("fixtures/backend/google_restore_sample.json")
        val historyPurchase = com.appactor.android.billing.AppActorStorePurchaseHistoryRecord(
            productId = "com.appactor.pro.monthly",
            productType = AppActorProductType.Subscription,
            purchaseToken = "token_history_restore_123",
            purchaseTimeMillis = 1_710_000_000_000,
            basePlanId = "monthly001",
            offerId = "intro7d",
        )
        val dependencies = createDependencies(
            receiptResponse = AppActorBackendHttpResponse(
                body = fixtureReceiptResponse("fixtures/backend/google_receipt_ok.json"),
                statusCode = 200,
                requestId = "unused",
                signatureVerified = true,
            ),
            historyPurchases = listOf(historyPurchase),
            restoreResponse = AppActorBackendHttpResponse(
                body = restoreResponse,
                statusCode = 200,
                requestId = restoreResponse.requestId,
                signatureVerified = true,
            ),
        )

        val info = dependencies.processor.restorePurchases()

        assertTrue(info.hasActiveEntitlement("premium"))
        assertEquals(1, dependencies.restoreRequests.size)
        assertEquals("token_history_restore_123", dependencies.restoreRequests.single().purchases.single().purchaseToken)
        assertTrue(dependencies.acknowledgedTokens.isEmpty())
        assertTrue(dependencies.consumedTokens.isEmpty())
    }

    @Test
    fun `restore purchases dedupes history and active candidates while finalizing only active purchases`() = runBlocking {
        val restoreResponse = fixtureRestoreResponse("fixtures/backend/google_restore_sample.json")
        val activePurchase = AppActorStorePurchase(
            productId = "com.appactor.pro.monthly",
            productType = AppActorProductType.Subscription,
            purchaseToken = "token_restore_dupe_123",
            orderId = "GPA.restore.dupe.1234",
            purchaseTimeMillis = 1_710_000_000_000,
            purchaseState = com.appactor.android.billing.AppActorStorePurchaseState.Purchased,
            basePlanId = "monthly001",
            offerId = "intro7d",
            isAcknowledged = false,
            isAutoRenewing = true,
        )
        val duplicateHistory = com.appactor.android.billing.AppActorStorePurchaseHistoryRecord(
            productId = activePurchase.productId,
            productType = AppActorProductType.Subscription,
            purchaseToken = activePurchase.purchaseToken,
            purchaseTimeMillis = activePurchase.purchaseTimeMillis,
            basePlanId = activePurchase.basePlanId,
            offerId = activePurchase.offerId,
        )
        val historyOnly = com.appactor.android.billing.AppActorStorePurchaseHistoryRecord(
            productId = "com.appactor.pro.yearly",
            productType = AppActorProductType.Subscription,
            purchaseToken = "token_history_only_123",
            purchaseTimeMillis = 1_710_000_100_000,
            basePlanId = "yearly001",
        )
        val dependencies = createDependencies(
            receiptResponse = AppActorBackendHttpResponse(
                body = fixtureReceiptResponse("fixtures/backend/google_receipt_ok.json"),
                statusCode = 200,
                requestId = "unused",
                signatureVerified = true,
            ),
            activePurchases = listOf(activePurchase),
            historyPurchases = listOf(duplicateHistory, historyOnly),
            restoreResponse = AppActorBackendHttpResponse(
                body = restoreResponse,
                statusCode = 200,
                requestId = restoreResponse.requestId,
                signatureVerified = true,
            ),
        )

        dependencies.processor.restorePurchases()

        val requestPurchases = dependencies.restoreRequests.single().purchases
        assertEquals(2, requestPurchases.size)
        assertTrue(requestPurchases.any { it.purchaseToken == "token_restore_dupe_123" })
        assertTrue(requestPurchases.any { it.purchaseToken == "token_history_only_123" })
        assertEquals(listOf("token_restore_dupe_123"), dependencies.acknowledgedTokens)
        assertFalse(dependencies.ledgerStore.isPosted("google:com.appactor.pro.yearly:yearly001:token_history_only_123"))
    }

    @Test
    fun `restore purchases leaves unresolved active purchases to sync path without reposting restored ones`() = runBlocking {
        val restoreResponse = fixtureRestoreResponse("fixtures/backend/google_restore_sample.json")
        val restoredSubscription = AppActorStorePurchase(
            productId = "com.appactor.pro.monthly",
            productType = AppActorProductType.Subscription,
            purchaseToken = "token_restore_followup_123",
            orderId = "GPA.restore.followup.1234",
            purchaseTimeMillis = 1_710_000_000_000,
            purchaseState = com.appactor.android.billing.AppActorStorePurchaseState.Purchased,
            basePlanId = "monthly001",
            offerId = "intro7d",
            isAcknowledged = false,
            isAutoRenewing = true,
            rawPurchaseData = "{\"purchaseToken\":\"token_restore_followup_123\"}",
            purchaseSignature = "signature_restore_followup_123",
        )
        val unresolvedInApp = AppActorStorePurchase(
            productId = "com.appactor.coins.100",
            productType = AppActorProductType.Unknown,
            purchaseToken = "token_restore_followup_coins_123",
            orderId = "GPA.restore.followup.coins.1234",
            purchaseTimeMillis = 1_710_000_100_000,
            purchaseState = com.appactor.android.billing.AppActorStorePurchaseState.Purchased,
            isAcknowledged = false,
            rawPurchaseData = "{\"purchaseToken\":\"token_restore_followup_coins_123\"}",
            purchaseSignature = "signature_restore_followup_coins_123",
        )
        val dependencies = createDependencies(
            receiptResponse = AppActorBackendHttpResponse(
                body = fixtureReceiptResponse("fixtures/backend/google_receipt_ok.json"),
                statusCode = 200,
                requestId = "req_receipt_followup",
                signatureVerified = true,
            ),
            offeringsEnvelope = fixtureOfferingsWithoutProduct("com.appactor.coins.100"),
            activePurchases = listOf(restoredSubscription, unresolvedInApp),
            restoreResponse = AppActorBackendHttpResponse(
                body = restoreResponse,
                statusCode = 200,
                requestId = restoreResponse.requestId,
                signatureVerified = true,
            ),
        )

        val info = dependencies.processor.restorePurchases()

        assertTrue(info.hasActiveEntitlement("premium"))
        val restorePurchases = dependencies.restoreRequests.single().purchases
        assertEquals(1, restorePurchases.size)
        assertEquals("token_restore_followup_123", restorePurchases.single().purchaseToken)
        assertTrue(dependencies.postedReceipts.isEmpty())
        assertTrue(dependencies.syncRequests.isEmpty())
        assertEquals(listOf("token_restore_followup_123"), dependencies.acknowledgedTokens)
        assertEquals(1, dependencies.queueStore.snapshot().size)
        assertEquals("com.appactor.coins.100", dependencies.queueStore.snapshot().single().productId)
        assertTrue(dependencies.ledgerStore.isPosted("google:com.appactor.pro.monthly:monthly001:token_restore_followup_123"))
    }

    @Test
    fun `restore purchases sends history candidates in multiple batches without dropping overflow`() = runBlocking {
        val restoreResponse = fixtureRestoreResponse("fixtures/backend/google_restore_sample.json")
        val historyPurchases = (0 until 1001).map { index ->
            com.appactor.android.billing.AppActorStorePurchaseHistoryRecord(
                productId = "com.appactor.pro.monthly",
                productType = AppActorProductType.Subscription,
                purchaseToken = "token_history_batch_$index",
                purchaseTimeMillis = 1_710_000_000_000L + index,
                basePlanId = "monthly001",
                offerId = "intro7d",
            )
        }
        val dependencies = createDependencies(
            receiptResponse = AppActorBackendHttpResponse(
                body = fixtureReceiptResponse("fixtures/backend/google_receipt_ok.json"),
                statusCode = 200,
                requestId = "unused",
                signatureVerified = true,
            ),
            historyPurchases = historyPurchases,
            restoreResponse = AppActorBackendHttpResponse(
                body = restoreResponse,
                statusCode = 200,
                requestId = restoreResponse.requestId,
                signatureVerified = true,
            ),
        )

        val info = dependencies.processor.restorePurchases()

        assertTrue(info.hasActiveEntitlement("premium"))
        assertEquals(3, dependencies.restoreRequests.size)
        assertEquals(listOf(500, 500, 1), dependencies.restoreRequests.map { it.purchases.size })
        assertEquals(1001, dependencies.restoreRequests.sumOf { it.purchases.size })
        assertEquals("token_history_batch_0", dependencies.restoreRequests.first().purchases.first().purchaseToken)
        assertEquals("token_history_batch_1000", dependencies.restoreRequests.last().purchases.last().purchaseToken)
        assertTrue(dependencies.acknowledgedTokens.isEmpty())
    }

    @Test
    fun `restore purchases fails fast when purchase history query fails`() = runBlocking {
        val dependencies = createDependencies(
            receiptResponse = AppActorBackendHttpResponse(
                body = fixtureReceiptResponse("fixtures/backend/google_receipt_ok.json"),
                statusCode = 200,
                requestId = "unused",
                signatureVerified = true,
            ),
            historyQueryError = AppActorError.Network(
                description = "Failed to query Google Play purchase history.",
                throwable = IllegalStateException("history down"),
            ),
        )

        val error = runCatching {
            dependencies.processor.restorePurchases()
        }.exceptionOrNull()

        assertTrue(error is AppActorError.Network)
        assertTrue(dependencies.restoreRequests.isEmpty())
        assertTrue(dependencies.postedReceipts.isEmpty())
        assertTrue(dependencies.fetchedCustomers.isEmpty())
    }

    @Test
    fun `restore purchases fails when a later history batch leaves remaining records unrecovered`() = runBlocking {
        val restoreResponse = fixtureRestoreResponse("fixtures/backend/google_restore_sample.json")
        val customerEnvelope = fixtureCustomerEnvelope("fixtures/backend/customer_android_active.json")
        val historyPurchases = (0 until 1001).map { index ->
            historyRecord(
                productId = "com.appactor.pro.monthly",
                purchaseToken = "token_history_partial_$index",
                purchaseTimeMillis = 1_710_000_000_000L + index,
            )
        }
        val dependencies = createDependencies(
            receiptResponse = AppActorBackendHttpResponse(
                body = fixtureReceiptResponse("fixtures/backend/google_receipt_ok.json"),
                statusCode = 200,
                requestId = "unused",
                signatureVerified = true,
            ),
            customerResponse = AppActorBackendHttpResponse(
                body = customerEnvelope,
                statusCode = 200,
                requestId = customerEnvelope.requestId,
                signatureVerified = true,
            ),
            historyPurchases = historyPurchases,
            restoreOutcomes = listOf(
                RestoreOutcome.Success(
                    AppActorBackendHttpResponse(
                        body = restoreResponse,
                        statusCode = 200,
                        requestId = restoreResponse.requestId,
                        signatureVerified = true,
                    )
                ),
                RestoreOutcome.Failure(
                    AppActorError.Network(
                        description = "restore batch failed",
                        throwable = IllegalStateException("batch two down"),
                    )
                ),
            ),
        )

        val error = runCatching {
            dependencies.processor.restorePurchases()
        }.exceptionOrNull()

        assertTrue(error is AppActorError.Network)
        assertEquals(2, dependencies.restoreRequests.size)
        assertEquals(listOf(500, 500), dependencies.restoreRequests.map { it.purchases.size })
        assertEquals(1, dependencies.fetchedCustomers.size)
        assertTrue(dependencies.postedReceipts.isEmpty())
    }

    @Test
    fun `restore purchases falls back to single receipt pipeline when bulk restore fails`() = runBlocking {
        val customerEnvelope = fixtureCustomerEnvelope("fixtures/backend/customer_android_active.json")
        val activePurchase = AppActorStorePurchase(
            productId = "com.appactor.pro.monthly",
            productType = AppActorProductType.Subscription,
            purchaseToken = "token_restore_fallback_123",
            orderId = "GPA.restore.fallback.1234",
            purchaseTimeMillis = 1_710_000_000_000,
            purchaseState = com.appactor.android.billing.AppActorStorePurchaseState.Purchased,
            basePlanId = "monthly001",
            offerId = "intro7d",
            isAcknowledged = false,
            isAutoRenewing = true,
            rawPurchaseData = "{\"purchaseToken\":\"token_restore_fallback_123\"}",
            purchaseSignature = "signature_restore_fallback_123",
        )
        val dependencies = createDependencies(
            receiptResponse = AppActorBackendHttpResponse(
                body = fixtureReceiptResponse("fixtures/backend/google_receipt_ok.json"),
                statusCode = 200,
                requestId = "req_receipt_fallback",
                signatureVerified = true,
            ),
            customerResponse = AppActorBackendHttpResponse(
                body = customerEnvelope,
                statusCode = 200,
                requestId = customerEnvelope.requestId,
                signatureVerified = true,
            ),
            activePurchases = listOf(activePurchase),
            restoreThrowable = IllegalStateException("restore down"),
        )

        val info = dependencies.processor.restorePurchases()

        assertTrue(info.hasActiveEntitlement("premium"))
        assertEquals(1, dependencies.restoreRequests.size)
        assertEquals(1, dependencies.syncRequests.size)
        assertTrue(dependencies.postedReceipts.isEmpty())
        assertEquals(1, dependencies.fetchedCustomers.size)
    }

    // region — MockK Identity Store Factory

    private fun createMockIdentityStore(
        initialAppUserId: String? = "user_android_123",
    ): AppActorIdentityStore {
        var storedAppUserId: String? = initialAppUserId
        var storedLastRequestId: String? = null

        val mock = mockk<AppActorIdentityStore>(relaxed = true)
        every { mock.currentAppUserId } answers { storedAppUserId }
        every { mock.installId } returns "install_123"
        every { mock.lastRequestId } answers { storedLastRequestId }
        every { mock.installReferrer } returns null
        every { mock.ensureAppUserId() } answers {
            storedAppUserId ?: "user_android_123".also { storedAppUserId = it }
        }
        every { mock.setAppUserId(any()) } answers { storedAppUserId = firstArg() }
        every { mock.setLastRequestId(any()) } answers { storedLastRequestId = firstArg() }
        every { mock.setInstallReferrer(any()) } answers { }
        every { mock.clearIdentity() } answers {
            storedAppUserId = null
            storedLastRequestId = null
        }
        return mock
    }

    // endregion

    // region — MockK Backend Client Factory

    private fun createMockBackendClient(
        offeringsResponse: AppActorBackendHttpResponse<AppActorOfferingsEnvelopeDTO>,
        receiptResponse: AppActorBackendHttpResponse<AppActorGoogleReceiptResponseDTO>,
        receiptResponses: List<AppActorBackendHttpResponse<AppActorGoogleReceiptResponseDTO>> = emptyList(),
        customerResponse: AppActorBackendHttpResponse<AppActorCustomerEnvelopeDTO>? = null,
        restoreResponse: AppActorBackendHttpResponse<AppActorGoogleRestoreResponseDTO>? = null,
        syncResponse: AppActorBackendHttpResponse<AppActorGoogleSyncResponseDTO>? = null,
        restoreThrowable: Throwable? = null,
        syncThrowable: Throwable? = null,
        restoreOutcomes: List<RestoreOutcome> = emptyList(),
        postReceiptStarted: CountDownLatch? = null,
        releasePostedReceipt: CountDownLatch? = null,
        postedReceipts: MutableList<AppActorGoogleReceiptRequestDTO>,
        fetchedCustomers: MutableList<String>,
        restoreRequests: MutableList<AppActorGoogleRestoreRequestDTO>,
        syncRequests: MutableList<AppActorGoogleSyncRequestDTO>,
        maxConcurrentReceiptPosts: AtomicInteger,
    ): AppActorBackendClient {
        val queuedReceiptResponses = ArrayDeque(receiptResponses)
        val queuedRestoreOutcomes = ArrayDeque(restoreOutcomes)
        val activeGooglePosts = AtomicInteger(0)
        var currentOfferingsResponse = offeringsResponse

        val mock = mockk<AppActorBackendClient>()

        coEvery { mock.identify(any()) } throws IllegalStateException("Unused in payment processor tests.")
        coEvery { mock.login(any()) } throws IllegalStateException("Unused in payment processor tests.")

        coEvery { mock.getOfferings(any()) } coAnswers { currentOfferingsResponse }

        coEvery { mock.getCustomer(any(), any()) } coAnswers {
            val appUserId: String = firstArg()
            fetchedCustomers += appUserId
            customerResponse ?: error("No fake customer response configured.")
        }

        coEvery { mock.getRemoteConfigs(any(), any(), any(), any()) } throws IllegalStateException("Unused in payment processor tests.")
        coEvery { mock.postExperimentAssignment(any(), any(), any(), any()) } throws IllegalStateException("Unused in payment processor tests.")

        coEvery { mock.postGoogleReceipt(any()) } coAnswers {
            val request: AppActorGoogleReceiptRequestDTO = firstArg()
            val inFlight = activeGooglePosts.incrementAndGet()
            maxConcurrentReceiptPosts.updateAndGet { current -> maxOf(current, inFlight) }
            try {
                postReceiptStarted?.countDown()
                releasePostedReceipt?.await(5, TimeUnit.SECONDS)
                postedReceipts += request
                if (queuedReceiptResponses.isNotEmpty()) {
                    queuedReceiptResponses.removeFirst()
                } else {
                    receiptResponse
                }
            } finally {
                activeGooglePosts.decrementAndGet()
            }
        }

        coEvery { mock.postGoogleRestore(any()) } coAnswers {
            val request: AppActorGoogleRestoreRequestDTO = firstArg()
            restoreRequests += request
            if (queuedRestoreOutcomes.isNotEmpty()) {
                when (val outcome = queuedRestoreOutcomes.removeFirst()) {
                    is RestoreOutcome.Success -> outcome.response
                    is RestoreOutcome.Failure -> throw outcome.throwable
                }
            } else {
                restoreThrowable?.let { throw it }
                restoreResponse ?: error("No fake restore response configured.")
            }
        }

        coEvery { mock.postGoogleSync(any()) } coAnswers {
            val request: AppActorGoogleSyncRequestDTO = firstArg()
            val inFlight = activeGooglePosts.incrementAndGet()
            maxConcurrentReceiptPosts.updateAndGet { current -> maxOf(current, inFlight) }
            try {
                postReceiptStarted?.countDown()
                releasePostedReceipt?.await(5, TimeUnit.SECONDS)
                syncRequests += request
                syncThrowable?.let { throw it }
                syncResponse?.let { return@coAnswers it }
                restoreResponse?.let {
                    return@coAnswers AppActorBackendHttpResponse(
                        body = AppActorGoogleSyncResponseDTO(
                            customer = it.body?.customer ?: error("No fake restore customer configured."),
                            syncedCount = it.body?.restoredCount ?: request.purchases.size,
                            transferred = it.body?.transferred ?: false,
                            results = it.body?.results ?: emptyList(),
                            requestId = it.body?.requestId,
                        ),
                        statusCode = it.statusCode,
                        requestId = it.requestId,
                        eTag = it.eTag,
                        isNotModified = it.isNotModified,
                        signatureHeaders = it.signatureHeaders,
                        signatureVerified = it.signatureVerified,
                    )
                }
                val receiptCustomer = receiptResponse.body?.customer
                if (receiptCustomer != null) {
                    return@coAnswers AppActorBackendHttpResponse(
                        body = AppActorGoogleSyncResponseDTO(
                            customer = receiptCustomer,
                            syncedCount = request.purchases.size,
                            transferred = false,
                            results = request.purchases.map { purchase ->
                                AppActorGoogleBatchResultDTO(
                                    purchaseToken = purchase.purchaseToken,
                                    productId = purchase.productId,
                                    basePlanId = purchase.basePlanId,
                                    offerId = purchase.offerId,
                                    status = "synced",
                                )
                            },
                            requestId = receiptResponse.body?.requestId,
                        ),
                        statusCode = receiptResponse.statusCode,
                        requestId = receiptResponse.requestId,
                        eTag = receiptResponse.eTag,
                        isNotModified = receiptResponse.isNotModified,
                        signatureHeaders = receiptResponse.signatureHeaders,
                        signatureVerified = receiptResponse.signatureVerified,
                    )
                }
                error("No fake sync response configured.")
            } finally {
                activeGooglePosts.decrementAndGet()
            }
        }

        return mock
    }

    // endregion

    // region — MockK Store Adapter Factory

    private fun createMockStoreAdapter(
        activePurchases: List<AppActorStorePurchase> = emptyList(),
        historyPurchases: List<com.appactor.android.billing.AppActorStorePurchaseHistoryRecord> = emptyList(),
        directPurchaseRequests: Map<String, AppActorStoreProductRequest> = emptyMap(),
        directPurchaseResolutionError: Throwable? = null,
        historyQueryError: Throwable? = null,
        storefront: AppActorStorefront? = null,
        acknowledgedTokens: MutableList<String>,
        consumedTokens: MutableList<String>,
    ): AppActorStoreAdapter {
        val mock = mockk<AppActorStoreAdapter>()

        coEvery { mock.connect() } returns Unit
        every { mock.shutdown() } returns Unit

        coEvery { mock.queryProductDetails(any()) } coAnswers {
            val requests: List<AppActorStoreProductRequest> = firstArg()
            requests.map { request ->
                AppActorStoreProduct(
                    productId = request.productId,
                    productType = request.productType,
                    basePlanId = request.basePlanId,
                    offerId = request.offerId,
                    localizedPrice = if (request.productType == AppActorProductType.Subscription) "$4.99" else "$1.99",
                    priceAmountMicros = if (request.productType == AppActorProductType.Subscription) 4_990_000 else 1_990_000,
                    currencyCode = "USD",
                )
            }
        }

        coEvery { mock.launchPurchase(any(), any()) } coAnswers {
            val request: AppActorStoreProductRequest = secondArg()
            AppActorStorePurchaseLaunchResult.Purchased(
                purchases = listOf(
                    AppActorStorePurchase(
                        productId = request.productId,
                        productType = request.productType,
                        purchaseToken = "token_123",
                        orderId = "GPA.1234",
                        purchaseTimeMillis = 1_710_000_000_000,
                        purchaseState = com.appactor.android.billing.AppActorStorePurchaseState.Purchased,
                        basePlanId = request.basePlanId,
                        offerId = request.offerId,
                        priceAmountMicros = if (request.productType == AppActorProductType.Subscription) 4_990_000 else 1_990_000,
                        currencyCode = "USD",
                        isAcknowledged = false,
                        isAutoRenewing = true,
                        obfuscatedAccountId = request.obfuscatedAccountId,
                        rawPurchaseData = "{\"purchaseToken\":\"token_123\"}",
                        purchaseSignature = "signature_123",
                    )
                )
            )
        }

        every { mock.purchaseUpdates() } returns emptyFlow()
        every { mock.currentStorefront() } returns storefront

        coEvery { mock.resolveDirectPurchaseRequest(any()) } coAnswers {
            val request: AppActorStoreProductRequest = firstArg()
            directPurchaseResolutionError?.let { throw it }
            val direct = directPurchaseRequests[request.productId]
            if (direct != null) {
                return@coAnswers direct.copy(obfuscatedAccountId = request.obfuscatedAccountId)
            }
            val matched = activePurchases.firstOrNull { it.productId == request.productId }
            if (
                request.productType != AppActorProductType.Unknown ||
                !request.basePlanId.isNullOrBlank() ||
                !request.offerId.isNullOrBlank()
            ) {
                return@coAnswers request.copy(
                    productType = if (request.productType != AppActorProductType.Unknown) {
                        request.productType
                    } else {
                        matched?.productType ?: request.productType
                    },
                    basePlanId = request.basePlanId ?: matched?.basePlanId,
                    offerId = request.offerId ?: matched?.offerId,
                )
            }
            request.copy(
                productId = request.productId,
                productType = matched?.productType ?: AppActorProductType.NonConsumable,
                basePlanId = matched?.basePlanId,
                offerId = matched?.offerId,
            )
        }

        coEvery { mock.queryActivePurchases() } returns activePurchases

        coEvery { mock.queryPurchaseHistory() } coAnswers {
            historyQueryError?.let { throw it }
            historyPurchases
        }

        coEvery { mock.acknowledgePurchase(any()) } coAnswers {
            acknowledgedTokens += firstArg<String>()
        }

        coEvery { mock.consumePurchase(any()) } coAnswers {
            consumedTokens += firstArg<String>()
        }

        return mock
    }

    // endregion

    // region — Dependencies

    private fun createDependencies(
        receiptResponse: AppActorBackendHttpResponse<AppActorGoogleReceiptResponseDTO>,
        customerResponse: AppActorBackendHttpResponse<AppActorCustomerEnvelopeDTO>? = null,
        activePurchases: List<AppActorStorePurchase> = emptyList(),
        historyPurchases: List<com.appactor.android.billing.AppActorStorePurchaseHistoryRecord> = emptyList(),
        restoreResponse: AppActorBackendHttpResponse<AppActorGoogleRestoreResponseDTO>? = null,
        syncResponse: AppActorBackendHttpResponse<AppActorGoogleSyncResponseDTO>? = null,
        restoreThrowable: Throwable? = null,
        syncThrowable: Throwable? = null,
        restoreOutcomes: List<RestoreOutcome> = emptyList(),
        receiptResponses: List<AppActorBackendHttpResponse<AppActorGoogleReceiptResponseDTO>> = emptyList(),
        directPurchaseRequests: Map<String, AppActorStoreProductRequest> = emptyMap(),
        directPurchaseResolutionError: Throwable? = null,
        historyQueryError: Throwable? = null,
        storefront: AppActorStorefront? = null,
        pipelineEvents: MutableList<AppActorReceiptPipelineEvent>? = null,
        offeringsEnvelope: AppActorOfferingsEnvelopeDTO = fixtureOfferings(),
        directory: File? = null,
        identityStore: AppActorIdentityStore = createMockIdentityStore(),
        postReceiptStarted: CountDownLatch? = null,
        releasePostedReceipt: CountDownLatch? = null,
        dateProviderMillis: () -> Long = { System.currentTimeMillis() },
    ): Dependencies {
        val actualDirectory = directory ?: File(context.filesDir, "tests/payment-${UUID.randomUUID()}").apply { mkdirs() }
        val postedReceipts = mutableListOf<AppActorGoogleReceiptRequestDTO>()
        val fetchedCustomers = mutableListOf<String>()
        val restoreRequests = mutableListOf<AppActorGoogleRestoreRequestDTO>()
        val syncRequests = mutableListOf<AppActorGoogleSyncRequestDTO>()
        val maxConcurrentReceiptPosts = AtomicInteger(0)
        val acknowledgedTokens = mutableListOf<String>()
        val consumedTokens = mutableListOf<String>()

        val backendClient = createMockBackendClient(
            offeringsResponse = AppActorBackendHttpResponse(
                body = offeringsEnvelope,
                statusCode = 200,
                requestId = offeringsEnvelope.requestId,
                signatureVerified = true,
            ),
            receiptResponse = receiptResponse,
            receiptResponses = receiptResponses,
            customerResponse = customerResponse,
            restoreResponse = restoreResponse,
            syncResponse = syncResponse,
            restoreThrowable = restoreThrowable,
            syncThrowable = syncThrowable,
            restoreOutcomes = restoreOutcomes,
            postReceiptStarted = postReceiptStarted,
            releasePostedReceipt = releasePostedReceipt,
            postedReceipts = postedReceipts,
            fetchedCustomers = fetchedCustomers,
            restoreRequests = restoreRequests,
            syncRequests = syncRequests,
            maxConcurrentReceiptPosts = maxConcurrentReceiptPosts,
        )
        val storeAdapter = createMockStoreAdapter(
            activePurchases = activePurchases,
            historyPurchases = historyPurchases,
            directPurchaseRequests = directPurchaseRequests,
            directPurchaseResolutionError = directPurchaseResolutionError,
            historyQueryError = historyQueryError,
            storefront = storefront,
            acknowledgedTokens = acknowledgedTokens,
            consumedTokens = consumedTokens,
        )
        val offlineProductCatalogStore = AppActorOfflineProductCatalogStore(
            AppActorETagManager(
                diskStore = AppActorCacheDiskStore(context, File(context.cacheDir, "tests/offline-product-${UUID.randomUUID()}")),
                responseVerificationEnabled = false,
            )
        )
        val offeringsManager = AppActorOfferingsManager(
            backendClient = backendClient,
            cacheStore = AppActorOfferingsCacheStore(
                AppActorETagManager(
                    diskStore = AppActorCacheDiskStore(context, File(context.cacheDir, "tests/off-${UUID.randomUUID()}")),
                    responseVerificationEnabled = false,
                )
            ),
            offlineProductCatalogStore = offlineProductCatalogStore,
            storeAdapter = storeAdapter,
        )
        runBlocking { offeringsManager.getOfferings() }
        val customerCacheStore = AppActorCustomerCacheStore(
            AppActorETagManager(
                diskStore = AppActorCacheDiskStore(context, File(context.cacheDir, "tests/customer-${UUID.randomUUID()}")),
                responseVerificationEnabled = false,
            )
        )
        val customerManager = AppActorCustomerManager(
            configuration = AppActorConfiguration(
                context = context,
                apiKey = "pk_test_123",
                appUserId = "user_android_123",
                environment = AppActorEnvironment.Production,
            ),
            backendClient = backendClient,
            cacheStore = customerCacheStore,
            identityStore = identityStore,
            offeringsManager = offeringsManager,
            offlineProductCatalogStore = offlineProductCatalogStore,
            storeAdapter = storeAdapter,
        )
        val queueStore = AppActorAtomicJsonReceiptQueueStore(context, actualDirectory)
        val ledgerStore = AppActorAtomicJsonPostedLedgerStore(context, actualDirectory)
        val processor = AppActorPaymentProcessor(
            configuration = AppActorConfiguration(
                context = context,
                apiKey = "pk_test_123",
                appUserId = "user_android_123",
                environment = AppActorEnvironment.Production,
            ),
            backendClient = backendClient,
            storeAdapter = storeAdapter,
            queueStore = queueStore,
            postedLedgerStore = ledgerStore,
            customerManager = customerManager,
            identityStore = identityStore,
            offeringsManager = offeringsManager,
            offlineProductCatalogStore = offlineProductCatalogStore,
            packageName = context.packageName,
            onPipelineEvent = { event -> pipelineEvents?.add(event) },
            dateProviderMillis = dateProviderMillis,
        )

        return Dependencies(
            processor = processor,
            backendClient = backendClient,
            storeAdapter = storeAdapter,
            queueStore = queueStore,
            ledgerStore = ledgerStore,
            offeringsManager = offeringsManager,
            directory = actualDirectory,
            identityStore = identityStore,
            postedReceipts = postedReceipts,
            fetchedCustomers = fetchedCustomers,
            restoreRequests = restoreRequests,
            syncRequests = syncRequests,
            maxConcurrentReceiptPosts = maxConcurrentReceiptPosts,
            acknowledgedTokens = acknowledgedTokens,
            consumedTokens = consumedTokens,
        )
    }

    // endregion

    // region — Helpers

    private fun monthlyPackage(): AppActorPackage {
        return AppActorPackage(
            id = "pkg_pro_monthly",
            packageType = AppActorPackageType.Monthly,
            store = AppActorStore.PlayStore,
            productId = "com.appactor.pro.monthly",
            productType = AppActorProductType.Subscription,
            basePlanId = "monthly001",
            offerId = "intro7d",
        )
    }

    private fun historyRecord(
        productId: String = "com.appactor.pro.monthly",
        purchaseToken: String = "token_history_123",
        purchaseTimeMillis: Long = 1_710_000_000_000L,
        productType: AppActorProductType = AppActorProductType.Subscription,
        basePlanId: String? = "monthly001",
        offerId: String? = "intro7d",
    ): com.appactor.android.billing.AppActorStorePurchaseHistoryRecord {
        return com.appactor.android.billing.AppActorStorePurchaseHistoryRecord(
            productId = productId,
            productType = productType,
            purchaseToken = purchaseToken,
            orderId = "GPA.history.${purchaseToken.takeLast(6)}",
            purchaseTimeMillis = purchaseTimeMillis,
            basePlanId = basePlanId,
            offerId = offerId,
            isAutoRenewing = productType == AppActorProductType.Subscription,
            rawPurchaseData = "{\"purchaseToken\":\"$purchaseToken\"}",
            purchaseSignature = "signature_$purchaseToken",
        )
    }

    private fun fixtureOfferings(): AppActorOfferingsEnvelopeDTO {
        val payload = requireNotNull(
            javaClass.classLoader?.getResource("fixtures/backend/offerings_android_sample.json")
        ).readText()
        return AppActorBackendJson.instance.decodeFromString(payload)
    }

    private fun fixtureOfferingsWithoutProduct(productId: String): AppActorOfferingsEnvelopeDTO {
        val base = fixtureOfferings()
        fun AppActorOfferingDTO.filtered(): AppActorOfferingDTO {
            return copy(
                packages = packages.mapNotNull { pkg ->
                    val filteredProducts = pkg.products.filterNot { it.productId == productId }
                    if (filteredProducts.isEmpty()) {
                        null
                    } else {
                        pkg.copy(products = filteredProducts)
                    }
                }
            )
        }

        return base.copy(
            data = base.data.copy(
                currentOffering = base.data.currentOffering?.filtered(),
                offerings = base.data.offerings.map { it.filtered() },
                productEntitlements = base.data.productEntitlements.filterKeys { key ->
                    !key.contains(productId)
                },
            )
        )
    }

    private fun fixtureReceiptResponse(path: String): AppActorGoogleReceiptResponseDTO {
        val payload = requireNotNull(javaClass.classLoader?.getResource(path)).readText()
        return AppActorBackendJson.instance.decodeFromString(payload)
    }

    private fun fixtureCustomerEnvelope(path: String): AppActorCustomerEnvelopeDTO {
        val payload = requireNotNull(javaClass.classLoader?.getResource(path)).readText()
        return AppActorBackendJson.instance.decodeFromString(payload)
    }

    private fun fixtureRestoreResponse(path: String): AppActorGoogleRestoreResponseDTO {
        val payload = requireNotNull(javaClass.classLoader?.getResource(path)).readText()
        return AppActorBackendJson.instance.decodeFromString(payload)
    }

    // endregion

    // region — Data Classes

    private data class Dependencies(
        val processor: AppActorPaymentProcessor,
        val backendClient: AppActorBackendClient,
        val storeAdapter: AppActorStoreAdapter,
        val queueStore: AppActorAtomicJsonReceiptQueueStore,
        val ledgerStore: AppActorAtomicJsonPostedLedgerStore,
        val offeringsManager: AppActorOfferingsManager,
        val directory: File,
        val identityStore: AppActorIdentityStore,
        val postedReceipts: MutableList<AppActorGoogleReceiptRequestDTO>,
        val fetchedCustomers: MutableList<String>,
        val restoreRequests: MutableList<AppActorGoogleRestoreRequestDTO>,
        val syncRequests: MutableList<AppActorGoogleSyncRequestDTO>,
        val maxConcurrentReceiptPosts: AtomicInteger,
        val acknowledgedTokens: MutableList<String>,
        val consumedTokens: MutableList<String>,
    )

    private sealed interface RestoreOutcome {
        data class Success(
            val response: AppActorBackendHttpResponse<AppActorGoogleRestoreResponseDTO>,
        ) : RestoreOutcome

        data class Failure(
            val throwable: Throwable,
        ) : RestoreOutcome
    }

    // endregion
}
