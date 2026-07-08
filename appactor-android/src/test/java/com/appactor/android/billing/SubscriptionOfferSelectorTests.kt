package com.appactor.android.billing

import com.appactor.android.models.AppActorPricingPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionOfferSelectorTests {

    private val basePriceMicros = 9_990_000L

    private fun phase(
        billingPeriod: String?,
        priceAmountMicros: Long?,
    ): AppActorPricingPhase {
        return AppActorPricingPhase(
            billingPeriod = billingPeriod,
            priceAmountMicros = priceAmountMicros,
        )
    }

    private fun baseOffer(
        offerToken: String = "base-token",
        recurringMicros: Long = basePriceMicros,
    ): AppActorBillingSubscriptionOfferPayload {
        return AppActorBillingSubscriptionOfferPayload(
            basePlanId = "monthly001",
            offerId = null,
            offerToken = offerToken,
            pricingPhases = listOf(phase("P1M", recurringMicros)),
        )
    }

    private fun trialOffer(
        offerId: String,
        trialPeriod: String,
        offerTags: List<String> = emptyList(),
    ): AppActorBillingSubscriptionOfferPayload {
        return AppActorBillingSubscriptionOfferPayload(
            basePlanId = "monthly001",
            offerId = offerId,
            offerToken = "$offerId-token",
            offerTags = offerTags,
            pricingPhases = listOf(
                phase(trialPeriod, 0L),
                phase("P1M", basePriceMicros),
            ),
        )
    }

    private fun introOffer(
        offerId: String,
        introMicros: Long,
        offerTags: List<String> = emptyList(),
    ): AppActorBillingSubscriptionOfferPayload {
        return AppActorBillingSubscriptionOfferPayload(
            basePlanId = "monthly001",
            offerId = offerId,
            offerToken = "$offerId-token",
            offerTags = offerTags,
            pricingPhases = listOf(
                phase("P1M", introMicros),
                phase("P1M", basePriceMicros),
            ),
        )
    }

    @Test
    fun `free trial beats intro price`() {
        val selected = SubscriptionOfferSelector.selectBestOffer(
            listOf(
                baseOffer(),
                introOffer("intro499", introMicros = 4_990_000),
                trialOffer("trial7d", trialPeriod = "P1W"),
            )
        )

        assertEquals("trial7d", selected?.offerId)
    }

    @Test
    fun `longer trial beats shorter trial across period units`() {
        val selected = SubscriptionOfferSelector.selectBestOffer(
            listOf(
                baseOffer(),
                trialOffer("trial3d", trialPeriod = "P3D"),
                trialOffer("trial1m", trialPeriod = "P1M"),
                trialOffer("trial1w", trialPeriod = "P1W"),
            )
        )

        assertEquals("trial1m", selected?.offerId)
    }

    @Test
    fun `cheapest intro price wins when no offer has a trial`() {
        val selected = SubscriptionOfferSelector.selectBestOffer(
            listOf(
                baseOffer(),
                introOffer("intro699", introMicros = 6_990_000),
                introOffer("intro499", introMicros = 4_990_000),
            )
        )

        assertEquals("intro499", selected?.offerId)
    }

    @Test
    fun `offers tagged aa-ignore-offer are excluded from auto-selection`() {
        val selected = SubscriptionOfferSelector.selectBestOffer(
            listOf(
                baseOffer(),
                trialOffer("trial30d", trialPeriod = "P1M", offerTags = listOf("aa-ignore-offer")),
                introOffer("intro499", introMicros = 4_990_000),
            )
        )

        assertEquals("intro499", selected?.offerId)
    }

    @Test
    fun `all discounted offers ignored selects the base plan`() {
        val selected = SubscriptionOfferSelector.selectBestOffer(
            listOf(
                baseOffer(),
                trialOffer("trial7d", trialPeriod = "P1W", offerTags = listOf("promo", "aa-ignore-offer")),
            )
        )

        assertNull(selected?.offerId)
        assertEquals("base-token", selected?.offerToken)
    }

    @Test
    fun `no discounted offers selects the base plan`() {
        val selected = SubscriptionOfferSelector.selectBestOffer(listOf(baseOffer()))

        assertNull(selected?.offerId)
        assertEquals("base-token", selected?.offerToken)
    }

    @Test
    fun `equal trials resolve deterministically to the first offer in play order instead of throwing`() {
        val selected = SubscriptionOfferSelector.selectBestOffer(
            listOf(
                baseOffer(),
                trialOffer("trial7d-a", trialPeriod = "P1W"),
                trialOffer("trial7d-b", trialPeriod = "P7D"),
            )
        )

        assertEquals("trial7d-a", selected?.offerId)
    }

    @Test
    fun `intro price not below the base price is not treated as a discount`() {
        val selected = SubscriptionOfferSelector.selectBestOffer(
            listOf(
                baseOffer(),
                introOffer("intro-full-price", introMicros = basePriceMicros),
            )
        )

        assertNull(selected?.offerId)
        assertEquals("base-token", selected?.offerToken)
    }

    @Test
    fun `no rankable offers and no base plan returns null for legacy fallback`() {
        val selected = SubscriptionOfferSelector.selectBestOffer(
            listOf(
                // No pricing phases at all (no trial, no intro) and no base pseudo-offer.
                AppActorBillingSubscriptionOfferPayload(basePlanId = "monthly001", offerId = "intro7d", offerToken = "intro"),
                AppActorBillingSubscriptionOfferPayload(basePlanId = "monthly001", offerId = "winback", offerToken = "winback"),
            )
        )

        assertNull(selected)
    }

    @Test
    fun `empty offer list returns null`() {
        assertNull(SubscriptionOfferSelector.selectBestOffer(emptyList()))
    }
}
