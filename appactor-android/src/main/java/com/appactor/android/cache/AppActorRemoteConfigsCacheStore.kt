package com.appactor.android.cache

internal class AppActorRemoteConfigsCacheStore(
    private val eTagManager: AppActorETagManager,
) {

    fun eTag(
        appUserId: String,
        forceRefresh: Boolean = false,
    ): String? {
        return eTagManager.eTag(
            resource = AppActorCacheResource.RemoteConfigs(appUserId),
            forceRefresh = forceRefresh,
        )
    }

    fun load(appUserId: String): AppActorCachedValue? {
        return eTagManager.cached(AppActorCacheResource.RemoteConfigs(appUserId))
    }

    fun save(
        appUserId: String,
        payload: String,
        eTag: String?,
        verified: Boolean,
    ) {
        eTagManager.storeFresh(
            resource = AppActorCacheResource.RemoteConfigs(appUserId),
            payload = payload,
            eTag = eTag,
            verified = verified,
        )
    }

    fun handleNotModified(
        appUserId: String,
        rotatedETag: String? = null,
    ): AppActorCachedValue? {
        return eTagManager.handleNotModified(
            resource = AppActorCacheResource.RemoteConfigs(appUserId),
            rotatedETag = rotatedETag,
        )
    }

    fun clear(appUserId: String) {
        eTagManager.clear(AppActorCacheResource.RemoteConfigs(appUserId))
    }
}
