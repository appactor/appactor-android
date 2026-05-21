package com.appactor.android.models

internal fun appActorStoreLookupProductId(
    productId: String,
    storeProductId: String?,
): String {
    return storeProductId?.takeIf { it.isNotBlank() } ?: productId
}

internal fun AppActorPackage.storeLookupProductId(): String {
    return appActorStoreLookupProductId(productId = productId, storeProductId = storeProductId)
}

internal fun AppActorPurchaseParams.storeLookupProductId(): String {
    return appActorStoreLookupProductId(productId = productId, storeProductId = storeProductId)
}
