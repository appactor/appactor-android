package com.appactor.android.models

import com.appactor.android.billing.AppActorStorePurchase

internal object AppActorEntitlementKeyResolver {

    fun entitlementKeysForPurchase(
        purchase: AppActorStorePurchase,
        productEntitlements: Map<String, List<String>>,
    ): List<String> {
        return entitlementKeysForProduct(
            productId = purchase.productId,
            basePlanId = purchase.basePlanId,
            productEntitlements = productEntitlements,
        )
    }

    fun entitlementKeysForProduct(
        productId: String,
        basePlanId: String?,
        productEntitlements: Map<String, List<String>>,
    ): List<String> {
        if (productId.isBlank() || productEntitlements.isEmpty()) return emptyList()

        val compoundKey = basePlanId
            ?.takeUnless { it.isBlank() }
            ?.let { "android:$productId:$it" }
        val flatKey = "android:$productId"

        return when {
            compoundKey != null && productEntitlements.containsKey(compoundKey) -> {
                productEntitlements[compoundKey].orEmpty()
            }

            productEntitlements.containsKey(flatKey) -> {
                productEntitlements[flatKey].orEmpty()
            }

            else -> emptyList()
        }
    }
}
