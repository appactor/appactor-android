package com.appactor.android.cache

internal class AppActorOfferingsCacheStore(
    private val eTagManager: AppActorETagManager,
) {

    fun eTag(forceRefresh: Boolean = false): String? {
        return eTagManager.eTag(
            resource = AppActorCacheResource.Offerings,
            forceRefresh = forceRefresh,
        )
    }

    fun load(): AppActorCachedValue? {
        return eTagManager.cached(AppActorCacheResource.Offerings)
    }

    fun save(
        payload: String,
        eTag: String?,
        verified: Boolean,
    ) {
        eTagManager.storeFresh(
            resource = AppActorCacheResource.Offerings,
            payload = payload,
            eTag = eTag,
            verified = verified,
        )
    }

    fun handleNotModified(rotatedETag: String? = null): AppActorCachedValue? {
        return eTagManager.handleNotModified(
            resource = AppActorCacheResource.Offerings,
            rotatedETag = rotatedETag,
        )
    }

    fun clear() {
        eTagManager.clear(AppActorCacheResource.Offerings)
    }
}
