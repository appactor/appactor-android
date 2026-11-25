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
        val (cachedValue, wrapped) = loadValidStoredEntry() ?: return null
        if (currentLocales != null && !isLocaleCompatible(wrapped, currentLocales)) return null
        return cachedValue.eTag
    }

    fun load(): AppActorCachedValue? {
        val (cachedValue, wrapped) = loadValidStoredEntry() ?: return null
        return cachedValue.copy(payload = wrapped.payload)
    }

    fun loadLocaleCompatible(currentLocales: List<String>): AppActorCachedValue? {
        val (cachedValue, wrapped) = loadValidStoredEntry() ?: return null
        if (!isLocaleCompatible(wrapped, currentLocales)) return null
        return cachedValue.copy(payload = wrapped.payload)
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
                schemaVersion = CURRENT_SCHEMA_VERSION,
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
        val (_, existingWrapped) = loadValidStoredEntry() ?: return null
        if (currentLocales != null && !isLocaleCompatible(existingWrapped, currentLocales)) return null
        val updated = eTagManager.handleNotModified(
            resource = AppActorCacheResource.Offerings,
            rotatedETag = rotatedETag,
        ) ?: return null
        val updatedWrapped = decodeValidStoredPayload(updated.payload) ?: return null
        if (currentLocales != null && !isLocaleCompatible(updatedWrapped, currentLocales)) return null
        return updated.copy(payload = updatedWrapped.payload)
    }

    fun clear() {
        eTagManager.clear(AppActorCacheResource.Offerings)
    }

    private fun loadValidStoredEntry(): Pair<AppActorCachedValue, StoredOfferingsPayload>? {
        val cachedValue = eTagManager.cached(AppActorCacheResource.Offerings) ?: return null
        val wrapped = decodeValidStoredPayload(cachedValue.payload)
        if (wrapped == null) {
            // Discard legacy (pre-schema-bump keyless or pre-locale) or incompatible cache entries
            // so upgraded apps never send a legacy ETag for 304 or serve keyless packages.
            eTagManager.clear(AppActorCacheResource.Offerings)
            return null
        }
        return cachedValue to wrapped
    }

    private fun isLocaleCompatible(
        wrapped: StoredOfferingsPayload,
        currentLocales: List<String>,
    ): Boolean {
        if (currentLocales.isEmpty()) return false
        return wrapped.preferredLocales == currentLocales
    }

    private fun decodeValidStoredPayload(payload: String): StoredOfferingsPayload? {
        val wrapped = runCatching {
            AppActorBackendJson.instance.decodeFromString<StoredOfferingsPayload>(payload)
        }.getOrNull() ?: return null
        return wrapped.takeIf { it.schemaVersion == CURRENT_SCHEMA_VERSION }
    }

    internal companion object {
        // v1 (unversioned) cached offerings DTOs before AppActorPackageDTO carried lookupKey.
        // v2 requires schemaVersion == 2 so upgraded apps invalidate keyless disk cache cleanly.
        const val CURRENT_SCHEMA_VERSION: Int = 2
    }

    @Serializable
    private data class StoredOfferingsPayload(
        val payload: String,
        val preferredLocales: List<String>,
        val schemaVersion: Int,
    )
}
