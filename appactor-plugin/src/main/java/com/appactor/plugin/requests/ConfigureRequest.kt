package com.appactor.plugin.requests

import com.appactor.android.api.AppActor
import com.appactor.android.models.AppActorOptions
import com.appactor.android.models.AppActorPlatformInfo
import com.appactor.plugin.AppActorPlugin
import com.appactor.plugin.infrastructure.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

internal class ConfigureRequest private constructor(
    private val apiKey: String,
    private val appUserId: String?,
    private val options: OptionsPayload?,
    private val logLevel: String?,
    private val platformFlavor: String?,
    private val platformVersion: String?,
    private val platformInfo: PlatformInfo?,
) : PluginRequest {

    override suspend fun execute(): PluginResult {
        val context = AppActorPlugin.applicationContext
            ?: return PluginResult.Error(PluginError(PluginError.MISSING_CONTEXT, "Context not set. Call AppActorPlugin.setContext() first."))

        val parsedLogLevel = (options?.logLevel ?: logLevel)?.let { PluginCoder.parseLogLevel(it) }
        val resolvedPlatformInfo = resolvePlatformInfo()
        val options = AppActorOptions(
            logLevel = parsedLogLevel,
            platformInfo = resolvedPlatformInfo,
        )
        AppActor.configure(context, apiKey, appUserId, options)
        return PluginResult.successVoid
    }

    internal fun resolvePlatformInfo(): AppActorPlatformInfo? {
        val resolvedFlavor = listOf(options?.platformInfo?.flavor, platformInfo?.flavor, platformFlavor)
            .firstOrNull { !it.isNullOrBlank() }
        val resolvedVersion = options?.platformInfo?.version ?: platformInfo?.version ?: platformVersion

        return when {
            !resolvedFlavor.isNullOrBlank() -> AppActorPlatformInfo(
                flavor = resolvedFlavor,
                version = resolvedVersion,
            )
            !resolvedVersion.isNullOrBlank() -> AppActorPlatformInfo(
                flavor = "flutter",
                version = resolvedVersion,
            )
            else -> null
        }
    }

    companion object : PluginRequestFactory {
        override val method: String = "configure"
        override fun create(json: String): PluginRequest {
            val payload = PluginCoder.json.parseToJsonElement(json).jsonObject
            val optionsObject = payload.objectOrNull("options")
            val nestedPlatformInfo = parsePlatformInfo(optionsObject?.objectOrNull("platform_info", "platformInfo"))
            val legacyPlatformInfo = parsePlatformInfo(payload.objectOrNull("platform_info", "platformInfo"))
            return ConfigureRequest(
                apiKey = payload.requiredString("api_key", "apiKey"),
                appUserId = payload.stringOrNull("app_user_id", "appUserId"),
                options = OptionsPayload(
                    logLevel = optionsObject?.stringOrNull("log_level", "logLevel"),
                    platformInfo = nestedPlatformInfo,
                ).takeIf { it.logLevel != null || it.platformInfo != null },
                logLevel = payload.stringOrNull("log_level", "logLevel"),
                platformFlavor = payload.stringOrNull("platform_flavor", "platformFlavor"),
                platformVersion = payload.stringOrNull("platform_version", "platformVersion"),
                platformInfo = legacyPlatformInfo,
            )
        }

        private fun JsonObject.requiredString(vararg keys: String): String {
            return stringOrNull(*keys)
                ?: throw IllegalArgumentException("Missing required field: ${keys.first()}")
        }

        private fun JsonObject.stringOrNull(vararg keys: String): String? {
            return keys.firstNotNullOfOrNull { key ->
                (this[key] as? JsonPrimitive)?.contentOrNull
            }
        }

        private fun JsonObject.objectOrNull(vararg keys: String): JsonObject? {
            return keys.firstNotNullOfOrNull { key ->
                this[key] as? JsonObject
            }
        }

        private fun parsePlatformInfo(objectValue: JsonObject?): PlatformInfo? {
            if (objectValue == null) return null
            val flavor = objectValue.stringOrNull("flavor")?.trim()?.takeIf { it.isNotEmpty() }
            val version = objectValue.stringOrNull("version")?.trim()?.takeIf { it.isNotEmpty() }
            return if (flavor != null || version != null) PlatformInfo(flavor, version) else null
        }
    }

    private data class PlatformInfo(
        val flavor: String? = null,
        val version: String? = null,
    )

    private data class OptionsPayload(
        val logLevel: String? = null,
        val platformInfo: PlatformInfo? = null,
    )
}
