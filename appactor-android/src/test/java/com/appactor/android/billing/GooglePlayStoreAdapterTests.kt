package com.appactor.android.billing

import android.app.Activity
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.appactor.android.internal.logging.AppActorLogger
import com.appactor.android.models.AppActorPricingPhase
import com.appactor.android.models.AppActorProductType
import com.appactor.android.models.AppActorRecurrenceMode
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GooglePlayStoreAdapterTests {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    /**
     * Creates a MockK-based AppActorGoogleBillingClient with configurable behavior.
     *
     * Captures launchPurchase arguments (offerToken, oldPurchaseToken, replacementMode)
     * for assertion via the returned [MockBillingClientCaptures].
     */
    private fun createMockBillingClient(
        productDetails: List<AppActorBillingProductDetailsPayload> = emptyList(),
        activePurchasesByType: Map<AppActorProductType, List<AppActorBillingPurchasePayload>> = emptyMap(),
        purchaseHistoryByType: Map<AppActorProductType, List<AppActorBillingPurchaseHistoryPayload>> = emptyMap(),
        launchResult: AppActorBillingLaunchResult = AppActorBillingLaunchResult.Cancelled,
        purchaseUpdates: Flow<AppActorBillingPurchaseUpdate> = emptyFlow(),
    ): Pair<AppActorGoogleBillingClient, MockBillingClientCaptures> {
        val offerTokenSlot = slot<String?>()
        val oldPurchaseTokenSlot = slot<String?>()
        val replacementModeSlot = slot<Int?>()
        val queryProductsCalls = mutableListOf<List<AppActorBillingQueryProduct>>()

        val mock = mockk<AppActorGoogleBillingClient>(relaxed = true)

        coEvery { mock.queryProductDetails(any()) } coAnswers {
            val requestedProducts = firstArg<List<AppActorBillingQueryProduct>>()
            queryProductsCalls += requestedProducts
            productDetails.filter { payload ->
                requestedProducts.any { requested ->
                    requested.productId == payload.productId &&
                        requested.matches(payload)
                }
            }
        }

        coEvery {
            mock.launchPurchase(
                activity = any(),
                productDetails = any(),
                productType = any(),
                offerToken = captureNullable(offerTokenSlot),
                obfuscatedAccountId = any(),
                oldPurchaseToken = captureNullable(oldPurchaseTokenSlot),
                replacementMode = captureNullable(replacementModeSlot),
            )
        } returns launchResult

        coEvery { mock.queryPurchases(any()) } coAnswers {
            val productType = firstArg<AppActorProductType>()
            activePurchasesByType[productType].orEmpty()
        }

        coEvery { mock.queryPurchaseHistory(any()) } coAnswers {
            val productType = firstArg<AppActorProductType>()
            purchaseHistoryByType[productType].orEmpty()
        }

        every { mock.purchaseUpdates() } returns purchaseUpdates

        val captures = MockBillingClientCaptures(
            offerTokenSlot = offerTokenSlot,
            oldPurchaseTokenSlot = oldPurchaseTokenSlot,
            replacementModeSlot = replacementModeSlot,
            queryProductsCalls = queryProductsCalls,
        )

        return mock to captures
    }

    private data class MockBillingClientCaptures(
        val offerTokenSlot: io.mockk.CapturingSlot<String?>,
        val oldPurchaseTokenSlot: io.mockk.CapturingSlot<String?>,
        val replacementModeSlot: io.mockk.CapturingSlot<Int?>,
        val queryProductsCalls: List<List<AppActorBillingQueryProduct>>,
    ) {
        val lastLaunchOfferToken: String?
            get() = if (offerTokenSlot.isCaptured) offerTokenSlot.captured else null
        val lastLaunchOldPurchaseToken: String?
            get() = if (oldPurchaseTokenSlot.isCaptured) oldPurchaseTokenSlot.captured else null
        val lastLaunchReplacementMode: Int?
            get() = if (replacementModeSlot.isCaptured) replacementModeSlot.captured else null
    }

    @Test
    fun `query product details resolves matching base plan and offer`() = kotlinx.coroutines.runBlocking {
        val (billingClient, _) = createMockBillingClient(
            productDetails = listOf(
                AppActorBillingProductDetailsPayload(
                    productId = "com.appactor.pro.monthly",
                    productType = AppActorProductType.Subscription,
                    title = "Pro Monthly",
                    displayName = "AppActor Pro Monthly",
                    description = "desc",
                    subscriptionOffers = listOf(
                        AppActorBillingSubscriptionOfferPayload(
                            basePlanId = "monthly001",
                            offerId = null,
                            offerToken = "base-plan-token",
                            pricingPhases = listOf(
                                AppActorPricingPhase(
                                    formattedPrice = "$9.99",
                                    priceAmountMicros = 9_990_000,
                                    currencyCode = "USD",
                                ),
                            ),
                        ),
                        AppActorBillingSubscriptionOfferPayload(
                            basePlanId = "monthly001",
                            offerId = "intro7d",
                            offerToken = "intro-token",
                            pricingPhases = listOf(
                                AppActorPricingPhase(
                                    formattedPrice = "$4.99",
                                    priceAmountMicros = 4_990_000,
                                    currencyCode = "USD",
                                ),
                            ),
                        ),
                    ),
                )
            )
        )
        val adapter = GooglePlayStoreAdapter(context, billingClient)

        val products = adapter.queryProductDetails(
            listOf(
                AppActorStoreProductRequest(
                    productId = "com.appactor.pro.monthly",
                    productType = AppActorProductType.Subscription,
                    basePlanId = "monthly001",
                    offerId = "intro7d",
                )
            )
        )

        assertEquals(1, products.size)
        assertEquals("$4.99", products.first().localizedPrice)
        assertEquals(4_990_000L, products.first().priceAmountMicros)
        assertEquals("USD", products.first().currencyCode)
        assertEquals("monthly001", products.first().basePlanId)
        assertEquals("intro7d", products.first().offerId)
    }

    @Test
    fun `auto-selected free-trial offer exposes its pricing phases on the resolved product`() = kotlinx.coroutines.runBlocking {
        val (billingClient, _) = createMockBillingClient(
            productDetails = listOf(
                AppActorBillingProductDetailsPayload(
                    productId = "com.appactor.pro.weekly",
                    productType = AppActorProductType.Subscription,
                    subscriptionOffers = listOf(
                        AppActorBillingSubscriptionOfferPayload(
                            basePlanId = "weekly001",
                            offerId = null,
                            offerToken = "base-plan-token",
                            pricingPhases = listOf(
                                AppActorPricingPhase(
                                    billingPeriod = "P1W",
                                    priceAmountMicros = 39_990_000,
                                    formattedPrice = "₺39.99",
                                    currencyCode = "TRY",
                                    recurrenceMode = AppActorRecurrenceMode.InfiniteRecurring,
                                ),
                            ),
                        ),
                        AppActorBillingSubscriptionOfferPayload(
                            basePlanId = "weekly001",
                            offerId = "trial3d",
                            offerToken = "trial-token",
                            pricingPhases = listOf(
                                AppActorPricingPhase(
                                    billingPeriod = "P3D",
                                    priceAmountMicros = 0,
                                    formattedPrice = "Free",
                                    currencyCode = "TRY",
                                    billingCycleCount = 1,
                                    recurrenceMode = AppActorRecurrenceMode.FiniteRecurring,
                                ),
                                AppActorPricingPhase(
                                    billingPeriod = "P1W",
                                    priceAmountMicros = 39_990_000,
                                    formattedPrice = "₺39.99",
                                    currencyCode = "TRY",
                                    recurrenceMode = AppActorRecurrenceMode.InfiniteRecurring,
                                ),
                            ),
                        ),
                    ),
                )
            )
        )
        val adapter = GooglePlayStoreAdapter(context, billingClient)

        val products = adapter.queryProductDetails(
            listOf(
                AppActorStoreProductRequest(
                    productId = "com.appactor.pro.weekly",
                    productType = AppActorProductType.Subscription,
                    basePlanId = "weekly001",
                    offerId = null,
                )
            )
        )

        val resolved = products.single()
        assertEquals("trial3d", resolved.offerId)
        assertEquals(2, resolved.pricingPhases.size)
        val trialPhase = resolved.pricingPhases.first()
        assertTrue(trialPhase.isFreeTrial)
        assertEquals("P3D", trialPhase.billingPeriod)
        assertEquals(3, trialPhase.period?.numberOfUnits)
        // Existing single-price fields still reflect the recurring price, unchanged.
        assertEquals("₺39.99", resolved.localizedPrice)
        assertEquals(39_990_000L, resolved.priceAmountMicros)
    }

    @Test
    fun `query product details resolves subscriptions even when request type is unknown`() = kotlinx.coroutines.runBlocking {
        val (billingClient, _) = createMockBillingClient(
            productDetails = listOf(
                AppActorBillingProductDetailsPayload(
                    productId = "com.appactor.pro.monthly",
                    productType = AppActorProductType.Subscription,
                    subscriptionOffers = listOf(
                        AppActorBillingSubscriptionOfferPayload(
                            basePlanId = "monthly001",
                            offerId = "intro7d",
                            offerToken = "intro-token",
                            pricingPhases = listOf(AppActorPricingPhase(formattedPrice = "$4.99")),
                        )
                    ),
                )
            )
        )
        val adapter = GooglePlayStoreAdapter(context, billingClient)

        val products = adapter.queryProductDetails(
            listOf(
                AppActorStoreProductRequest(
                    productId = "com.appactor.pro.monthly",
                    productType = AppActorProductType.Unknown,
                    basePlanId = "monthly001",
                    offerId = "intro7d",
                )
            )
        )

        assertEquals(1, products.size)
        assertEquals(AppActorProductType.Subscription, products.single().productType)
        assertEquals("intro7d", products.single().offerId)
    }

    @Test
    fun `query product details resolves ambiguous request when only one subscription offer exists`() = kotlinx.coroutines.runBlocking {
        val (billingClient, captures) = createMockBillingClient(
            productDetails = listOf(
                AppActorBillingProductDetailsPayload(
                    productId = "com.appactor.pro.monthly",
                    productType = AppActorProductType.Subscription,
                    subscriptionOffers = listOf(
                        AppActorBillingSubscriptionOfferPayload(
                            basePlanId = "monthly001",
                            offerId = "intro7d",
                            offerToken = "intro-token",
                            pricingPhases = listOf(AppActorPricingPhase(formattedPrice = "$4.99")),
                        )
                    ),
                )
            )
        )
        val adapter = GooglePlayStoreAdapter(context, billingClient)

        val products = adapter.queryProductDetails(
            listOf(
                AppActorStoreProductRequest(
                    productId = "com.appactor.pro.monthly",
                    productType = AppActorProductType.Unknown,
                )
            )
        )

        assertEquals(1, products.size)
        assertEquals(AppActorProductType.Subscription, products.single().productType)
        assertEquals("intro7d", products.single().offerId)
        assertEquals(2, captures.queryProductsCalls.size)
    }

    @Test
    fun `query product details resolves ambiguous request when only one inapp payload exists`() = kotlinx.coroutines.runBlocking {
        val (billingClient, captures) = createMockBillingClient(
            productDetails = listOf(
                AppActorBillingProductDetailsPayload(
                    productId = "com.appactor.coins.100",
                    productType = AppActorProductType.Consumable,
                    oneTimeOffer = AppActorStorePricing(
                        formattedPrice = "$1.99",
                        priceAmountMicros = 1_990_000,
                        currencyCode = "USD",
                    ),
                )
            )
        )
        val adapter = GooglePlayStoreAdapter(context, billingClient)

        val products = adapter.queryProductDetails(
            listOf(
                AppActorStoreProductRequest(
                    productId = "com.appactor.coins.100",
                    productType = AppActorProductType.Unknown,
                )
            )
        )

        assertEquals(1, products.size)
        assertEquals("com.appactor.coins.100", products.single().productId)
        assertEquals("$1.99", products.single().localizedPrice)
        assertEquals(2, captures.queryProductsCalls.size)
    }

    @Test
    fun `query product details maps one time products`() = kotlinx.coroutines.runBlocking {
        val (billingClient, _) = createMockBillingClient(
            productDetails = listOf(
                AppActorBillingProductDetailsPayload(
                    productId = "com.appactor.coins.100",
                    productType = AppActorProductType.Consumable,
                    title = "100 Coins",
                    displayName = "100 Coins",
                    description = "desc",
                    oneTimeOffer = AppActorStorePricing(
                        formattedPrice = "$1.99",
                        priceAmountMicros = 1_990_000,
                        currencyCode = "USD",
                    ),
                )
            )
        )
        val adapter = GooglePlayStoreAdapter(context, billingClient)

        val products = adapter.queryProductDetails(
            listOf(
                AppActorStoreProductRequest(
                    productId = "com.appactor.coins.100",
                    productType = AppActorProductType.Consumable,
                )
            )
        )

        assertEquals("$1.99", products.first().localizedPrice)
        assertEquals(1_990_000L, products.first().priceAmountMicros)
        assertEquals("USD", products.first().currencyCode)
        assertNull(products.first().offerId)
    }

    @Test
    fun `query product details falls back from subs to inapp for one time products`() = kotlinx.coroutines.runBlocking {
        val (billingClient, captures) = createMockBillingClient(
            productDetails = listOf(
                AppActorBillingProductDetailsPayload(
                    productId = "com.appactor.coins.100",
                    productType = AppActorProductType.Consumable,
                    oneTimeOffer = AppActorStorePricing(
                        formattedPrice = "$1.99",
                        priceAmountMicros = 1_990_000,
                        currencyCode = "USD",
                    ),
                )
            )
        )
        val adapter = GooglePlayStoreAdapter(context, billingClient)

        val products = adapter.queryProductDetails(
            listOf(
                AppActorStoreProductRequest(
                    productId = "com.appactor.coins.100",
                    productType = AppActorProductType.Consumable,
                )
            )
        )

        assertEquals(1, products.size)
        assertEquals(AppActorProductType.Consumable, products.single().productType)
        assertEquals(2, captures.queryProductsCalls.size)
        assertEquals(AppActorProductType.Subscription, captures.queryProductsCalls[0].single().productType)
        assertEquals(AppActorProductType.NonConsumable, captures.queryProductsCalls[1].single().productType)
    }

    @Test
    fun `query product details logs unresolved product references`() = kotlinx.coroutines.runBlocking {
        val previousHandler = AppActorLogger.logHandler
        val messages = mutableListOf<String>()
        AppActorLogger.logHandler = { _, message, _, _ -> messages += message }
        try {
            val (billingClient, _) = createMockBillingClient(
                productDetails = listOf(
                    AppActorBillingProductDetailsPayload(
                        productId = "com.appactor.pro.monthly",
                        productType = AppActorProductType.Subscription,
                        subscriptionOffers = listOf(
                            AppActorBillingSubscriptionOfferPayload(
                                basePlanId = "monthly001",
                                offerId = "intro7d",
                                offerToken = "intro",
                            )
                        ),
                    )
                )
            )
            val adapter = GooglePlayStoreAdapter(context, billingClient)

            val products = adapter.queryProductDetails(
                listOf(
                    AppActorStoreProductRequest(
                        productId = "com.appactor.pro.monthly",
                        productType = AppActorProductType.Subscription,
                        basePlanId = "monthly001",
                        offerId = "missing-offer",
                    )
                )
            )

            assertTrue(products.isEmpty())
            assertTrue(messages.any { it.contains("No matching Play subscription offer") })
            assertTrue(messages.any { it.contains("productId=com.appactor.pro.monthly") && it.contains("offerId=missing-offer") })
        } finally {
            AppActorLogger.logHandler = previousHandler
        }
    }

    @Test
    fun `query product details keeps mixed type ambiguous requests unresolved after querying both catalogs`() = kotlinx.coroutines.runBlocking {
        val previousHandler = AppActorLogger.logHandler
        val messages = mutableListOf<String>()
        AppActorLogger.logHandler = { _, message, _, _ -> messages += message }
        try {
            val (billingClient, captures) = createMockBillingClient(
                productDetails = listOf(
                    AppActorBillingProductDetailsPayload(
                        productId = "com.appactor.mixed",
                        productType = AppActorProductType.Subscription,
                        subscriptionOffers = listOf(
                            AppActorBillingSubscriptionOfferPayload(
                                basePlanId = "monthly001",
                                offerId = "intro7d",
                                offerToken = "intro",
                            )
                        ),
                    ),
                    AppActorBillingProductDetailsPayload(
                        productId = "com.appactor.mixed",
                        productType = AppActorProductType.Consumable,
                        oneTimeOffer = AppActorStorePricing(formattedPrice = "$1.99"),
                    ),
                )
            )
            val adapter = GooglePlayStoreAdapter(context, billingClient)

            val products = adapter.queryProductDetails(
                listOf(
                    AppActorStoreProductRequest(
                        productId = "com.appactor.mixed",
                        productType = AppActorProductType.Unknown,
                    )
                )
            )

            assertTrue(products.isEmpty())
            assertEquals(2, captures.queryProductsCalls.size)
            assertTrue(messages.any { it.contains("matched both SUBS and INAPP") && it.contains("com.appactor.mixed") })
        } finally {
            AppActorLogger.logHandler = previousHandler
        }
    }

    @Test
    fun `launch purchase maps purchased payload back to request`() = kotlinx.coroutines.runBlocking {
        val (billingClient, captures) = createMockBillingClient(
            productDetails = listOf(
                AppActorBillingProductDetailsPayload(
                    productId = "com.appactor.pro.monthly",
                    productType = AppActorProductType.Subscription,
                    subscriptionOffers = listOf(
                        AppActorBillingSubscriptionOfferPayload(
                            basePlanId = "monthly001",
                            offerId = "intro7d",
                            offerToken = "intro-token",
                            pricingPhases = listOf(
                                AppActorPricingPhase(
                                    formattedPrice = "$4.99",
                                    priceAmountMicros = 4_990_000,
                                    currencyCode = "USD",
                                ),
                            ),
                        )
                    ),
                )
            ),
            launchResult = AppActorBillingLaunchResult.Purchased(
                purchases = listOf(
                    AppActorBillingPurchasePayload(
                        products = listOf("com.appactor.pro.monthly"),
                        productType = AppActorProductType.Subscription,
                        purchaseToken = "token_123",
                        orderId = "GPA.1234",
                        purchaseTimeMillis = 1_710_000_000_000,
                        purchaseState = AppActorStorePurchaseState.Purchased,
                        isAcknowledged = false,
                        isAutoRenewing = true,
                    )
                )
            ),
        )
        val adapter = GooglePlayStoreAdapter(context, billingClient)
        val request = AppActorStoreProductRequest(
            productId = "com.appactor.pro.monthly",
            productType = AppActorProductType.Subscription,
            basePlanId = "monthly001",
            offerId = "intro7d",
        )
        adapter.queryProductDetails(listOf(request))

        val result = adapter.launchPurchase(
            activity = Activity(),
            request = request,
        ) as AppActorStorePurchaseLaunchResult.Purchased

        assertEquals("token_123", result.purchases.first().purchaseToken)
        assertEquals("monthly001", result.purchases.first().basePlanId)
        assertEquals("intro7d", result.purchases.first().offerId)
        assertEquals(4_990_000L, result.purchases.first().priceAmountMicros)
        assertEquals("USD", result.purchases.first().currencyCode)
        assertEquals("intro-token", captures.lastLaunchOfferToken)
    }

    @Test
    fun `launch purchase maps one time price snapshot onto purchase`() = kotlinx.coroutines.runBlocking {
        val (billingClient, _) = createMockBillingClient(
            productDetails = listOf(
                AppActorBillingProductDetailsPayload(
                    productId = "com.appactor.coins.100",
                    productType = AppActorProductType.Consumable,
                    oneTimeOffer = AppActorStorePricing(
                        formattedPrice = "$1.99",
                        priceAmountMicros = 1_990_000,
                        currencyCode = "USD",
                    ),
                )
            ),
            launchResult = AppActorBillingLaunchResult.Purchased(
                purchases = listOf(
                    AppActorBillingPurchasePayload(
                        products = listOf("com.appactor.coins.100"),
                        productType = AppActorProductType.Consumable,
                        purchaseToken = "token_coins_100",
                        orderId = "GPA.5678",
                        purchaseTimeMillis = 1_710_000_000_000,
                        purchaseState = AppActorStorePurchaseState.Purchased,
                        isAcknowledged = false,
                    )
                )
            ),
        )
        val adapter = GooglePlayStoreAdapter(context, billingClient)
        val request = AppActorStoreProductRequest(
            productId = "com.appactor.coins.100",
            productType = AppActorProductType.Consumable,
        )
        adapter.queryProductDetails(listOf(request))

        val result = adapter.launchPurchase(
            activity = Activity(),
            request = request,
        ) as AppActorStorePurchaseLaunchResult.Purchased

        assertEquals(1_990_000L, result.purchases.first().priceAmountMicros)
        assertEquals("USD", result.purchases.first().currencyCode)
    }

    @Test
    fun `launch purchase forwards replacement params to billing client`() = kotlinx.coroutines.runBlocking {
        val (billingClient, captures) = createMockBillingClient(
            productDetails = listOf(
                AppActorBillingProductDetailsPayload(
                    productId = "com.appactor.pro.annual",
                    productType = AppActorProductType.Subscription,
                    subscriptionOffers = listOf(
                        AppActorBillingSubscriptionOfferPayload(
                            basePlanId = "annual001",
                            offerToken = "annual-token",
                        )
                    ),
                )
            ),
            launchResult = AppActorBillingLaunchResult.Cancelled,
        )
        val adapter = GooglePlayStoreAdapter(context, billingClient)
        val request = AppActorStoreProductRequest(
            productId = "com.appactor.pro.annual",
            productType = AppActorProductType.Subscription,
            basePlanId = "annual001",
            oldPurchaseToken = "old_monthly_token",
            replacementMode = 5,
        )
        adapter.queryProductDetails(listOf(request))

        adapter.launchPurchase(
            activity = Activity(),
            request = request,
        )

        assertEquals("old_monthly_token", captures.lastLaunchOldPurchaseToken)
        assertEquals(5, captures.lastLaunchReplacementMode)
    }

    @Test
    fun `launch purchase omits replacement params when not set`() = kotlinx.coroutines.runBlocking {
        val (billingClient, captures) = createMockBillingClient(
            productDetails = listOf(
                AppActorBillingProductDetailsPayload(
                    productId = "com.appactor.pro.monthly",
                    productType = AppActorProductType.Subscription,
                    subscriptionOffers = listOf(
                        AppActorBillingSubscriptionOfferPayload(
                            basePlanId = "monthly001",
                            offerToken = "monthly-token",
                        )
                    ),
                )
            ),
            launchResult = AppActorBillingLaunchResult.Cancelled,
        )
        val adapter = GooglePlayStoreAdapter(context, billingClient)
        val request = AppActorStoreProductRequest(
            productId = "com.appactor.pro.monthly",
            productType = AppActorProductType.Subscription,
            basePlanId = "monthly001",
        )
        adapter.queryProductDetails(listOf(request))

        adapter.launchPurchase(
            activity = Activity(),
            request = request,
        )

        assertNull(captures.lastLaunchOldPurchaseToken)
        assertNull(captures.lastLaunchReplacementMode)
    }

    @Test
    fun `query active purchases reuses cached product type and offer metadata`() = kotlinx.coroutines.runBlocking {
        val (billingClient, _) = createMockBillingClient(
            productDetails = listOf(
                AppActorBillingProductDetailsPayload(
                    productId = "com.appactor.coins.100",
                    productType = AppActorProductType.Consumable,
                    oneTimeOffer = AppActorStorePricing(formattedPrice = "$1.99"),
                )
            ),
            activePurchasesByType = mapOf(
                AppActorProductType.NonConsumable to listOf(
                    AppActorBillingPurchasePayload(
                        products = listOf("com.appactor.coins.100"),
                        productType = AppActorProductType.NonConsumable,
                        purchaseToken = "coins_token",
                        orderId = "GPA.inapp.1",
                        purchaseTimeMillis = 1_710_000_000_000,
                        purchaseState = AppActorStorePurchaseState.Purchased,
                        isAcknowledged = false,
                    )
                )
            ),
        )
        val adapter = GooglePlayStoreAdapter(context, billingClient)
        adapter.queryProductDetails(
            listOf(
                AppActorStoreProductRequest(
                    productId = "com.appactor.coins.100",
                    productType = AppActorProductType.Consumable,
                )
            )
        )

        val purchases = adapter.queryActivePurchases()

        assertEquals(1, purchases.size)
        assertEquals(AppActorProductType.Consumable, purchases.first().productType)
    }

    @Test
    fun `resolve direct purchase request fails when underspecified subscription target is ambiguous`() = kotlinx.coroutines.runBlocking {
        val (billingClient, _) = createMockBillingClient(
            productDetails = listOf(
                AppActorBillingProductDetailsPayload(
                    productId = "com.appactor.pro.monthly",
                    productType = AppActorProductType.Subscription,
                    subscriptionOffers = listOf(
                        AppActorBillingSubscriptionOfferPayload(basePlanId = "monthly001", offerId = null, offerToken = "base"),
                        AppActorBillingSubscriptionOfferPayload(basePlanId = "monthly001", offerId = "intro7d", offerToken = "intro"),
                    ),
                )
            )
        )
        val adapter = GooglePlayStoreAdapter(context, billingClient)

        val error = runCatching {
            adapter.resolveDirectPurchaseRequest(
                AppActorStoreProductRequest(
                    productId = "com.appactor.pro.monthly",
                    productType = AppActorProductType.Unknown,
                )
            )
        }.exceptionOrNull()

        assertTrue(error is com.appactor.android.models.AppActorError.InvalidConfiguration)
    }

    @Test
    fun `resolve direct purchase request fails when one time product type cannot be inferred`() = kotlinx.coroutines.runBlocking {
        val (billingClient, _) = createMockBillingClient(
            productDetails = listOf(
                AppActorBillingProductDetailsPayload(
                    productId = "com.appactor.coins.100",
                    productType = AppActorProductType.NonConsumable,
                    oneTimeOffer = AppActorStorePricing(formattedPrice = "$1.99"),
                )
            )
        )
        val adapter = GooglePlayStoreAdapter(context, billingClient)

        val error = runCatching {
            adapter.resolveDirectPurchaseRequest(
                AppActorStoreProductRequest(
                    productId = "com.appactor.coins.100",
                    productType = AppActorProductType.Unknown,
                )
            )
        }.exceptionOrNull()

        assertTrue(error is com.appactor.android.models.AppActorError.InvalidConfiguration)
    }

    @Test
    fun `resolve direct purchase request keeps explicit subscription target exact`() = kotlinx.coroutines.runBlocking {
        val (billingClient, _) = createMockBillingClient(
            productDetails = listOf(
                AppActorBillingProductDetailsPayload(
                    productId = "com.appactor.pro.monthly",
                    productType = AppActorProductType.Subscription,
                    subscriptionOffers = listOf(
                        AppActorBillingSubscriptionOfferPayload(basePlanId = "monthly001", offerId = null, offerToken = "base"),
                        AppActorBillingSubscriptionOfferPayload(basePlanId = "monthly001", offerId = "intro7d", offerToken = "intro"),
                    ),
                )
            )
        )
        val adapter = GooglePlayStoreAdapter(context, billingClient)

        val request = adapter.resolveDirectPurchaseRequest(
            AppActorStoreProductRequest(
                productId = "com.appactor.pro.monthly",
                productType = AppActorProductType.Subscription,
                basePlanId = "monthly001",
                offerId = "intro7d",
            )
        )

        assertEquals(AppActorProductType.Subscription, request.productType)
        assertEquals("monthly001", request.basePlanId)
        assertEquals("intro7d", request.offerId)
    }

    @Test
    fun `resolve direct purchase request rejects explicit one time type that contradicts cached catalog metadata`() = kotlinx.coroutines.runBlocking {
        val (billingClient, _) = createMockBillingClient(
            productDetails = listOf(
                AppActorBillingProductDetailsPayload(
                    productId = "com.appactor.coins.100",
                    productType = AppActorProductType.Consumable,
                    oneTimeOffer = AppActorStorePricing(formattedPrice = "$1.99"),
                )
            )
        )
        val adapter = GooglePlayStoreAdapter(context, billingClient)
        adapter.queryProductDetails(
            listOf(
                AppActorStoreProductRequest(
                    productId = "com.appactor.coins.100",
                    productType = AppActorProductType.Consumable,
                )
            )
        )

        val error = runCatching {
            adapter.resolveDirectPurchaseRequest(
                AppActorStoreProductRequest(
                    productId = "com.appactor.coins.100",
                    productType = AppActorProductType.NonConsumable,
                )
            )
        }.exceptionOrNull()

        assertTrue(error is com.appactor.android.models.AppActorError.InvalidConfiguration)
        assertTrue(error?.message?.contains("Conflicting Play one-time target") == true)
    }

    @Test
    fun `query product details falls back to the base plan when the requested offer is unavailable (never another offer)`() = kotlinx.coroutines.runBlocking {
        val (billingClient, _) = createMockBillingClient(
            productDetails = listOf(
                AppActorBillingProductDetailsPayload(
                    productId = "com.appactor.pro.monthly",
                    productType = AppActorProductType.Subscription,
                    subscriptionOffers = listOf(
                        AppActorBillingSubscriptionOfferPayload(basePlanId = "monthly001", offerId = null, offerToken = "base"),
                        AppActorBillingSubscriptionOfferPayload(basePlanId = "monthly001", offerId = "intro7d", offerToken = "intro"),
                    ),
                )
            )
        )
        val adapter = GooglePlayStoreAdapter(context, billingClient)

        val products = adapter.queryProductDetails(
            listOf(
                AppActorStoreProductRequest(
                    productId = "com.appactor.pro.monthly",
                    productType = AppActorProductType.Subscription,
                    basePlanId = "monthly001",
                    offerId = "wrong-offer",
                )
            )
        )

        // Requested offer is absent → fall back to the base plan (offerId null), never to the other
        // offer (intro7d).
        assertEquals(1, products.size)
        assertEquals("monthly001", products.single().basePlanId)
        assertNull(products.single().offerId)
    }

    @Test
    fun `query product details falls back to base plan for a returning user whose trial offer is unavailable`() = kotlinx.coroutines.runBlocking {
        val previousHandler = AppActorLogger.logHandler
        val messages = mutableListOf<String>()
        AppActorLogger.logHandler = { _, message, _, _ -> messages += message }
        try {
            val (billingClient, _) = createMockBillingClient(
                productDetails = listOf(
                    AppActorBillingProductDetailsPayload(
                        productId = "com.appactor.weeklytrial",
                        productType = AppActorProductType.Subscription,
                        // Returning / trial-ineligible user: Play returns ONLY the base plan; the trial offer is omitted.
                        subscriptionOffers = listOf(
                            AppActorBillingSubscriptionOfferPayload(basePlanId = "weeklytrial", offerId = null, offerToken = "base"),
                        ),
                    )
                )
            )
            val adapter = GooglePlayStoreAdapter(context, billingClient)

            val products = adapter.queryProductDetails(
                listOf(
                    AppActorStoreProductRequest(
                        productId = "com.appactor.weeklytrial",
                        productType = AppActorProductType.Subscription,
                        basePlanId = "weeklytrial",
                        offerId = "trial",
                    )
                )
            )

            // The trial offer is unavailable to this user; the purchase must still resolve to the base
            // plan (standard price) instead of dropping the product / failing.
            assertEquals(1, products.size)
            assertEquals("weeklytrial", products.single().basePlanId)
            assertNull(products.single().offerId)
            // The fallback must emit a WARN — the only audit signal that a requested offer was silently
            // degraded to the base plan (this also fires on a misconfigured / typo'd offerId).
            assertTrue(messages.any { it.contains("Requested Play offer unavailable") && it.contains("offerId=trial") })
        } finally {
            AppActorLogger.logHandler = previousHandler
        }
    }

    @Test
    fun `query product details fails fast when base plan matches multiple offers`() = kotlinx.coroutines.runBlocking {
        val (billingClient, _) = createMockBillingClient(
            productDetails = listOf(
                AppActorBillingProductDetailsPayload(
                    productId = "com.appactor.pro.monthly",
                    productType = AppActorProductType.Subscription,
                    subscriptionOffers = listOf(
                        AppActorBillingSubscriptionOfferPayload(basePlanId = "monthly001", offerId = "intro7d", offerToken = "intro"),
                        AppActorBillingSubscriptionOfferPayload(basePlanId = "monthly001", offerId = "winback", offerToken = "winback"),
                    ),
                )
            )
        )
        val adapter = GooglePlayStoreAdapter(context, billingClient)

        val error = runCatching {
            adapter.queryProductDetails(
                listOf(
                    AppActorStoreProductRequest(
                        productId = "com.appactor.pro.monthly",
                        productType = AppActorProductType.Subscription,
                        basePlanId = "monthly001",
                    )
                )
            )
        }.exceptionOrNull()

        assertTrue(error is com.appactor.android.models.AppActorError.InvalidConfiguration)
    }

    @Test
    fun `query active purchases keeps unresolved one time products as unknown instead of misclassifying them`() = kotlinx.coroutines.runBlocking {
        val (billingClient, _) = createMockBillingClient(
            activePurchasesByType = mapOf(
                AppActorProductType.NonConsumable to listOf(
                    AppActorBillingPurchasePayload(
                        products = listOf("com.appactor.unknown.inapp"),
                        productType = AppActorProductType.NonConsumable,
                        purchaseToken = "unknown_token",
                        purchaseTimeMillis = 1_710_000_000_000,
                        purchaseState = AppActorStorePurchaseState.Purchased,
                        isAcknowledged = false,
                    )
                )
            ),
        )
        val adapter = GooglePlayStoreAdapter(context, billingClient)

        val purchases = adapter.queryActivePurchases()

        assertEquals(1, purchases.size)
        assertEquals(AppActorProductType.Unknown, purchases.single().productType)
    }

    @Test
    fun `query active purchases keeps cold start inapp payloads unknown even when product details exist`() = kotlinx.coroutines.runBlocking {
        val (billingClient, _) = createMockBillingClient(
            productDetails = listOf(
                AppActorBillingProductDetailsPayload(
                    productId = "com.appactor.coins.100",
                    productType = AppActorProductType.Consumable,
                    oneTimeOffer = AppActorStorePricing(formattedPrice = "$1.99"),
                )
            ),
            activePurchasesByType = mapOf(
                AppActorProductType.NonConsumable to listOf(
                    AppActorBillingPurchasePayload(
                        products = listOf("com.appactor.coins.100"),
                        productType = AppActorProductType.NonConsumable,
                        purchaseToken = "coins_token",
                        purchaseTimeMillis = 1_710_000_000_000,
                        purchaseState = AppActorStorePurchaseState.Purchased,
                        isAcknowledged = false,
                    )
                )
            ),
        )
        val adapter = GooglePlayStoreAdapter(context, billingClient)

        val purchases = adapter.queryActivePurchases()

        assertEquals(1, purchases.size)
        assertEquals(AppActorProductType.Unknown, purchases.single().productType)
    }

    @Test
    fun `query purchase history maps subscription records and keeps unresolved inapp records unknown`() = kotlinx.coroutines.runBlocking {
        val (billingClient, _) = createMockBillingClient(
            purchaseHistoryByType = mapOf(
                AppActorProductType.Subscription to listOf(
                    AppActorBillingPurchaseHistoryPayload(
                        products = listOf("com.appactor.pro.monthly"),
                        productType = AppActorProductType.Subscription,
                        purchaseToken = "history_sub_123",
                        purchaseTimeMillis = 1_710_000_000_000,
                        rawPurchaseData = "{\"purchaseToken\":\"history_sub_123\"}",
                        purchaseSignature = "signature_sub_123",
                    )
                ),
                AppActorProductType.NonConsumable to listOf(
                    AppActorBillingPurchaseHistoryPayload(
                        products = listOf("com.appactor.unknown.inapp"),
                        productType = AppActorProductType.Unknown,
                        purchaseToken = "history_inapp_123",
                        purchaseTimeMillis = 1_710_000_100_000,
                    )
                ),
            )
        )
        val adapter = GooglePlayStoreAdapter(context, billingClient)

        val history = adapter.queryPurchaseHistory()

        assertEquals(2, history.size)
        assertEquals(AppActorProductType.Subscription, history.first { it.purchaseToken == "history_sub_123" }.productType)
        assertEquals(AppActorProductType.Unknown, history.first { it.purchaseToken == "history_inapp_123" }.productType)
    }

    @Test
    fun `purchase updates resolve out of band purchases through active purchase scan`() = kotlinx.coroutines.runBlocking {
        val activePurchase = AppActorBillingPurchasePayload(
            products = listOf("com.appactor.pro.monthly"),
            productType = AppActorProductType.Subscription,
            purchaseToken = "token_update_123",
            purchaseTimeMillis = 1_710_000_000_000,
            purchaseState = AppActorStorePurchaseState.Purchased,
            isAcknowledged = false,
            isAutoRenewing = true,
        )
        val (billingClient, _) = createMockBillingClient(
            activePurchasesByType = mapOf(
                AppActorProductType.Subscription to listOf(activePurchase),
            ),
            purchaseUpdates = flowOf(
                AppActorBillingPurchaseUpdate(
                    purchaseTokens = setOf("token_update_123"),
                    purchases = listOf(
                        activePurchase.copy(productType = AppActorProductType.Unknown)
                    ),
                )
            ),
        )
        val adapter = GooglePlayStoreAdapter(context, billingClient)

        val purchases = adapter.purchaseUpdates().first()

        assertEquals(1, purchases.size)
        assertEquals(AppActorProductType.Subscription, purchases.single().productType)
        assertEquals("token_update_123", purchases.single().purchaseToken)
    }

    @Test
    fun `purchase updates keep unresolved inapp payloads as unknown instead of dropping them`() = kotlinx.coroutines.runBlocking {
        val update = AppActorBillingPurchasePayload(
            products = listOf("com.appactor.unknown.inapp"),
            productType = AppActorProductType.Unknown,
            purchaseToken = "token_unknown_inapp",
            purchaseTimeMillis = 1_710_000_000_000,
            purchaseState = AppActorStorePurchaseState.Purchased,
            isAcknowledged = false,
        )
        val (billingClient, _) = createMockBillingClient(
            purchaseUpdates = flowOf(
                AppActorBillingPurchaseUpdate(
                    purchaseTokens = setOf("token_unknown_inapp"),
                    purchases = listOf(update),
                )
            ),
        )
        val adapter = GooglePlayStoreAdapter(context, billingClient)

        val purchases = adapter.purchaseUpdates().first()

        assertEquals(1, purchases.size)
        assertEquals(AppActorProductType.Unknown, purchases.single().productType)
        assertEquals("token_unknown_inapp", purchases.single().purchaseToken)
    }

    @Test
    fun `query product details auto-selects the free trial offer when no offer id is requested`() = kotlinx.coroutines.runBlocking {
        val (billingClient, captures) = createMockBillingClient(
            productDetails = listOf(
                AppActorBillingProductDetailsPayload(
                    productId = "com.appactor.pro.monthly",
                    productType = AppActorProductType.Subscription,
                    subscriptionOffers = listOf(
                        AppActorBillingSubscriptionOfferPayload(
                            basePlanId = "monthly001",
                            offerId = null,
                            offerToken = "base-token",
                            pricingPhases = listOf(
                                AppActorPricingPhase(billingPeriod = "P1M", priceAmountMicros = 9_990_000),
                            ),
                        ),
                        AppActorBillingSubscriptionOfferPayload(
                            basePlanId = "monthly001",
                            offerId = "trial7d",
                            offerToken = "trial-token",
                            pricingPhases = listOf(
                                AppActorPricingPhase(billingPeriod = "P1W", priceAmountMicros = 0),
                                AppActorPricingPhase(billingPeriod = "P1M", priceAmountMicros = 9_990_000),
                            ),
                        ),
                    ),
                )
            )
        )
        val adapter = GooglePlayStoreAdapter(context, billingClient)
        val request = AppActorStoreProductRequest(
            productId = "com.appactor.pro.monthly",
            productType = AppActorProductType.Subscription,
            basePlanId = "monthly001",
        )

        val products = adapter.queryProductDetails(listOf(request))

        // No explicit offerId -> the eligible free trial is auto-applied (RevenueCat parity),
        // instead of deterministically charging the base-plan full price.
        assertEquals(1, products.size)
        assertEquals("monthly001", products.single().basePlanId)
        assertEquals("trial7d", products.single().offerId)

        adapter.launchPurchase(activity = Activity(), request = request)

        assertEquals("trial-token", captures.lastLaunchOfferToken)
    }

    @Test
    fun `explicit offer id bypasses auto-selection entirely`() = kotlinx.coroutines.runBlocking {
        val (billingClient, _) = createMockBillingClient(
            productDetails = listOf(
                AppActorBillingProductDetailsPayload(
                    productId = "com.appactor.pro.monthly",
                    productType = AppActorProductType.Subscription,
                    subscriptionOffers = listOf(
                        AppActorBillingSubscriptionOfferPayload(
                            basePlanId = "monthly001",
                            offerId = null,
                            offerToken = "base-token",
                            pricingPhases = listOf(
                                AppActorPricingPhase(billingPeriod = "P1M", priceAmountMicros = 9_990_000),
                            ),
                        ),
                        AppActorBillingSubscriptionOfferPayload(
                            basePlanId = "monthly001",
                            offerId = "trial30d",
                            offerToken = "trial-token",
                            pricingPhases = listOf(
                                AppActorPricingPhase(billingPeriod = "P1M", priceAmountMicros = 0),
                                AppActorPricingPhase(billingPeriod = "P1M", priceAmountMicros = 9_990_000),
                            ),
                        ),
                        AppActorBillingSubscriptionOfferPayload(
                            basePlanId = "monthly001",
                            offerId = "intro499",
                            offerToken = "intro-token",
                            pricingPhases = listOf(
                                AppActorPricingPhase(billingPeriod = "P1M", priceAmountMicros = 4_990_000),
                                AppActorPricingPhase(billingPeriod = "P1M", priceAmountMicros = 9_990_000),
                            ),
                        ),
                    ),
                )
            )
        )
        val adapter = GooglePlayStoreAdapter(context, billingClient)

        val products = adapter.queryProductDetails(
            listOf(
                AppActorStoreProductRequest(
                    productId = "com.appactor.pro.monthly",
                    productType = AppActorProductType.Subscription,
                    basePlanId = "monthly001",
                    offerId = "intro499",
                )
            )
        )

        // The pinned offer wins even though another offer (trial30d) would rank higher.
        assertEquals(1, products.size)
        assertEquals("intro499", products.single().offerId)
    }

    @Test
    fun `auto-selection skips offers tagged aa-ignore-offer and falls back to the base plan`() = kotlinx.coroutines.runBlocking {
        val (billingClient, captures) = createMockBillingClient(
            productDetails = listOf(
                AppActorBillingProductDetailsPayload(
                    productId = "com.appactor.pro.monthly",
                    productType = AppActorProductType.Subscription,
                    subscriptionOffers = listOf(
                        AppActorBillingSubscriptionOfferPayload(
                            basePlanId = "monthly001",
                            offerId = null,
                            offerToken = "base-token",
                            pricingPhases = listOf(
                                AppActorPricingPhase(billingPeriod = "P1M", priceAmountMicros = 9_990_000),
                            ),
                        ),
                        AppActorBillingSubscriptionOfferPayload(
                            basePlanId = "monthly001",
                            offerId = "trial7d",
                            offerToken = "trial-token",
                            offerTags = listOf("aa-ignore-offer"),
                            pricingPhases = listOf(
                                AppActorPricingPhase(billingPeriod = "P1W", priceAmountMicros = 0),
                                AppActorPricingPhase(billingPeriod = "P1M", priceAmountMicros = 9_990_000),
                            ),
                        ),
                    ),
                )
            )
        )
        val adapter = GooglePlayStoreAdapter(context, billingClient)
        val request = AppActorStoreProductRequest(
            productId = "com.appactor.pro.monthly",
            productType = AppActorProductType.Subscription,
            basePlanId = "monthly001",
        )

        val products = adapter.queryProductDetails(listOf(request))

        assertEquals(1, products.size)
        assertNull(products.single().offerId)

        adapter.launchPurchase(activity = Activity(), request = request)

        assertEquals("base-token", captures.lastLaunchOfferToken)
    }
}

private fun AppActorBillingQueryProduct.matches(
    payload: AppActorBillingProductDetailsPayload,
): Boolean {
    return when (productType) {
        AppActorProductType.Subscription -> payload.productType == AppActorProductType.Subscription
        else -> payload.productType != AppActorProductType.Subscription
    }
}
