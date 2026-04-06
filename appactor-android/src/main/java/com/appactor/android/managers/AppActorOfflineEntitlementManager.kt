package com.appactor.android.managers

import com.appactor.android.backend.client.AppActorBackendJson
import com.appactor.android.backend.dto.AppActorCustomerEnvelopeDTO
import com.appactor.android.backend.mappers.toModel
import com.appactor.android.billing.AppActorStoreAdapter
import com.appactor.android.cache.AppActorCustomerCacheStore
import com.appactor.android.models.AppActorCustomerInfo
import com.appactor.android.models.AppActorEntitlementKeyResolver

internal class AppActorOfflineEntitlementManager(
    private val customerCacheStore: AppActorCustomerCacheStore,
    private val offeringsManager: AppActorOfferingsManager,
    private val storeAdapter: AppActorStoreAdapter,
    private val dateProviderMillis: () -> Long = { System.currentTimeMillis() },
) {

    suspend fun activeEntitlementKeysOffline(appUserId: String): Set<String> {
        val productEntitlements = offeringsManager.currentProductEntitlements()
        if (productEntitlements.isNotEmpty()) {
            val derivedKeys = runCatching { storeAdapter.queryActivePurchases() }
                .getOrDefault(emptyList())
                .flatMap { purchase ->
                    AppActorEntitlementKeyResolver.entitlementKeysForPurchase(
                        purchase = purchase,
                        productEntitlements = productEntitlements,
                    )
                }
                .toSet()
            if (derivedKeys.isNotEmpty()) {
                return derivedKeys
            }
        }

        return freshCachedCustomer(appUserId)?.activeEntitlementKeys.orEmpty()
    }

    fun freshCachedCustomer(appUserId: String): AppActorCustomerInfo? {
        val cached = customerCacheStore.load(appUserId)
            ?.takeIf { isFresh(it.cachedAtMillis) }
            ?: return null
        return decodeCachedCustomer(appUserId = appUserId, payload = cached.payload)
    }

    private fun decodeCachedCustomer(
        appUserId: String,
        payload: String,
    ): AppActorCustomerInfo {
        val envelope = AppActorBackendJson.instance.decodeFromString<AppActorCustomerEnvelopeDTO>(payload)
        return envelope.toModel(productEntitlements = offeringsManager.currentProductEntitlements())
            .copy(
                appUserId = envelope.appUserId ?: appUserId,
            )
    }

    private fun isFresh(cachedAtMillis: Long): Boolean {
        return dateProviderMillis() - cachedAtMillis < CUSTOMER_CACHE_TTL_MILLIS
    }

    companion object {
        internal const val CUSTOMER_CACHE_TTL_MILLIS: Long = 24 * 60 * 60 * 1_000
    }
}
