package com.appactor.android.storage

import android.content.Context
import android.content.SharedPreferences
import com.appactor.android.backend.client.AppActorBackendJson
import com.appactor.android.backend.dto.AppActorAttributionRequestDTO
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

internal interface AppActorAttributeQueueStore {
    fun load(appUserId: String): AppActorQueuedAttributeMutation?
    fun save(appUserId: String, mutation: AppActorQueuedAttributeMutation?)
    fun pendingAppUserIds(): List<String>
    fun loadAttributionSnapshot(appUserId: String): AppActorAttributionRequestDTO?
    fun saveAttributionSnapshot(appUserId: String, attribution: AppActorAttributionRequestDTO?)
    /** Fingerprint of the last successfully delivered automatic device-attribute bucket. */
    fun loadProfileContextFingerprint(appUserId: String): String?
    fun saveProfileContextFingerprint(appUserId: String, fingerprint: String?)
    fun clearAll()
}

@Serializable
internal data class AppActorQueuedAttributeMutation(
    val attributes: Map<String, JsonElement> = emptyMap(),
    val unsetAttributes: List<String> = emptyList(),
    val integrationIdentifiers: Map<String, String> = emptyMap(),
    val unsetIntegrationIdentifiers: List<String> = emptyList(),
    val attribution: AppActorAttributionRequestDTO? = null,
) {
    fun isEmpty(): Boolean =
        attributes.isEmpty() &&
            unsetAttributes.isEmpty() &&
            integrationIdentifiers.isEmpty() &&
            unsetIntegrationIdentifiers.isEmpty() &&
            attribution == null
}

internal class AppActorSharedPrefsAttributeQueueStore(
    context: Context,
    private val preferences: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
) : AppActorAttributeQueueStore {

    override fun load(appUserId: String): AppActorQueuedAttributeMutation? {
        val payload = preferences.getString(key(appUserId), null) ?: return null
        return runCatching {
            AppActorBackendJson.instance.decodeFromString<AppActorQueuedAttributeMutation>(payload)
        }.getOrNull()
    }

    override fun save(appUserId: String, mutation: AppActorQueuedAttributeMutation?) {
        val pendingKey = key(appUserId)
        val isSavingMutation = mutation != null && !mutation.isEmpty()
        preferences.edit().apply {
            if (!isSavingMutation) {
                remove(pendingKey)
            } else {
                putString(pendingKey, AppActorBackendJson.instance.encodeToString(mutation))
            }
            trimToMaxUsers(this, keepAppUserId = appUserId, willHaveRecord = isSavingMutation)
        }.apply()
    }

    override fun pendingAppUserIds(): List<String> {
        return preferences.all.keys
            .filter { it.startsWith(KEY_PREFIX) }
            .map { it.removePrefix(KEY_PREFIX) }
            .sorted()
    }

    override fun loadAttributionSnapshot(appUserId: String): AppActorAttributionRequestDTO? {
        val payload = preferences.getString(snapshotKey(appUserId), null) ?: return null
        return runCatching {
            AppActorBackendJson.instance.decodeFromString<AppActorAttributionRequestDTO>(payload)
        }.getOrNull()
    }

    override fun saveAttributionSnapshot(appUserId: String, attribution: AppActorAttributionRequestDTO?) {
        preferences.edit().apply {
            if (attribution == null) {
                remove(snapshotKey(appUserId))
            } else {
                putString(snapshotKey(appUserId), AppActorBackendJson.instance.encodeToString(attribution))
            }
            trimToMaxUsers(this, keepAppUserId = appUserId, willHaveRecord = attribution != null)
        }.apply()
    }

    override fun loadProfileContextFingerprint(appUserId: String): String? =
        preferences.getString(fingerprintKey(appUserId), null)

    override fun saveProfileContextFingerprint(appUserId: String, fingerprint: String?) {
        preferences.edit().apply {
            if (fingerprint == null) {
                remove(fingerprintKey(appUserId))
            } else {
                putString(fingerprintKey(appUserId), fingerprint)
            }
            trimToMaxUsers(this, keepAppUserId = appUserId, willHaveRecord = fingerprint != null)
        }.apply()
    }

    override fun clearAll() {
        preferences.edit().clear().apply()
    }

    private fun key(appUserId: String): String = "$KEY_PREFIX$appUserId"

    private fun snapshotKey(appUserId: String): String = "$SNAPSHOT_PREFIX$appUserId"

    private fun fingerprintKey(appUserId: String): String = "$FINGERPRINT_PREFIX$appUserId"

    private fun trimToMaxUsers(
        editor: SharedPreferences.Editor,
        keepAppUserId: String,
        willHaveRecord: Boolean,
    ) {
        val userIds = preferences.all.keys
            .mapNotNull(::appUserIdForStoredKey)
            .toMutableSet()
        if (willHaveRecord) {
            userIds.add(keepAppUserId)
        } else {
            userIds.remove(keepAppUserId)
        }
        val overflow = userIds.size - MAX_USERS
        if (overflow <= 0) return
        // Evict users WITHOUT a pending mutation first (fingerprint/snapshot-only), so a
        // lingering fingerprint never pushes out a not-yet-delivered pending bucket.
        val pendingUserIds = preferences.all.keys
            .filter { it.startsWith(KEY_PREFIX) }
            .map { it.removePrefix(KEY_PREFIX) }
            .toSet()
        userIds
            .filterNot { it == keepAppUserId }
            .sortedWith(compareBy({ it in pendingUserIds }, { it }))
            .take(overflow)
            .forEach { appUserId ->
                editor.remove(key(appUserId))
                editor.remove(snapshotKey(appUserId))
                editor.remove(fingerprintKey(appUserId))
            }
    }

    private fun appUserIdForStoredKey(key: String): String? {
        return when {
            key.startsWith(KEY_PREFIX) -> key.removePrefix(KEY_PREFIX)
            key.startsWith(SNAPSHOT_PREFIX) -> key.removePrefix(SNAPSHOT_PREFIX)
            key.startsWith(FINGERPRINT_PREFIX) -> key.removePrefix(FINGERPRINT_PREFIX)
            else -> null
        }
    }

    private companion object {
        const val PREFS_NAME = "appactor_attribute_queue"
        const val KEY_PREFIX = "pending:"
        const val SNAPSHOT_PREFIX = "attribution_snapshot:"
        const val FINGERPRINT_PREFIX = "profile_fp:"
        const val MAX_USERS = 8
    }
}
