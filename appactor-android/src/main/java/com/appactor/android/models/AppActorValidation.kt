package com.appactor.android.models

internal object AppActorValidation {

    // The backend's appUserId rules (publicAppUserIdSchema and BLOCKED_USER_IDS). An id it
    // rejects still gets charged on Play, but every receipt for it is refused, so the SDK must
    // refuse the id before any purchase is made.
    private const val MAX_APP_USER_ID_LENGTH = 255
    private val CONTROL_CHARACTERS = Regex("[\\u0000-\\u001F\\u007F]")
    private val BLOCKED_APP_USER_IDS = setOf(
        "null",
        "(null)",
        "anonymous",
        "guest",
        "-1",
        "0",
        "none",
        "nil",
        "nan",
        "no_user",
        "undefined",
        "unknown",
        "unidentified",
        "[]",
        "{}",
        "[object object]",
    )

    fun validateAppUserId(appUserId: String) {
        invalidAppUserIdReason(appUserId)?.let { reason ->
            throw AppActorError.InvalidConfiguration(reason)
        }
    }

    fun isValidAppUserId(appUserId: String): Boolean = invalidAppUserIdReason(appUserId) == null

    /** A "no user" stand-in such as "null", "guest" or "0". */
    fun isPlaceholderAppUserId(appUserId: String): Boolean = appUserId.backendTrim().lowercase() in BLOCKED_APP_USER_IDS

    private fun invalidAppUserIdReason(appUserId: String): String? = when {
        appUserId.backendTrim().isEmpty() -> "appUserId must not be blank."
        appUserId.length > MAX_APP_USER_ID_LENGTH -> "appUserId must be at most $MAX_APP_USER_ID_LENGTH characters."
        '/' in appUserId -> "appUserId must not contain '/'."
        CONTROL_CHARACTERS.containsMatchIn(appUserId) -> "appUserId must not contain control characters."
        isPlaceholderAppUserId(appUserId) ->
            "appUserId '$appUserId' is a placeholder the backend rejects."
        else -> null
    }
}

// JavaScript's trim() also strips U+FEFF, which Kotlin's whitespace test does not.
internal fun String.backendTrim(): String = trim { it.isWhitespace() || it == '\uFEFF' }
