package com.appactor.android.models

import kotlin.math.roundToLong

public data class AppActorPackage(
    val id: String,
    val packageType: AppActorPackageType = AppActorPackageType.Custom,
    val customTypeIdentifier: String? = null,
    val store: AppActorStore,
    val productId: String,
    val storeProductId: String? = null,
    val productType: AppActorProductType = AppActorProductType.Unknown,
    val basePlanId: String? = null,
    val offerId: String? = null,
    val localizedPriceString: String? = null,
    val price: Double? = null,
    val currencyCode: String? = null,
    val displayName: String? = null,
    val productName: String? = null,
    val productDescription: String? = null,
    val metadata: AppActorMetadata = emptyMap(),
    val tokenAmount: Int? = null,
    val position: Int? = null,
    val oldPurchaseToken: String? = null,
    val replacementMode: AppActorSubscriptionReplacementMode? = null,
    val offeringId: String? = null,
    /**
     * Ordered pricing phases of the resolved subscription offer (trial / intro phases first,
     * the full recurring price last). Empty for one-time products, Apple packages, and base
     * plans without a resolved store offer.
     */
    val pricingPhases: List<AppActorPricingPhase> = emptyList(),
) {
    /**
     * Semantic identifier for package selection.
     *
     * Standard packages keep their package type value even when [id] is a backend UUID.
     */
    public val identifier: String
        get() = customTypeIdentifier
            ?: packageType.takeUnless { it == AppActorPackageType.Custom }?.wireValue
            ?: id

    public val priceAmountMicros: Long?
        get() = price
            ?.takeIf { it.isFinite() }
            ?.let { (it * 1_000_000).roundToLong() }

    /**
     * The full (recurring) price phase — the last phase of the offer. Mirrors RevenueCat's
     * `SubscriptionOption.fullPricePhase`.
     */
    public val fullPricePhase: AppActorPricingPhase?
        get() = pricingPhases.lastOrNull()

    /**
     * The free-trial phase — the first non-recurring phase priced at zero. `null` when the offer
     * has no free trial. Mirrors RevenueCat's `SubscriptionOption.freePhase`.
     */
    public val freePhase: AppActorPricingPhase?
        get() = pricingPhases.dropLast(1).firstOrNull { it.priceAmountMicros == 0L }

    /**
     * The introductory-price phase — the first non-recurring phase priced above zero. `null` when
     * the offer has no intro price. Mirrors RevenueCat's `SubscriptionOption.introPhase`.
     */
    public val introPhase: AppActorPricingPhase?
        get() = pricingPhases.dropLast(1).firstOrNull { (it.priceAmountMicros ?: 0L) > 0L }

    /** `true` when the resolved offer includes a free-trial phase. */
    public val hasFreeTrial: Boolean
        get() = freePhase != null
}
