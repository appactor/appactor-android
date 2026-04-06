package com.appactor.android.models

public data class AppActorOffering(
    val id: String,
    val displayName: String = id,
    val isCurrent: Boolean = false,
    val lookupKey: String? = null,
    val metadata: AppActorMetadata = emptyMap(),
    val packages: List<AppActorPackage> = emptyList(),
) {
    public val monthly: AppActorPackage?
        get() = packages.firstOrNull { it.packageType == AppActorPackageType.Monthly }

    public val annual: AppActorPackage?
        get() = packages.firstOrNull { it.packageType == AppActorPackageType.Annual }

    public val weekly: AppActorPackage?
        get() = packages.firstOrNull { it.packageType == AppActorPackageType.Weekly }

    public val sixMonth: AppActorPackage?
        get() = packages.firstOrNull { it.packageType == AppActorPackageType.SixMonth }

    public val threeMonth: AppActorPackage?
        get() = packages.firstOrNull { it.packageType == AppActorPackageType.ThreeMonth }

    public val twoMonth: AppActorPackage?
        get() = packages.firstOrNull { it.packageType == AppActorPackageType.TwoMonth }

    public val lifetime: AppActorPackage?
        get() = packages.firstOrNull { it.packageType == AppActorPackageType.Lifetime }

    public fun packageFor(type: AppActorPackageType): AppActorPackage? {
        return packages.firstOrNull { it.packageType == type }
    }
}
