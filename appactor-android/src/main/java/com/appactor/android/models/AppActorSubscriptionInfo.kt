package com.appactor.android.models

public data class AppActorSubscriptionInfo(
    val subscriptionKey: String,
    val productIdentifier: String,
    val store: AppActorStore = AppActorStore.Unknown,
    val basePlanId: String? = null,
    val offerId: String? = null,
    val isActive: Boolean = false,
    val expiresDate: String? = null,
    val purchaseDate: String? = null,
    val startsAt: String? = null,
    val periodType: AppActorPeriodType? = null,
    val status: String? = null,
    val autoRenew: Boolean? = null,
    val isSandbox: Boolean? = null,
    val gracePeriodExpiresAt: String? = null,
    val unsubscribeDetectedAt: String? = null,
    val cancellationReason: AppActorCancellationReason? = null,
    val renewedAt: String? = null,
    val originalTransactionId: String? = null,
    val latestTransactionId: String? = null,
    val activePromotionalOfferType: String? = null,
    val activePromotionalOfferId: String? = null,
) {
    public val willRenew: Boolean
        get() = autoRenew ?: false

    public val isInGracePeriod: Boolean
        get() = status == "grace" || status == "grace_period"

    public val isTrial: Boolean
        get() = periodType == AppActorPeriodType.Trial
}
