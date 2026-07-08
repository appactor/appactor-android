package com.appactor.android.billing

import com.appactor.android.models.AppActorSubscriptionPeriod

/**
 * Client-side auto-selection of the best eligible Google Play subscription offer for a base
 * plan, mirroring RevenueCat's default offer logic.
 *
 * Play already eligibility-filters the offers it returns in `ProductDetails`, so every offer
 * seen here is purchasable by the current user. Ranking, best first:
 *
 *  1. the offer with the longest free-trial phase (a pricing phase with `priceAmountMicros == 0`,
 *     durations compared by parsing the ISO-8601 `billingPeriod`),
 *  2. the offer with the cheapest first paid phase priced below the base plan's recurring price,
 *  3. the base-plan pseudo-offer (standard price).
 *
 * Ties are deterministic: the first offer in Play's returned order wins — never a throw.
 * Offers tagged [IGNORE_OFFER_TAG] in Play Console are excluded from auto-selection (per-offer
 * opt-out); they stay purchasable through an explicitly pinned `offerId`.
 *
 * Pure and dependency-free so it unit-tests without Play Billing.
 */
internal object SubscriptionOfferSelector {

    /** Play Console offer tag that opts an offer out of client-side auto-selection. */
    const val IGNORE_OFFER_TAG: String = "aa-ignore-offer"

    /**
     * Selects the best eligible offer among [offers] (all entries are expected to belong to the
     * same base plan). Returns `null` when no offer ranks and no base-plan pseudo-offer exists,
     * so callers can fall back to their legacy resolution.
     */
    fun selectBestOffer(
        offers: List<AppActorBillingSubscriptionOfferPayload>,
    ): AppActorBillingSubscriptionOfferPayload? {
        val baseOffer = offers.firstOrNull { offer -> offer.offerId.isNullOrBlank() }
        val candidates = offers.filter { offer ->
            !offer.offerId.isNullOrBlank() && !offer.offerTags.contains(IGNORE_OFFER_TAG)
        }

        // (a) Longest free trial. maxByOrNull returns the first max, so equal trials resolve
        // deterministically to the earliest offer in Play's order.
        val longestFreeTrial = candidates
            .mapNotNull { offer -> offer.freeTrialDays()?.let { days -> offer to days } }
            .maxByOrNull { (_, days) -> days }
        if (longestFreeTrial != null) return longestFreeTrial.first

        // (b) Cheapest first paid phase priced below the base plan's recurring price.
        // minByOrNull returns the first min, so price ties are deterministic too.
        val basePriceMicros = baseOffer?.recurringPriceMicros()
        val cheapestIntro = candidates
            .mapNotNull { offer -> offer.firstDiscountedPriceMicros(basePriceMicros)?.let { price -> offer to price } }
            .minByOrNull { (_, price) -> price }
        if (cheapestIntro != null) return cheapestIntro.first

        // (c) Base-plan pseudo-offer (standard price).
        return baseOffer
    }

    /** The longest free (zero-priced) phase of the offer, in days; `null` when there is none. */
    private fun AppActorBillingSubscriptionOfferPayload.freeTrialDays(): Int? {
        return pricingPhases
            .filter { phase -> phase.isFreeTrial }
            .mapNotNull { phase -> AppActorSubscriptionPeriod.iso8601ToDays(phase.billingPeriod) }
            .maxOrNull()
    }

    /** The offer's recurring price: the final pricing phase. */
    private fun AppActorBillingSubscriptionOfferPayload.recurringPriceMicros(): Long? {
        return pricingPhases.lastOrNull()?.priceAmountMicros
    }

    /** The first paid phase's price, only when it undercuts the base plan's recurring price. */
    private fun AppActorBillingSubscriptionOfferPayload.firstDiscountedPriceMicros(
        basePriceMicros: Long?,
    ): Long? {
        if (basePriceMicros == null || basePriceMicros <= 0L) return null
        val firstPaidPriceMicros = pricingPhases.firstNotNullOfOrNull { phase ->
            phase.priceAmountMicros?.takeIf { micros -> micros > 0L }
        } ?: return null
        return firstPaidPriceMicros.takeIf { micros -> micros < basePriceMicros }
    }
}
