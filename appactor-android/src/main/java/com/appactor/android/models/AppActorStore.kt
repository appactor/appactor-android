package com.appactor.android.models

public enum class AppActorStore(public val wireValue: String) {
    PlayStore("play_store"),
    AppStore("app_store"),
    Stripe("stripe"),
    Promotional("promotional"),
    Unknown("unknown");

    public companion object {
        public fun fromWireValue(value: String?): AppActorStore {
            return entries.firstOrNull { it.wireValue == value } ?: Unknown
        }
    }
}
