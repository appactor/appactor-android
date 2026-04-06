package com.appactor.android.cache

internal class AppActorCustomerCacheStore(
    private val eTagManager: AppActorETagManager,
) {

    fun eTag(
        appUserId: String,
        forceRefresh: Boolean = false,
    ): String? {
        return eTagManager.eTag(
            resource = AppActorCacheResource.Customer(appUserId),
            forceRefresh = forceRefresh,
        )
    }

    fun load(appUserId: String): AppActorCachedValue? {
        return eTagManager.cached(AppActorCacheResource.Customer(appUserId))
    }

    fun save(
        appUserId: String,
        payload: String,
        eTag: String?,
        verified: Boolean,
    ) {
        eTagManager.storeFresh(
            resource = AppActorCacheResource.Customer(appUserId),
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
            resource = AppActorCacheResource.Customer(appUserId),
            rotatedETag = rotatedETag,
        )
    }

    fun clear(appUserId: String) {
        eTagManager.clear(AppActorCacheResource.Customer(appUserId))
    }
}
