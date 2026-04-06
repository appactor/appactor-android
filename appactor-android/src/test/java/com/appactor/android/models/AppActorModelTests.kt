package com.appactor.android.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppActorModelTests {

    @Test
    fun `store maps known wire value`() {
        assertEquals(AppActorStore.PlayStore, AppActorStore.fromWireValue("play_store"))
        assertEquals(AppActorStore.Unknown, AppActorStore.fromWireValue("something_else"))
    }

    @Test
    fun `package type accepts backend variants`() {
        assertEquals(AppActorPackageType.TwoMonth, AppActorPackageType.fromServerValue("twoMonth"))
        assertEquals(AppActorPackageType.TwoMonth, AppActorPackageType.fromServerValue("two_month"))
        assertEquals(AppActorPackageType.Consumable, AppActorPackageType.fromServerValue("consumable"))
        assertEquals(AppActorPackageType.Custom, AppActorPackageType.fromServerValue("not_real"))
    }

    @Test
    fun `package identifier falls back to id when custom identifier is missing`() {
        val defaultPackage = AppActorPackage(
            id = "monthly",
            store = AppActorStore.PlayStore,
            productId = "premium_monthly",
        )
        val customPackage = AppActorPackage(
            id = "custom",
            customTypeIdentifier = "hero_offer",
            store = AppActorStore.PlayStore,
            productId = "premium_custom",
        )

        assertEquals("monthly", defaultPackage.identifier)
        assertEquals("hero_offer", customPackage.identifier)
    }

    @Test
    fun `customer info exposes active entitlement helpers`() {
        val active = AppActorEntitlementInfo(identifier = "premium", isActive = true)
        val inactive = AppActorEntitlementInfo(identifier = "pro", isActive = false)
        val customerInfo = AppActorCustomerInfo(
            entitlements = mapOf(
                "premium" to active,
                "pro" to inactive,
            )
        )

        assertEquals(setOf("premium"), customerInfo.activeEntitlementKeys)
        assertTrue(customerInfo.hasActiveEntitlement("premium"))
        assertFalse(customerInfo.hasActiveEntitlement("pro"))
    }

    @Test
    fun `subscription status exposes entitlement state`() {
        assertTrue(AppActorSubscriptionStatus.Active.isEntitled)
        assertTrue(AppActorSubscriptionStatus.GracePeriod.isEntitled)
        assertFalse(AppActorSubscriptionStatus.Expired.isEntitled)
    }
}
