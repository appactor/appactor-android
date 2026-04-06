package com.appactor.android.models

import com.appactor.android.billing.AppActorStorePurchase
import com.appactor.android.billing.AppActorStorePurchaseState
import org.junit.Assert.assertEquals
import org.junit.Test

class AppActorEntitlementKeyResolverTests {

    @Test
    fun `resolver prefers compound android key over flat key`() {
        val purchase = AppActorStorePurchase(
            productId = "com.appactor.pro.monthly",
            productType = AppActorProductType.Subscription,
            purchaseToken = "token_123",
            purchaseTimeMillis = 1_710_000_000_000,
            purchaseState = AppActorStorePurchaseState.Purchased,
            basePlanId = "monthly001",
        )

        val keys = AppActorEntitlementKeyResolver.entitlementKeysForPurchase(
            purchase = purchase,
            productEntitlements = mapOf(
                "android:com.appactor.pro.monthly" to listOf("flat"),
                "android:com.appactor.pro.monthly:monthly001" to listOf("compound"),
            ),
        )

        assertEquals(listOf("compound"), keys)
    }

    @Test
    fun `resolver falls back to flat android key when base plan mapping is absent`() {
        val keys = AppActorEntitlementKeyResolver.entitlementKeysForProduct(
            productId = "coins_pack",
            basePlanId = null,
            productEntitlements = mapOf(
                "android:coins_pack" to listOf("coins"),
            ),
        )

        assertEquals(listOf("coins"), keys)
    }

    @Test
    fun `resolver ignores other store mappings and malformed keys`() {
        val keys = AppActorEntitlementKeyResolver.entitlementKeysForProduct(
            productId = "com.appactor.pro.monthly",
            basePlanId = "monthly001",
            productEntitlements = mapOf(
                "ios:com.appactor.pro.monthly" to listOf("ios_premium"),
                "com.appactor.pro.monthly" to listOf("legacy"),
                "android:other.sku:monthly001" to listOf("wrong"),
            ),
        )

        assertEquals(emptyList<String>(), keys)
    }
}
