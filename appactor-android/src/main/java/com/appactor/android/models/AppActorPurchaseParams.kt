package com.appactor.android.models

public data class AppActorPurchaseParams @JvmOverloads constructor(
    public val productId: String,
    public val storeProductId: String? = null,
    public val basePlanId: String? = null,
    public val offerId: String? = null,
    public val oldPurchaseToken: String? = null,
    public val replacementMode: AppActorSubscriptionReplacementMode? = null,
    public val metadata: AppActorMetadata = emptyMap(),
    public val productType: AppActorProductType = AppActorProductType.Unknown,
) {
    init {
        require(productId.isNotBlank()) {
            "purchase params productId must not be blank."
        }
    }
}

internal fun AppActorPurchaseParams.toAppActorPackage(): AppActorPackage {
    return AppActorPackage(
        id = storeProductId ?: productId,
        store = AppActorStore.PlayStore,
        productId = productId,
        storeProductId = storeProductId,
        productType = resolvedProductType(),
        basePlanId = basePlanId,
        offerId = offerId,
        metadata = metadata,
        oldPurchaseToken = oldPurchaseToken,
        replacementMode = replacementMode,
    )
}

internal fun AppActorPackage.toPurchaseParams(): AppActorPurchaseParams {
    return AppActorPurchaseParams(
        productId = productId,
        storeProductId = storeProductId,
        basePlanId = basePlanId,
        offerId = offerId,
        oldPurchaseToken = oldPurchaseToken,
        replacementMode = replacementMode,
        metadata = metadata,
        productType = productType,
    )
}

private fun AppActorPurchaseParams.resolvedProductType(): AppActorProductType {
    return when {
        productType != AppActorProductType.Unknown -> productType
        basePlanId != null || offerId != null || oldPurchaseToken != null || replacementMode != null ->
            AppActorProductType.Subscription

        else -> AppActorProductType.Unknown
    }
}
