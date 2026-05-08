package com.appactor.android.pipeline

import kotlin.math.min
import kotlin.math.pow

internal object AppActorRetryPolicy {
    private const val MAX_RETRY_DELAY_MILLIS: Long = 60 * 60 * 1_000L

    fun nextRetryAtMillis(
        nowMillis: Long,
        retryCount: Int,
        retryAfterSeconds: Double?,
    ): Long {
        val serverDelayMillis = retryAfterSeconds
            ?.takeIf { it > 0 }
            ?.times(1_000)
            ?.toLong()
        val backoffMillis = min(
            (2.0.pow(retryCount.toDouble()) * 1_000.0).toLong(),
            MAX_RETRY_DELAY_MILLIS,
        )
        val resolvedDelay = maxOf(serverDelayMillis ?: 0L, backoffMillis)
        return nowMillis + resolvedDelay
    }
}
