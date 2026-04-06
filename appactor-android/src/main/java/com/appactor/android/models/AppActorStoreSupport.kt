package com.appactor.android.models

internal data class AppActorProductQuery(
    val productId: String,
    val productType: AppActorProductType,
    val basePlanId: String? = null,
    val offerId: String? = null,
)

internal data class AppActorStoreProductInfo(
    val store: AppActorStore,
    val productId: String,
    val productType: AppActorProductType,
    val basePlanId: String? = null,
    val offerId: String? = null,
    val localizedPriceString: String? = null,
    val priceAmountMicros: Long? = null,
    val currencyCode: String? = null,
    val title: String? = null,
    val displayName: String? = null,
    val description: String? = null,
)

public data class AppActorStorefront(
    val store: AppActorStore,
    val countryCode: String? = null,
)

public enum class AppActorStoreCapability {
    Purchases,
    Subscriptions,
    InAppProducts,
    PurchaseHistory,
    Storefront,
}
