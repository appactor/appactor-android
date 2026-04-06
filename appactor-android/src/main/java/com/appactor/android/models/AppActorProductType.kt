package com.appactor.android.models

public enum class AppActorProductType(public val wireValue: String) {
    Subscription("subscription"),
    NonConsumable("non_consumable"),
    Consumable("consumable"),
    Unknown("unknown");

    public companion object {
        public fun fromWireValue(value: String?): AppActorProductType {
            return entries.firstOrNull { it.wireValue == value } ?: Unknown
        }
    }
}
