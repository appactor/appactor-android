package com.appactor.android.cache

import kotlinx.serialization.Serializable

@Serializable
internal data class AppActorCacheEntry(
    val payload: String,
    val eTag: String? = null,
    val cachedAtMillis: Long,
    val responseVerified: Boolean,
)

internal data class AppActorCachedValue(
    val payload: String,
    val eTag: String?,
    val cachedAtMillis: Long,
)
