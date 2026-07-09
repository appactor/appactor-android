package com.appactor.android.models

/**
 * The unit of time a subscription billing period is measured in.
 */
public enum class AppActorSubscriptionPeriodUnit {
    Day,
    Week,
    Month,
    Year,
    Unknown,
    ;

    /** Approximate days per unit (1M = 30d, 1Y = 365d), used to compare period lengths. */
    internal val approxDays: Int?
        get() = when (this) {
            Day -> 1
            Week -> 7
            Month -> 30
            Year -> 365
            Unknown -> null
        }
}

/**
 * A subscription billing period expressed as a [unit] and a [numberOfUnits] count
 * (e.g. `P3D` -> 3 days, `P1W` -> 1 week, `P1M` -> 1 month).
 *
 * Parsed from Google Play's ISO-8601 `billingPeriod` string. Mirrors RevenueCat's `Period`
 * and Adapty's localized subscription period so paywalls can render durations without
 * re-parsing raw ISO strings.
 */
public data class AppActorSubscriptionPeriod(
    val unit: AppActorSubscriptionPeriodUnit,
    val numberOfUnits: Int,
) {
    internal companion object {
        // Google Play billing periods are always single-unit (P3D, P1W, P1M, P1Y).
        private val ISO_8601_SINGLE_UNIT_PERIOD = Regex("^P(\\d+)([DWMY])$", RegexOption.IGNORE_CASE)

        /**
         * Parses a single-unit ISO-8601 subscription period (`P3D`, `P1W`, `P1M`, `P1Y`) into a
         * [unit] + [numberOfUnits]. Returns `null` for zero-length, multi-component (`P1M2W`),
         * or otherwise unparseable periods — Google Play never emits multi-component billing
         * periods, and rejecting them keeps every consumer on one interpretation of the string.
         */
        internal fun fromIso8601(period: String?): AppActorSubscriptionPeriod? {
            val match = ISO_8601_SINGLE_UNIT_PERIOD.matchEntire(period?.trim().orEmpty()) ?: return null
            val (count, unitLetter) = match.destructured
            val numberOfUnits = count.toIntOrNull()?.takeIf { it > 0 } ?: return null
            val unit = when (unitLetter.uppercase()) {
                "D" -> AppActorSubscriptionPeriodUnit.Day
                "W" -> AppActorSubscriptionPeriodUnit.Week
                "M" -> AppActorSubscriptionPeriodUnit.Month
                "Y" -> AppActorSubscriptionPeriodUnit.Year
                else -> return null
            }
            return AppActorSubscriptionPeriod(unit, numberOfUnits)
        }

        /**
         * Approximate total days of a single-unit ISO-8601 period (1M = 30d, 1Y = 365d), used to
         * compare trial durations. Derived from [fromIso8601] so both functions share one parse
         * and one semantics; `null` whenever [fromIso8601] rejects the input.
         */
        internal fun iso8601ToDays(period: String?): Int? =
            fromIso8601(period)?.let { parsed -> parsed.unit.approxDays?.let { it * parsed.numberOfUnits } }
    }
}

/**
 * How a subscriber pays during a single [AppActorPricingPhase]. Mirrors RevenueCat's
 * `OfferPaymentMode` (and Adapty's payment mode).
 */
public enum class AppActorOfferPaymentMode {
    /** Subscribers pay nothing until the phase ends (free trial). */
    FreeTrial,

    /** Subscribers pay once up front for the phase. */
    SinglePayment,

    /** Subscribers pay a discounted amount for a fixed number of billing cycles. */
    DiscountedRecurring,
}

/**
 * How a pricing phase repeats. Mirrors Google Play's `ProductDetails.RecurrenceMode` (and
 * RevenueCat's `RecurrenceMode`) as a typed value instead of a raw integer.
 */
public enum class AppActorRecurrenceMode {
    /** The phase repeats at its billing period until the subscriber cancels. */
    InfiniteRecurring,

    /** The phase repeats for exactly [AppActorPricingPhase.billingCycleCount] billing cycles. */
    FiniteRecurring,

    /** The phase is charged once and does not repeat. */
    NonRecurring,
    ;

    internal companion object {
        /** Maps Google Play's raw `RecurrenceMode` int (1/2/3); `null` for unknown values. */
        internal fun fromPlayValue(value: Int?): AppActorRecurrenceMode? = when (value) {
            1 -> InfiniteRecurring
            2 -> FiniteRecurring
            3 -> NonRecurring
            else -> null
        }
    }
}

/**
 * A single pricing phase of a subscription offer: a free trial, an introductory price, or the
 * full recurring price. Exposes Google Play's `ProductDetails.PricingPhase` fields publicly so a
 * paywall can render e.g. "3 days free, then ₺39.99/week" dynamically.
 *
 * Mirrors RevenueCat's `PricingPhase` (`billingPeriod`, `recurrenceMode`, `billingCycleCount`,
 * price) and Adapty's `AdaptyProductDiscountPhase`.
 */
public data class AppActorPricingPhase(
    /** Raw ISO-8601 billing period for this phase (e.g. `P3D`, `P1W`, `P1M`). */
    val billingPeriod: String? = null,
    /** Localized, currency-formatted price of this phase (e.g. `₺39.99`). `null` / empty for a free trial. */
    val formattedPrice: String? = null,
    /** Price in micro-units (1,000,000 micros = one currency unit). `0` for a free trial. */
    val priceAmountMicros: Long? = null,
    /** ISO 4217 currency code (e.g. `TRY`, `USD`). */
    val currencyCode: String? = null,
    /** Number of billing cycles this phase repeats for. `null` for infinite / non-recurring phases. */
    val billingCycleCount: Int? = null,
    /** How this phase repeats, or `null` when Google Play reports an unknown mode. */
    val recurrenceMode: AppActorRecurrenceMode? = null,
) {
    /** The [billingPeriod] parsed into a unit + count (parsed once at construction), or `null` when unparseable. */
    public val period: AppActorSubscriptionPeriod? = AppActorSubscriptionPeriod.fromIso8601(billingPeriod)

    /** `true` when this phase is a free trial (its price is zero). */
    public val isFreeTrial: Boolean
        get() = priceAmountMicros == 0L

    /**
     * How the subscriber pays for this phase, or `null` when the phase is not a finite-recurring
     * offer phase. Mirrors RevenueCat's `PricingPhase.offerPaymentMode`.
     */
    public val paymentMode: AppActorOfferPaymentMode?
        get() {
            if (recurrenceMode != AppActorRecurrenceMode.FiniteRecurring) return null
            return when {
                isFreeTrial -> AppActorOfferPaymentMode.FreeTrial
                billingCycleCount == 1 -> AppActorOfferPaymentMode.SinglePayment
                billingCycleCount != null && billingCycleCount > 1 -> AppActorOfferPaymentMode.DiscountedRecurring
                else -> null
            }
        }
}
