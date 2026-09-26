package com.appactor.android.models

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppActorValidationTests {

    @Test
    fun `app user ids the backend accepts pass`() {
        listOf(
            "user_android_123",
            "auth0|abc123",
            " user_android_123 ",
            "nullable",
            "a".repeat(255),
        ).forEach { appUserId ->
            assertTrue(appUserId, AppActorValidation.isValidAppUserId(appUserId))
        }
    }

    @Test
    fun `app user ids the backend rejects fail`() {
        listOf(
            "",
            "a".repeat(256),
            "null",
            "NULL",
            " guest ",
            "0",
            "-1",
            "undefined",
            "[object Object]",
            "user/123",
            "user\n123",
            "user\u007F",
        ).forEach { appUserId ->
            assertFalse(appUserId, AppActorValidation.isValidAppUserId(appUserId))
        }
    }

    @Test
    fun `placeholders are told apart from malformed ids`() {
        assertTrue(AppActorValidation.isPlaceholderAppUserId(" Guest "))
        assertFalse(AppActorValidation.isPlaceholderAppUserId("user/123"))
    }

    @Test
    fun `validate throws invalid configuration`() {
        val error = runCatching { AppActorValidation.validateAppUserId("guest") }.exceptionOrNull()

        assertTrue(error is AppActorError.InvalidConfiguration)
    }
}
