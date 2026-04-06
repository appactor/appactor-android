package com.appactor.android.models

public enum class AppActorPackageType(public val wireValue: String) {
    Weekly("weekly"),
    Monthly("monthly"),
    TwoMonth("two_month"),
    ThreeMonth("three_month"),
    SixMonth("six_month"),
    Annual("annual"),
    Lifetime("lifetime"),
    Consumable("consumable"),
    Custom("custom");

    public companion object {
        public fun fromServerValue(value: String?): AppActorPackageType {
            return when (value) {
                "weekly" -> Weekly
                "monthly" -> Monthly
                "twoMonth", "two_month", "two_months" -> TwoMonth
                "threeMonth", "three_month", "three_months" -> ThreeMonth
                "sixMonth", "six_month", "six_months" -> SixMonth
                "annual" -> Annual
                "lifetime" -> Lifetime
                "consumable" -> Consumable
                else -> Custom
            }
        }
    }
}
