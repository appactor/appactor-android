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
        recurrenceMode: AppActorRecurrenceMode? = null,
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

    @Test
    fun `period parser rejects multi-component periods play never emits`() {
        assertNull(AppActorSubscriptionPeriod.fromIso8601("P1M2W"))
        assertNull(AppActorSubscriptionPeriod.iso8601ToDays("P1M2W"))
    }

    @Test
    fun `iso periods convert to comparable approximate days`() {
        assertEquals(3, AppActorSubscriptionPeriod.iso8601ToDays("P3D"))
        assertEquals(7, AppActorSubscriptionPeriod.iso8601ToDays("P1W"))
        assertEquals(30, AppActorSubscriptionPeriod.iso8601ToDays("P1M"))
        assertEquals(365, AppActorSubscriptionPeriod.iso8601ToDays("P1Y"))
        assertNull(AppActorSubscriptionPeriod.iso8601ToDays("P0D"))
        assertNull(AppActorSubscriptionPeriod.iso8601ToDays("not-a-period"))
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
            phase(priceAmountMicros = 0L, billingCycleCount = 1, recurrenceMode = AppActorRecurrenceMode.FiniteRecurring).paymentMode,
        )
        assertEquals(
            AppActorOfferPaymentMode.SinglePayment,
            phase(priceAmountMicros = 4_990_000L, billingCycleCount = 1, recurrenceMode = AppActorRecurrenceMode.FiniteRecurring).paymentMode,
        )
        assertEquals(
            AppActorOfferPaymentMode.DiscountedRecurring,
            phase(priceAmountMicros = 4_990_000L, billingCycleCount = 3, recurrenceMode = AppActorRecurrenceMode.FiniteRecurring).paymentMode,
        )
        // An infinite-recurring phase (the full recurring price) has no offer payment mode.
        assertNull(phase(priceAmountMicros = 39_990_000L, recurrenceMode = AppActorRecurrenceMode.InfiniteRecurring).paymentMode)
    }

    @Test
    fun `recurrence mode maps from play raw values`() {
        assertEquals(AppActorRecurrenceMode.InfiniteRecurring, AppActorRecurrenceMode.fromPlayValue(1))
        assertEquals(AppActorRecurrenceMode.FiniteRecurring, AppActorRecurrenceMode.fromPlayValue(2))
        assertEquals(AppActorRecurrenceMode.NonRecurring, AppActorRecurrenceMode.fromPlayValue(3))
        assertNull(AppActorRecurrenceMode.fromPlayValue(0))
        assertNull(AppActorRecurrenceMode.fromPlayValue(null))
    }

    // --- AppActorPackage phase helpers ---------------------------------------------------------

    @Test
    fun `free-trial offer exposes freePhase with the trial period`() {
        val pkg = subscriptionPackage(
            listOf(
                phase(billingPeriod = "P3D", priceAmountMicros = 0L, recurrenceMode = AppActorRecurrenceMode.FiniteRecurring, billingCycleCount = 1),
                phase(billingPeriod = "P1W", priceAmountMicros = 39_990_000L, formattedPrice = "₺39.99", recurrenceMode = AppActorRecurrenceMode.InfiniteRecurring),
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
                phase(billingPeriod = "P1M", priceAmountMicros = 4_990_000L, formattedPrice = "₺4.99", recurrenceMode = AppActorRecurrenceMode.FiniteRecurring, billingCycleCount = 3),
                phase(billingPeriod = "P1M", priceAmountMicros = 9_990_000L, formattedPrice = "₺9.99", recurrenceMode = AppActorRecurrenceMode.InfiniteRecurring),
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
                phase(billingPeriod = "P1M", priceAmountMicros = 9_990_000L, formattedPrice = "₺9.99", recurrenceMode = AppActorRecurrenceMode.InfiniteRecurring),
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
