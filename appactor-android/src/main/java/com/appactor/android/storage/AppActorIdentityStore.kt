package com.appactor.android.storage

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

internal interface AppActorIdentityStore {
    val currentAppUserId: String?
    val installId: String
    val lastRequestId: String?
    val installReferrer: String?

    fun ensureAppUserId(): String
    fun resolveAppUserId(explicitAppUserId: String?): String
    fun clearLegacyIdentityState()
    fun setAppUserId(appUserId: String?)
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
        if (!existing.isNullOrBlank()) {
            return existing
        }
        val generated = "appactor-anon-${UUID.randomUUID()}".lowercase()
        setAppUserId(generated)
        return generated
    }

    override fun resolveAppUserId(explicitAppUserId: String?): String {
        val normalizedExplicit = explicitAppUserId
            ?.takeIf { it.trim().isNotEmpty() }
        if (normalizedExplicit != null) {
            com.appactor.android.models.AppActorValidation.validateAppUserId(normalizedExplicit)
            setAppUserId(normalizedExplicit)
            return normalizedExplicit
        }
        return ensureAppUserId()
    }

    override fun setAppUserId(appUserId: String?) {
        preferences.edit().apply {
            if (appUserId.isNullOrBlank()) remove(KEY_APP_USER_ID) else putString(KEY_APP_USER_ID, appUserId)
        }.apply()
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
        preferences.edit()
            .remove(KEY_APP_USER_ID)
            .remove(KEY_LEGACY_SERVER_USER_ID)
            .remove(KEY_LAST_REQUEST_ID)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "appactor_identity"
        const val KEY_APP_USER_ID = "appactor_billing_app_user_id"
        const val KEY_LEGACY_SERVER_USER_ID = "appactor_billing_server_user_id"
        const val KEY_INSTALL_ID = "appactor_billing_install_id"
        const val KEY_LAST_REQUEST_ID = "appactor_billing_last_request_id"
        const val KEY_INSTALL_REFERRER = "appactor_billing_install_referrer"
    }
}
