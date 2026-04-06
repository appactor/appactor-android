package com.appactor.android.cache

internal class AppActorExperimentCacheStore(
    private val eTagManager: AppActorETagManager,
) {

    fun load(appUserId: String): AppActorCachedValue? {
        return eTagManager.cached(AppActorCacheResource.Experiments(appUserId))
    }

    fun save(
        appUserId: String,
        payload: String,
        verified: Boolean,
    ) {
        eTagManager.storeFresh(
            resource = AppActorCacheResource.Experiments(appUserId),
            payload = payload,
            eTag = null,
            verified = verified,
        )
    }

    fun clear(appUserId: String) {
        eTagManager.clear(AppActorCacheResource.Experiments(appUserId))
    }
}
