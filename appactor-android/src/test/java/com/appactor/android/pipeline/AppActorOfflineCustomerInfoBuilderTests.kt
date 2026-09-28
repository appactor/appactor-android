package com.appactor.android.pipeline

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.appactor.android.billing.AppActorStorePurchase
import com.appactor.android.billing.AppActorStorePurchaseState
import com.appactor.android.models.AppActorConfiguration
import com.appactor.android.models.AppActorCustomerInfo
import com.appactor.android.models.AppActorEntitlementInfo
import com.appactor.android.models.AppActorProductType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppActorOfflineCustomerInfoBuilderTests {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `a queued purchase keeps the user's other entitlements`() {
        val builder = AppActorOfflineCustomerInfoBuilder(
            AppActorConfiguration(context = context, apiKey = "pk_test_123"),
        )
        val cached = AppActorCustomerInfo(
            entitlements = mapOf("premium" to AppActorEntitlementInfo(identifier = "premium", isActive = true)),
            appUserId = "user_1",
            managementUrl = "https://play.google.com/store/account/subscriptions",
        )
        val purchase = AppActorStorePurchase(
            productId = "com.appactor.no_ads",
            productType = AppActorProductType.NonConsumable,
            purchaseToken = "token_no_ads",
            purchaseTimeMillis = 1_710_000_000_000,
            purchaseState = AppActorStorePurchaseState.Purchased,
        )

        val info = builder.buildOfflineCustomerInfo(
            purchase = purchase,
            appUserId = "user_1",
            productEntitlements = mapOf("android:com.appactor.no_ads" to listOf("no_ads")),
            baseCustomer = cached,
        )!!

        assertEquals(setOf("premium", "no_ads"), info.activeEntitlementKeys)
        assertEquals(cached.managementUrl, info.managementUrl)
        assertTrue(info.isComputedOffline)
    }

    @Test
    fun `an offline entitlement dates its first and latest purchase to the purchase`() {
        val builder = AppActorOfflineCustomerInfoBuilder(
            AppActorConfiguration(context = context, apiKey = "pk_test_123"),
        )
        val purchase = AppActorStorePurchase(
            productId = "com.appactor.no_ads",
            productType = AppActorProductType.NonConsumable,
            purchaseToken = "token_no_ads",
            purchaseTimeMillis = 1_710_000_000_000,
            purchaseState = AppActorStorePurchaseState.Purchased,
        )

        val entitlement = builder.buildOfflineCustomerInfo(
            purchase = purchase,
            appUserId = "user_1",
            productEntitlements = mapOf("android:com.appactor.no_ads" to listOf("no_ads")),
        )!!.entitlements.getValue("no_ads")

        assertEquals(purchase.purchaseDateString(), entitlement.originalPurchaseDate)
        assertEquals(purchase.purchaseDateString(), entitlement.latestPurchaseDate)
    }
}
