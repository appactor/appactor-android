package com.appactor.android.models

public data class AppActorCustomerInfo(
    val entitlements: Map<String, AppActorEntitlementInfo> = emptyMap(),
    val subscriptions: Map<String, AppActorSubscriptionInfo> = emptyMap(),
    val nonSubscriptions: Map<String, List<AppActorNonSubscription>> = emptyMap(),
    val consumableBalances: Map<String, Int>? = null,
    val tokenBalance: AppActorTokenBalance? = null,
    val snapshotDate: String? = null,
    val appUserId: String? = null,
    val requestId: String? = null,
    val requestDate: String? = null,
    val firstSeen: String? = null,
    val lastSeen: String? = null,
    val managementUrl: String? = null,
    val isComputedOffline: Boolean = false,
    val productEntitlements: Map<String, List<String>> = emptyMap(),
) {
    public val activeEntitlements: Map<String, AppActorEntitlementInfo>
        get() = entitlements.filterValues { it.isActive }

    public val activeEntitlementKeys: Set<String>
        get() = activeEntitlements.keys

    public fun hasActiveEntitlement(key: String): Boolean = entitlements[key]?.isActive == true

    public companion object {
        public val empty: AppActorCustomerInfo = AppActorCustomerInfo()
    }
}
