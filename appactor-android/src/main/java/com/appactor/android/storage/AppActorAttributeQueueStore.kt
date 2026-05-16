package com.appactor.android.storage

import android.content.Context
import android.content.SharedPreferences
import com.appactor.android.backend.client.AppActorBackendJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

internal interface AppActorAttributeQueueStore {
    fun load(appUserId: String): AppActorQueuedAttributeMutation?
    fun save(appUserId: String, mutation: AppActorQueuedAttributeMutation?)
    fun clearAll()
}

@Serializable
internal data class AppActorQueuedAttributeMutation(
    val attributes: Map<String, JsonElement> = emptyMap(),
    val unsetAttributes: List<String> = emptyList(),
    val integrationIdentifiers: Map<String, String> = emptyMap(),
) {
    fun isEmpty(): Boolean =
        attributes.isEmpty() && unsetAttributes.isEmpty() && integrationIdentifiers.isEmpty()
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
            trimToMaxUsers(this, keepKey = pendingKey, isSavingMutation = isSavingMutation)
        }.apply()
    }

    override fun clearAll() {
        preferences.edit().clear().apply()
    }

    private fun key(appUserId: String): String = "$KEY_PREFIX$appUserId"

    private fun trimToMaxUsers(
        editor: SharedPreferences.Editor,
        keepKey: String,
        isSavingMutation: Boolean,
    ) {
        val keys = preferences.all.keys
            .filter { it.startsWith(KEY_PREFIX) }
            .filterNot { it == keepKey }
            .sorted()
        val projectedSize = keys.size + if (isSavingMutation) 1 else 0
        val overflow = projectedSize - MAX_USERS
        if (overflow <= 0) return
        keys.take(overflow).forEach(editor::remove)
    }

    private companion object {
        const val PREFS_NAME = "appactor_attribute_queue"
        const val KEY_PREFIX = "pending:"
        const val MAX_USERS = 8
    }
}
