package com.appactor.android.cache

import com.appactor.android.backend.client.AppActorBackendJson
import kotlinx.serialization.Serializable
import java.util.Locale

internal class AppActorOfferingsCacheStore(
    private val eTagManager: AppActorETagManager,
) {

    fun eTag(
        forceRefresh: Boolean = false,
        currentLocales: List<String>? = null,
    ): String? {
        if (forceRefresh) return null
        val cachedValue = eTagManager.cached(AppActorCacheResource.Offerings) ?: return null
        if (currentLocales != null && !isLocaleCompatible(cachedValue, currentLocales)) return null
        return cachedValue.eTag
    }

    fun load(): AppActorCachedValue? {
        return eTagManager.cached(AppActorCacheResource.Offerings)?.unwrap()
    }

    fun loadLocaleCompatible(currentLocales: List<String>): AppActorCachedValue? {
        val cachedValue = eTagManager.cached(AppActorCacheResource.Offerings) ?: return null
        if (!isLocaleCompatible(cachedValue, currentLocales)) return null
        return cachedValue.unwrap()
    }

    fun save(
        payload: String,
        eTag: String?,
        verified: Boolean,
        preferredLocales: List<String> = listOf(Locale.getDefault().toLanguageTag()),
    ) {
        val wrappedPayload = AppActorBackendJson.instance.encodeToString(
            StoredOfferingsPayload(
                payload = payload,
                preferredLocales = preferredLocales,
            )
        )
        eTagManager.storeFresh(
            resource = AppActorCacheResource.Offerings,
            payload = wrappedPayload,
            eTag = eTag,
            verified = verified,
        )
    }

    fun handleNotModified(
        rotatedETag: String? = null,
        currentLocales: List<String>? = null,
    ): AppActorCachedValue? {
        val cachedValue = eTagManager.handleNotModified(
            resource = AppActorCacheResource.Offerings,
            rotatedETag = rotatedETag,
        ) ?: return null
        if (currentLocales != null && !isLocaleCompatible(cachedValue, currentLocales)) return null
        return cachedValue.unwrap()
    }

    fun clear() {
        eTagManager.clear(AppActorCacheResource.Offerings)
    }

    private fun AppActorCachedValue.unwrap(): AppActorCachedValue {
        val wrapped = decodeStoredPayload(payload) ?: return this
        return copy(payload = wrapped.payload)
    }

    private fun isLocaleCompatible(
        cachedValue: AppActorCachedValue,
        currentLocales: List<String>,
    ): Boolean {
        if (currentLocales.isEmpty()) return false
        // Pre-locale cache entries stored the raw offerings payload directly. Keep
        // them readable during upgrade so offline users do not lose their cache
        // until the next successful network refresh.
        val wrapped = decodeStoredPayload(cachedValue.payload) ?: return true
        return wrapped.preferredLocales == currentLocales
    }

    private fun decodeStoredPayload(payload: String): StoredOfferingsPayload? {
        return runCatching {
            AppActorBackendJson.instance.decodeFromString<StoredOfferingsPayload>(payload)
        }.getOrNull()
    }

    @Serializable
    private data class StoredOfferingsPayload(
        val payload: String,
        val preferredLocales: List<String>,
    )
}
