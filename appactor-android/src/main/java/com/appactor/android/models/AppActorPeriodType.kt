package com.appactor.android.models

public enum class AppActorPeriodType(public val wireValue: String) {
    Weekly("weekly"),
    Monthly("monthly"),
    TwoMonth("two_month"),
    ThreeMonth("three_month"),
    SixMonth("six_month"),
    Annual("annual"),
    Lifetime("lifetime"),
    Normal("normal"),
    Trial("trial"),
    Intro("intro"),
    Unknown("unknown");

    public companion object {
        public fun fromWireValue(value: String?): AppActorPeriodType {
            return entries.firstOrNull { it.wireValue == value } ?: Unknown
        }
    }
}
