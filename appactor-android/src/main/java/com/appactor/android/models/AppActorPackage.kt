package com.appactor.android.models

public data class AppActorPackage(
    val id: String,
    val packageType: AppActorPackageType = AppActorPackageType.Custom,
    val customTypeIdentifier: String? = null,
    val store: AppActorStore,
    val productId: String,
    val storeProductId: String? = null,
    val serverId: String? = null,
    val productType: AppActorProductType = AppActorProductType.Unknown,
    val basePlanId: String? = null,
    val offerId: String? = null,
    val localizedPriceString: String? = null,
    val price: Double? = null,
    val currencyCode: String? = null,
    val displayName: String? = null,
    val productName: String? = null,
    val productDescription: String? = null,
    val metadata: AppActorMetadata = emptyMap(),
    val tokenAmount: Int? = null,
    val position: Int? = null,
    val oldPurchaseToken: String? = null,
    val replacementMode: AppActorSubscriptionReplacementMode? = null,
    val offeringId: String? = null,
) {
    public val identifier: String
        get() = customTypeIdentifier ?: id
}
