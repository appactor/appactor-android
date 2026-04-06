package com.appactor.android.cache

internal class AppActorETagManager(
    private val diskStore: AppActorCacheDiskStore,
    private val responseVerificationEnabled: Boolean = false,
) {

    fun eTag(
        resource: AppActorCacheResource,
        forceRefresh: Boolean = false,
    ): String? {
        if (forceRefresh) return null
        val entry = diskStore.load(resource) ?: return null
        if (responseVerificationEnabled && !entry.responseVerified) {
            return null
        }
        return entry.eTag
    }

    fun storeFresh(
        resource: AppActorCacheResource,
        payload: String,
        eTag: String?,
        verified: Boolean = responseVerificationEnabled,
    ) {
        diskStore.save(
            entry = AppActorCacheEntry(
                payload = payload,
                eTag = eTag,
                cachedAtMillis = System.currentTimeMillis(),
                responseVerified = verified,
            ),
            resource = resource,
        )
    }

    fun handleNotModified(
        resource: AppActorCacheResource,
        rotatedETag: String? = null,
    ): AppActorCachedValue? {
        val entry = diskStore.updateTimestamp(resource, rotatedETag) ?: return null
        if (responseVerificationEnabled && !entry.responseVerified) {
            return null
        }
        return AppActorCachedValue(
            payload = entry.payload,
            eTag = entry.eTag,
            cachedAtMillis = entry.cachedAtMillis,
        )
    }

    fun cached(resource: AppActorCacheResource): AppActorCachedValue? {
        val entry = diskStore.load(resource) ?: return null
        if (responseVerificationEnabled && !entry.responseVerified) {
            return null
        }
        return AppActorCachedValue(
            payload = entry.payload,
            eTag = entry.eTag,
            cachedAtMillis = entry.cachedAtMillis,
        )
    }

    fun isFresh(
        resource: AppActorCacheResource,
        ttlMillis: Long,
    ): Boolean {
        val entry = diskStore.load(resource) ?: return false
        if (responseVerificationEnabled && !entry.responseVerified) {
            return false
        }
        return System.currentTimeMillis() - entry.cachedAtMillis < ttlMillis
    }

    fun clearUnverifiedIfNeeded() {
        if (responseVerificationEnabled) {
            diskStore.clearAllUnverified()
        }
    }

    fun clear(resource: AppActorCacheResource) {
        diskStore.clear(resource)
    }

    fun clearAll() {
        diskStore.clearAll()
    }
}
