package com.appactor.android.cache

import com.appactor.android.backend.client.AppActorBackendJson
import com.appactor.android.models.AppActorProductType
import kotlinx.serialization.Serializable

@Serializable
internal data class AppActorOfflineProductCatalog(
    val productEntitlements: Map<String, List<String>> = emptyMap(),
    val oneTimeProductKinds: Map<String, String> = emptyMap(),
) {
    fun oneTimeProductType(productId: String): AppActorProductType? {
        return AppActorProductType.fromWireValue(
            oneTimeProductKinds[oneTimeKey(productId)]
        ).takeIf { it == AppActorProductType.Consumable || it == AppActorProductType.NonConsumable }
    }

    companion object {
        fun oneTimeKey(productId: String): String = "android:$productId"
    }
}

internal class AppActorOfflineProductCatalogStore(
    private val eTagManager: AppActorETagManager,
) {

    fun load(): AppActorOfflineProductCatalog? {
        val payload = eTagManager.cached(AppActorCacheResource.OfflineProductCatalog)?.payload
            ?: return null
        return runCatching {
            AppActorBackendJson.instance.decodeFromString<AppActorOfflineProductCatalog>(payload)
        }.getOrNull()
    }

    fun save(catalog: AppActorOfflineProductCatalog) {
        eTagManager.storeFresh(
            resource = AppActorCacheResource.OfflineProductCatalog,
            payload = AppActorBackendJson.instance.encodeToString(catalog),
            eTag = null,
            verified = true,
        )
    }

    fun clear() {
        eTagManager.clear(AppActorCacheResource.OfflineProductCatalog)
    }
}
