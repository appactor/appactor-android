package com.appactor.android.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppActorPricingPhaseTests {

    private fun phase(
        billingPeriod: String? = null,
        priceAmountMicros: Long? = null,
        formattedPrice: String? = null,
        billingCycleCount: Int? = null,
        recurrenceMode: Int? = null,
    ): AppActorPricingPhase =
        AppActorPricingPhase(
            billingPeriod = billingPeriod,
            formattedPrice = formattedPrice,
            priceAmountMicros = priceAmountMicros,
            currencyCode = "TRY",
            billingCycleCount = billingCycleCount,
            recurrenceMode = recurrenceMode,
        )

    private fun subscriptionPackage(phases: List<AppActorPricingPhase>): AppActorPackage =
        AppActorPackage(
            id = "pkg",
            store = AppActorStore.PlayStore,
            productId = "com.appactor.pro.weekly",
            productType = AppActorProductType.Subscription,
            basePlanId = "weekly001",
            localizedPriceString = phases.lastOrNull()?.formattedPrice,
            pricingPhases = phases,
        )

    // --- ISO-8601 period parsing (shared parser) --------------------------------------------

    @Test
    fun `period parser maps single-unit ISO periods to unit and count`() {
        assertEquals(
            AppActorSubscriptionPeriod(AppActorSubscriptionPeriodUnit.Day, 3),
            AppActorSubscriptionPeriod.fromIso8601("P3D"),
        )
        assertEquals(
            AppActorSubscriptionPeriod(AppActorSubscriptionPeriodUnit.Week, 1),
            AppActorSubscriptionPeriod.fromIso8601("P1W"),
        )
        assertEquals(
            AppActorSubscriptionPeriod(AppActorSubscriptionPeriodUnit.Month, 1),
            AppActorSubscriptionPeriod.fromIso8601("P1M"),
        )
        assertEquals(
            AppActorSubscriptionPeriod(AppActorSubscriptionPeriodUnit.Year, 1),
            AppActorSubscriptionPeriod.fromIso8601("P1Y"),
        )
    }

    @Test
    fun `period parser returns null for zero-length or unparseable input`() {
        assertNull(AppActorSubscriptionPeriod.fromIso8601("P0D"))
        assertNull(AppActorSubscriptionPeriod.fromIso8601("not-a-period"))
        assertNull(AppActorSubscriptionPeriod.fromIso8601(null))
    }

    // --- AppActorPricingPhase computed fields --------------------------------------------------

    @Test
    fun `free-trial phase reports isFreeTrial and parsed period`() {
        val trial = phase(billingPeriod = "P3D", priceAmountMicros = 0L)
        assertTrue(trial.isFreeTrial)
        assertEquals(AppActorSubscriptionPeriod(AppActorSubscriptionPeriodUnit.Day, 3), trial.period)
    }

    @Test
    fun `paid phase is not a free trial`() {
        assertFalse(phase(billingPeriod = "P1W", priceAmountMicros = 39_990_000L).isFreeTrial)
    }

    @Test
    fun `payment mode mirrors RevenueCat finite-recurring semantics`() {
        assertEquals(
            AppActorOfferPaymentMode.FreeTrial,
            phase(priceAmountMicros = 0L, billingCycleCount = 1, recurrenceMode = 2).paymentMode,
        )
        assertEquals(
            AppActorOfferPaymentMode.SinglePayment,
            phase(priceAmountMicros = 4_990_000L, billingCycleCount = 1, recurrenceMode = 2).paymentMode,
        )
        assertEquals(
            AppActorOfferPaymentMode.DiscountedRecurring,
            phase(priceAmountMicros = 4_990_000L, billingCycleCount = 3, recurrenceMode = 2).paymentMode,
        )
        // recurrenceMode 1 == INFINITE_RECURRING (the full recurring price) -> no offer payment mode.
        assertNull(phase(priceAmountMicros = 39_990_000L, recurrenceMode = 1).paymentMode)
    }

    // --- AppActorPackage phase helpers ---------------------------------------------------------

    @Test
    fun `free-trial offer exposes freePhase with the trial period`() {
        val pkg = subscriptionPackage(
            listOf(
                phase(billingPeriod = "P3D", priceAmountMicros = 0L, recurrenceMode = 2, billingCycleCount = 1),
                phase(billingPeriod = "P1W", priceAmountMicros = 39_990_000L, formattedPrice = "₺39.99", recurrenceMode = 1),
            ),
        )

        assertTrue(pkg.hasFreeTrial)
        assertEquals(true, pkg.freePhase?.isFreeTrial)
        assertEquals(
            AppActorSubscriptionPeriod(AppActorSubscriptionPeriodUnit.Day, 3),
            pkg.freePhase?.period,
        )
        assertNull(pkg.introPhase)
        assertEquals("₺39.99", pkg.fullPricePhase?.formattedPrice)
    }

    @Test
    fun `intro-price offer exposes introPhase and no free trial`() {
        val pkg = subscriptionPackage(
            listOf(
                phase(billingPeriod = "P1M", priceAmountMicros = 4_990_000L, formattedPrice = "₺4.99", recurrenceMode = 2, billingCycleCount = 3),
                phase(billingPeriod = "P1M", priceAmountMicros = 9_990_000L, formattedPrice = "₺9.99", recurrenceMode = 1),
            ),
        )

        assertFalse(pkg.hasFreeTrial)
        assertNull(pkg.freePhase)
        assertEquals(4_990_000L, pkg.introPhase?.priceAmountMicros)
        assertEquals("₺9.99", pkg.fullPricePhase?.formattedPrice)
    }

    @Test
    fun `plain base plan exposes only a full price phase`() {
        val pkg = subscriptionPackage(
            listOf(
                phase(billingPeriod = "P1M", priceAmountMicros = 9_990_000L, formattedPrice = "₺9.99", recurrenceMode = 1),
            ),
        )

        assertFalse(pkg.hasFreeTrial)
        assertNull(pkg.freePhase)
        assertNull(pkg.introPhase)
        assertEquals("₺9.99", pkg.fullPricePhase?.formattedPrice)
    }

    @Test
    fun `package with no resolved offer exposes no phases`() {
        val pkg = AppActorPackage(
            id = "pkg",
            store = AppActorStore.AppStore,
            productId = "com.appactor.pro.weekly",
        )

        assertTrue(pkg.pricingPhases.isEmpty())
        assertNull(pkg.fullPricePhase)
        assertNull(pkg.freePhase)
        assertNull(pkg.introPhase)
        assertFalse(pkg.hasFreeTrial)
    }
}
