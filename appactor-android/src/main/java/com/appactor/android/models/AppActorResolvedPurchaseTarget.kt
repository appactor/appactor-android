package com.appactor.android.models

import com.appactor.android.billing.AppActorStoreProductRequest
import com.appactor.android.billing.AppActorStorePurchase
import com.appactor.android.billing.toBillingReplacementMode

internal data class AppActorResolvedPurchaseTarget(
    val request: AppActorStoreProductRequest,
    val expectedProductId: String,
    val expectedProductType: AppActorProductType,
    val expectedBasePlanId: String? = null,
    val expectedOfferId: String? = null,
    val offeringId: String? = null,
    val packageId: String? = null,
    val requiresStoreResolution: Boolean = true,
) {
    fun matches(purchase: AppActorStorePurchase): Boolean {
        return purchase.productId == expectedProductId &&
            purchase.productType == expectedProductType &&
            purchase.basePlanId == expectedBasePlanId &&
            purchase.offerId == expectedOfferId
    }
}

internal fun AppActorPackage.toResolvedPurchaseTarget(
    appUserId: String,
): AppActorResolvedPurchaseTarget {
    return AppActorResolvedPurchaseTarget(
        request = AppActorStoreProductRequest(
            productId = productId,
            productType = productType,
            basePlanId = basePlanId,
            offerId = offerId,
            obfuscatedAccountId = appActorGoogleObfuscatedAccountId(appUserId),
            oldPurchaseToken = oldPurchaseToken,
            replacementMode = replacementMode?.toBillingReplacementMode(),
        ),
        expectedProductId = productId,
        expectedProductType = productType,
        expectedBasePlanId = basePlanId,
        expectedOfferId = offerId,
        offeringId = offeringId,
        packageId = id,
        requiresStoreResolution = false,
    )
}
