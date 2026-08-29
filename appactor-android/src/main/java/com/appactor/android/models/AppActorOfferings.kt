package com.appactor.android.models

public data class AppActorOfferings(
    val current: AppActorOffering? = null,
    val all: Map<String, AppActorOffering> = emptyMap(),
    val productEntitlements: Map<String, List<String>> = emptyMap(),
    val verification: AppActorVerificationResult = AppActorVerificationResult.NotRequested,
) {
    /** Every offering as a list: the current one first, then by [AppActorOffering.offeringKey]. */
    public val allOfferings: List<AppActorOffering>
        get() = all.values.sortedWith(compareBy({ !it.isCurrent }, { it.offeringKey }))

    /** Lookup by server `id` (the key of [all]). For the dashboard key use [getOffering]. */
    public fun offering(id: String): AppActorOffering? = all[id]

    /**
     * Returns the offering with the given [AppActorOffering.offeringKey], or `null`.
     *
     * ```kotlin
     * val onboarding = offerings.getOffering("onboarding")
     * ```
     */
    public fun getOffering(offeringKey: String): AppActorOffering? =
        all.values.firstOrNull { it.offeringKey == offeringKey }

    /** `offerings["onboarding"]` — same as [getOffering]. */
    public operator fun get(offeringKey: String): AppActorOffering? = getOffering(offeringKey)
}
