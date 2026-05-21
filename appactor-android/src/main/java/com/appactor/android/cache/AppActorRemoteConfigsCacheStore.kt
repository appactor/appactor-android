package com.appactor.android.cache

internal class AppActorRemoteConfigsCacheStore(
    private val eTagManager: AppActorETagManager,
) {

    fun eTag(
        appUserId: String?,
        appVersion: String? = null,
        country: String? = null,
        forceRefresh: Boolean = false,
    ): String? {
        return eTagManager.eTag(resource(appUserId, appVersion, country), forceRefresh = forceRefresh)
            ?: legacyResource(appUserId)?.let { resource ->
                eTagManager.eTag(resource, forceRefresh = forceRefresh)
            }
    }

    fun load(
        appUserId: String?,
        appVersion: String? = null,
        country: String? = null,
    ): AppActorCachedValue? {
        return eTagManager.cached(resource(appUserId, appVersion, country))
            ?: legacyResource(appUserId)?.let(eTagManager::cached)
    }

    fun save(
        appUserId: String?,
        appVersion: String? = null,
        country: String? = null,
        payload: String,
        eTag: String?,
        verified: Boolean,
    ) {
        eTagManager.storeFresh(
            resource = resource(appUserId, appVersion, country),
            payload = payload,
            eTag = eTag,
            verified = verified,
        )
    }

    fun handleNotModified(
        appUserId: String?,
        appVersion: String? = null,
        country: String? = null,
        rotatedETag: String? = null,
    ): AppActorCachedValue? {
        return eTagManager.handleNotModified(resource(appUserId, appVersion, country), rotatedETag = rotatedETag)
            ?: legacyResource(appUserId)?.let { resource ->
                eTagManager.handleNotModified(resource, rotatedETag = rotatedETag)
            }
    }

    fun clear(appUserId: String?) {
        eTagManager.clearPrefix(AppActorCacheResource.remoteConfigsPrefix(appUserId))
    }

    fun clearContext(
        appUserId: String?,
        appVersion: String?,
        country: String?,
    ) {
        eTagManager.clear(resource(appUserId, appVersion, country))
    }

    fun clearAll() {
        eTagManager.clearPrefix("remote_configs_")
    }

    private fun resource(
        appUserId: String?,
        appVersion: String?,
        country: String?,
    ): AppActorCacheResource {
        return AppActorCacheResource.RemoteConfigs(appUserId, appVersion, country)
    }

    private fun legacyResource(appUserId: String?): AppActorCacheResource? {
        return appUserId
            ?.takeIf { it.isNotBlank() }
            ?.let(AppActorCacheResource::LegacyRemoteConfigs)
    }
}
