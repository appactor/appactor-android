package com.appactor.android.models

public enum class AppActorSubscriptionStatus(public val wireValue: String) {
    Active("active"),
    GracePeriod("grace_period"),
    BillingRetry("billing_retry"),
    Expired("expired"),
    Revoked("revoked"),
    Upgraded("upgraded"),
    Unknown("unknown");

    public val isEntitled: Boolean
        get() = this == Active || this == GracePeriod

    public companion object {
        public fun fromWireValue(value: String?): AppActorSubscriptionStatus {
            // The backend sends "grace"; iOS reads both spellings too.
            if (value == "grace") return GracePeriod
            return entries.firstOrNull { it.wireValue == value } ?: Unknown
        }
    }
}
