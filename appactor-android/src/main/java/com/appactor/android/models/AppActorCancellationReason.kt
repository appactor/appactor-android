package com.appactor.android.models

public enum class AppActorCancellationReason(public val wireValue: String) {
    CustomerCancelled("customer_cancelled"),
    DeveloperCancelled("developer_cancelled"),
    Unknown("unknown");

    public companion object {
        public fun fromWireValue(value: String?): AppActorCancellationReason {
            return entries.firstOrNull { it.wireValue == value } ?: Unknown
        }
    }
}
