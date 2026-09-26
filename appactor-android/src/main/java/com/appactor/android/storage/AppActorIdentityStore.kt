package com.appactor.android.storage

import android.content.Context
import android.content.SharedPreferences
import com.appactor.android.internal.logging.AppActorLogger
import com.appactor.android.models.AppActorValidation
import java.util.UUID

private const val ANONYMOUS_APP_USER_ID_PREFIX: String = "appactor-anon-"

internal fun isAnonymousAppUserId(appUserId: String): Boolean = appUserId.startsWith(ANONYMOUS_APP_USER_ID_PREFIX)

internal interface AppActorIdentityStore {
    val currentAppUserId: String?
    val installId: String
    val lastRequestId: String?
    val installReferrer: String?

    fun ensureAppUserId(): String
    fun resolveAppUserId(explicitAppUserId: String?): String
    fun clearLegacyIdentityState()
    fun setAppUserId(appUserId: String?)
    /** Sets [appUserId] only while [expected] is still current, atomically with the other identity writes. */
    fun replaceAppUserId(expected: String, appUserId: String)
    fun setLastRequestId(requestId: String?)
    fun setInstallReferrer(referrer: String?)
    fun clearIdentity()
}

internal class AppActorSharedPrefsIdentityStore(
    context: Context,
    private val preferences: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
) : AppActorIdentityStore {

    override val currentAppUserId: String?
        get() = preferences.getString(KEY_APP_USER_ID, null)

    override val installId: String
        get() {
            val existing = preferences.getString(KEY_INSTALL_ID, null)
            if (!existing.isNullOrBlank()) {
                return existing
            }
            val generated = "appactor-install-${UUID.randomUUID()}".lowercase()
            preferences.edit().putString(KEY_INSTALL_ID, generated).apply()
            return generated
        }

    override val lastRequestId: String?
        get() = preferences.getString(KEY_LAST_REQUEST_ID, null)

    override val installReferrer: String?
        get() = preferences.getString(KEY_INSTALL_REFERRER, null)

    override fun ensureAppUserId(): String {
        val existing = currentAppUserId
        if (existing != null && AppActorValidation.isValidAppUserId(existing)) {
            return existing
        }
        // Older versions stored ids the backend rejects (e.g. "null"). Every backend call for
        // such an id fails, so it holds no server state worth keeping.
        if (!existing.isNullOrBlank()) {
            AppActorLogger.warn("[Identity] Replacing a stored appUserId the backend rejects with a new anonymous id.")
        }
        return newAnonymousAppUserId()
    }

    override fun resolveAppUserId(explicitAppUserId: String?): String {
        val normalizedExplicit = explicitAppUserId
            ?.takeIf { it.trim().isNotEmpty() }
        if (normalizedExplicit != null && AppActorValidation.isPlaceholderAppUserId(normalizedExplicit)) {
            // A placeholder such as "null" (e.g. `user?.id.toString()` while signed out) means
            // nobody is signed in: keep an anonymous id, never the last signed-in user's.
            AppActorLogger.warn("[Identity] appUserId '$normalizedExplicit' is a placeholder the backend rejects; using an anonymous id.")
            return currentAppUserId?.takeIf(::isAnonymousAppUserId) ?: newAnonymousAppUserId()
        }
        if (normalizedExplicit != null) {
            AppActorValidation.validateAppUserId(normalizedExplicit)
            setAppUserId(normalizedExplicit)
            return normalizedExplicit
        }
        return ensureAppUserId()
    }

    private fun newAnonymousAppUserId(): String {
        val generated = "$ANONYMOUS_APP_USER_ID_PREFIX${UUID.randomUUID()}".lowercase()
        setAppUserId(generated)
        return generated
    }

    override fun setAppUserId(appUserId: String?) {
        synchronized(WRITE_LOCK) {
            preferences.edit().apply {
                if (appUserId.isNullOrBlank()) remove(KEY_APP_USER_ID) else putString(KEY_APP_USER_ID, appUserId)
            }.apply()
        }
    }

    override fun replaceAppUserId(expected: String, appUserId: String) {
        synchronized(WRITE_LOCK) {
            if (currentAppUserId == expected) setAppUserId(appUserId)
        }
    }

    override fun setLastRequestId(requestId: String?) {
        preferences.edit().apply {
            if (requestId.isNullOrBlank()) remove(KEY_LAST_REQUEST_ID) else putString(KEY_LAST_REQUEST_ID, requestId)
        }.apply()
    }

    override fun setInstallReferrer(referrer: String?) {
        preferences.edit().apply {
            if (referrer.isNullOrBlank()) remove(KEY_INSTALL_REFERRER) else putString(KEY_INSTALL_REFERRER, referrer)
        }.apply()
    }

    override fun clearLegacyIdentityState() {
        preferences.edit()
            .remove(KEY_LEGACY_SERVER_USER_ID)
            .apply()
    }

    override fun clearIdentity() {
        synchronized(WRITE_LOCK) {
            preferences.edit()
                .remove(KEY_APP_USER_ID)
                .remove(KEY_LEGACY_SERVER_USER_ID)
                .remove(KEY_LAST_REQUEST_ID)
                .apply()
        }
    }

    private companion object {
        // Process-wide: every runtime's store writes the same preferences.
        val WRITE_LOCK = Any()
        const val PREFS_NAME = "appactor_identity"
        const val KEY_APP_USER_ID = "appactor_billing_app_user_id"
        const val KEY_LEGACY_SERVER_USER_ID = "appactor_billing_server_user_id"
        const val KEY_INSTALL_ID = "appactor_billing_install_id"
        const val KEY_LAST_REQUEST_ID = "appactor_billing_last_request_id"
        const val KEY_INSTALL_REFERRER = "appactor_billing_install_referrer"
    }
}
