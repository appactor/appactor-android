package com.appactor.android.cache

import java.security.MessageDigest

internal sealed class AppActorCacheResource(
    val cacheKey: String,
) {
    data object Offerings : AppActorCacheResource("offerings")
    data object OfflineProductCatalog : AppActorCacheResource("offline_product_catalog")
    data class RemoteConfigs(
        val appUserId: String?,
        val appVersion: String? = null,
        val country: String? = null,
    ) : AppActorCacheResource("${remoteConfigsPrefix(appUserId)}_${contextHash(appVersion, country)}")
    data class LegacyRemoteConfigs(
        val appUserId: String,
    ) : AppActorCacheResource(remoteConfigsPrefix(appUserId))
    data class Experiments(
        val appUserId: String,
    ) : AppActorCacheResource("experiments_${hash(appUserId)}")

    data class Customer(
        val appUserId: String,
    ) : AppActorCacheResource("customer_${hash(appUserId)}")

    companion object {
        fun remoteConfigsPrefix(appUserId: String?): String {
            return appUserId
                ?.takeIf { it.isNotBlank() }
                ?.let { "remote_configs_${hash(it)}" }
                ?: "remote_configs_anon"
        }

        private fun contextHash(appVersion: String?, country: String?): String {
            return hash("v=${appVersion.orEmpty()}|c=${country.orEmpty()}")
        }

        private fun hash(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
            return digest
                .take(8)
                .joinToString(separator = "") { byte -> "%02x".format(byte) }
        }
    }
}
