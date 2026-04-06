package com.appactor.android.models

public enum class AppActorOwnershipType(public val wireValue: String) {
    Purchased("purchased"),
    FamilyShared("family_shared"),
    Unknown("unknown");

    public companion object {
        public fun fromWireValue(value: String?): AppActorOwnershipType {
            return entries.firstOrNull { it.wireValue == value } ?: Unknown
        }
    }
}
