package com.appactor.android.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

class AppActorRetryPolicyTests {

    @Test
    fun `retry policy prefers server retry after when it exceeds backoff`() {
        val next = AppActorRetryPolicy.nextRetryAtMillis(
            nowMillis = 1_000L,
            retryCount = 1,
            retryAfterSeconds = 10.0,
        )

        assertEquals(11_000L, next)
    }

    @Test
    fun `retry policy falls back to exponential backoff when server delay is smaller`() {
        val next = AppActorRetryPolicy.nextRetryAtMillis(
            nowMillis = 1_000L,
            retryCount = 3,
            retryAfterSeconds = 1.0,
        )

        assertEquals(9_000L, next)
    }

    @Test
    fun `retry policy caps exponential backoff at one hour`() {
        val next = AppActorRetryPolicy.nextRetryAtMillis(
            nowMillis = 1_000L,
            retryCount = 20,
            retryAfterSeconds = null,
        )

        assertEquals(3_601_000L, next)
    }
}
