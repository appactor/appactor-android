package com.appactor.android.cache

import com.appactor.android.models.AppActorVerificationResult
import kotlinx.serialization.Serializable

@Serializable
internal data class AppActorCacheEntry(
    val payload: String,
    val eTag: String? = null,
    val cachedAtMillis: Long,
    // Legacy field — kept only for deserializing cache entries written before verificationStatus existed.
    val responseVerified: Boolean,
    val verificationStatus: AppActorVerificationResult? = null,
) {
    // Legacy entries lack verificationStatus: treat responseVerified=false as Failed (untrusted)
    // rather than NotRequested, because those entries were written when verification was enabled
    // but the response couldn't be verified.
    val resolvedStatus: AppActorVerificationResult
        get() = verificationStatus
            ?: if (responseVerified) AppActorVerificationResult.Verified
            else AppActorVerificationResult.Failed
}

internal data class AppActorCachedValue(
    val payload: String,
    val eTag: String?,
    val cachedAtMillis: Long,
    val verification: AppActorVerificationResult = AppActorVerificationResult.NotRequested,
)
