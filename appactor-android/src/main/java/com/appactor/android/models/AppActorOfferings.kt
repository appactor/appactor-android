package com.appactor.android.models

public data class AppActorOfferings(
    val current: AppActorOffering? = null,
    val all: Map<String, AppActorOffering> = emptyMap(),
    val productEntitlements: Map<String, List<String>> = emptyMap(),
    val verification: AppActorVerificationResult = AppActorVerificationResult.NotRequested,
) {
    public fun offering(id: String): AppActorOffering? = all[id]

    public fun offeringByLookupKey(lookupKey: String): AppActorOffering? {
        return all.values.firstOrNull { it.lookupKey == lookupKey }
    }
}
