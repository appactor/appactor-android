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
        private val ISO_8601_PERIOD =
            Regex("^P(?:(\\d+)Y)?(?:(\\d+)M)?(?:(\\d+)W)?(?:(\\d+)D)?$", RegexOption.IGNORE_CASE)

        /**
         * Parses an ISO-8601 subscription period (`P3D`, `P1W`, `P1M`, `P1Y`, or combinations)
         * into a [unit] + [numberOfUnits]. When more than one component is present the smallest
         * non-zero unit wins (matching Google Play's single-unit billing periods). Returns `null`
         * for unparseable or zero-length periods.
         */
        internal fun fromIso8601(period: String?): AppActorSubscriptionPeriod? {
            val match = ISO_8601_PERIOD.matchEntire(period?.trim().orEmpty()) ?: return null
            val (years, months, weeks, days) = match.destructured
            val yearCount = years.toIntOrNull() ?: 0
            val monthCount = months.toIntOrNull() ?: 0
            val weekCount = weeks.toIntOrNull() ?: 0
            val dayCount = days.toIntOrNull() ?: 0
            return when {
                dayCount > 0 -> AppActorSubscriptionPeriod(AppActorSubscriptionPeriodUnit.Day, dayCount)
                weekCount > 0 -> AppActorSubscriptionPeriod(AppActorSubscriptionPeriodUnit.Week, weekCount)
                monthCount > 0 -> AppActorSubscriptionPeriod(AppActorSubscriptionPeriodUnit.Month, monthCount)
                yearCount > 0 -> AppActorSubscriptionPeriod(AppActorSubscriptionPeriodUnit.Year, yearCount)
                else -> null
            }
        }

        /**
         * Approximate total days for an ISO-8601 period (1M = 30d, 1Y = 365d), used to compare
         * durations. Returns `null` for unparseable or zero periods. Shared with
         * `SubscriptionOfferSelector` so the ISO-8601 regex lives in exactly one place.
         */
        internal fun iso8601ToDays(period: String?): Int? {
            val match = ISO_8601_PERIOD.matchEntire(period?.trim().orEmpty()) ?: return null
            val (years, months, weeks, days) = match.destructured
            val totalDays = (years.toIntOrNull() ?: 0) * 365 +
                (months.toIntOrNull() ?: 0) * 30 +
                (weeks.toIntOrNull() ?: 0) * 7 +
                (days.toIntOrNull() ?: 0)
            return totalDays.takeIf { it > 0 }
        }
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
    /** Raw Google Play recurrence mode (1 = infinite recurring, 2 = finite recurring, 3 = non-recurring). */
    val recurrenceMode: Int? = null,
) {
    /** The [billingPeriod] parsed into a unit + count, or `null` when it cannot be parsed. */
    public val period: AppActorSubscriptionPeriod?
        get() = AppActorSubscriptionPeriod.fromIso8601(billingPeriod)

    /** `true` when this phase is a free trial (its price is zero). */
    public val isFreeTrial: Boolean
        get() = priceAmountMicros == 0L

    /**
     * How the subscriber pays for this phase, or `null` when the phase is not a finite-recurring
     * offer phase. Mirrors RevenueCat's `PricingPhase.offerPaymentMode`.
     */
    public val paymentMode: AppActorOfferPaymentMode?
        get() {
            if (recurrenceMode != RECURRENCE_MODE_FINITE_RECURRING) return null
            return when {
                priceAmountMicros == 0L -> AppActorOfferPaymentMode.FreeTrial
                billingCycleCount == 1 -> AppActorOfferPaymentMode.SinglePayment
                billingCycleCount != null && billingCycleCount > 1 -> AppActorOfferPaymentMode.DiscountedRecurring
                else -> null
            }
        }
}

/** Google Play `ProductDetails.RecurrenceMode.FINITE_RECURRING`. */
private const val RECURRENCE_MODE_FINITE_RECURRING = 2
