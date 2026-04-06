package com.appactor.android.cache

import java.security.MessageDigest

internal sealed class AppActorCacheResource(
    val cacheKey: String,
) {
    data object Offerings : AppActorCacheResource("offerings")
    data class RemoteConfigs(
        val appUserId: String,
    ) : AppActorCacheResource("remote_configs_${hash(appUserId)}")
    data class Experiments(
        val appUserId: String,
    ) : AppActorCacheResource("experiments_${hash(appUserId)}")

    data class Customer(
        val appUserId: String,
    ) : AppActorCacheResource("customer_${hash(appUserId)}")

    companion object {
        private fun hash(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
            return digest
                .take(8)
                .joinToString(separator = "") { byte -> "%02x".format(byte) }
        }
    }
}
