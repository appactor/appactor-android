package com.appactor.android.pipeline

import com.appactor.android.billing.AppActorStorePurchase
import com.appactor.android.models.AppActorConfiguration
import com.appactor.android.models.AppActorCustomerInfo
import com.appactor.android.models.AppActorEntitlementInfo
import com.appactor.android.models.AppActorEntitlementKeyResolver
import com.appactor.android.models.AppActorEnvironment
import com.appactor.android.models.AppActorOwnershipType
import com.appactor.android.models.AppActorPeriodType
import com.appactor.android.models.AppActorProductType
import com.appactor.android.models.AppActorStore
import com.appactor.android.models.AppActorSubscriptionStatus
import com.appactor.android.models.AppActorVerificationResult

/**
 * Synthesizes an on-device [AppActorCustomerInfo] for a successful purchase whose
 * receipt is queued for retry, so callers still observe granted entitlements while
 * the backend reconciles. Pure-ish: depends only on the static entitlement-key
 * resolver and the SDK [configuration]; holds no lock.
 */
internal class AppActorOfflineCustomerInfoBuilder(
    private val configuration: AppActorConfiguration,
) {

    fun buildOfflineCustomerInfo(
        purchase: AppActorStorePurchase,
        appUserId: String,
        productEntitlements: Map<String, List<String>>,
    ): AppActorCustomerInfo? {
        val keys = entitlementKeysForPurchase(purchase, productEntitlements)
        if (keys.isEmpty()) return null

        val entitlements = linkedMapOf<String, AppActorEntitlementInfo>().apply {
            keys.forEach { key ->
                put(
                    key,
                    AppActorEntitlementInfo(
                        identifier = key,
                        isActive = true,
                        status = "active",
                        productIdentifier = purchase.productId,
                        grantedBy = "purchase",
                        ownershipType = AppActorOwnershipType.Purchased,
                        periodType = AppActorPeriodType.Normal,
                        willRenew = purchase.productType == AppActorProductType.Subscription,
                        subscriptionStatus = AppActorSubscriptionStatus.Active,
                        store = AppActorStore.PlayStore,
                        basePlanId = purchase.basePlanId,
                        offerId = purchase.offerId,
                        isSandbox = configuration.environment == AppActorEnvironment.Sandbox,
                        purchaseDate = purchase.purchaseDateString(),
                        startsAt = purchase.purchaseDateString(),
                        latestPurchaseDate = purchase.purchaseDateString(),
                    )
                )
            }
        }

        return AppActorCustomerInfo(
            entitlements = entitlements,
            appUserId = appUserId,
            snapshotDate = purchase.purchaseDateString(),
            requestDate = purchase.purchaseDateString(),
            isComputedOffline = true,
            productEntitlements = productEntitlements,
            verification = AppActorVerificationResult.VerifiedOnDevice,
        )
    }

    private fun entitlementKeysForPurchase(
        purchase: AppActorStorePurchase,
        productEntitlements: Map<String, List<String>>,
    ): List<String> {
        return AppActorEntitlementKeyResolver.entitlementKeysForPurchase(
            purchase = purchase,
            productEntitlements = productEntitlements,
        )
    }
}
