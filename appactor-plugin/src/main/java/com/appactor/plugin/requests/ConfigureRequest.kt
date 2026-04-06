package com.appactor.plugin.requests

import com.appactor.android.api.AppActor
import com.appactor.android.models.AppActorLogLevel
import com.appactor.android.models.AppActorOptions
import com.appactor.android.models.AppActorPlatformInfo
import com.appactor.plugin.AppActorPlugin
import com.appactor.plugin.infrastructure.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal class ConfigureRequest private constructor(
    private val apiKey: String,
    private val logLevel: String?,
    private val platformFlavor: String?,
    private val platformVersion: String?,
) : PluginRequest {

    override suspend fun execute(): PluginResult {
        val context = AppActorPlugin.applicationContext
            ?: return PluginResult.Error(PluginError(PluginError.MISSING_CONTEXT, "Context not set. Call AppActorPlugin.setContext() first."))

        val parsedLogLevel = logLevel?.let { PluginCoder.parseLogLevel(it) }
        val options = AppActorOptions(
            logLevel = parsedLogLevel,
            platformInfo = AppActorPlatformInfo(
                flavor = platformFlavor ?: "flutter",
                version = platformVersion,
            ),
        )
        AppActor.configure(context, apiKey, options)
        return PluginResult.successVoid
    }

    companion object : PluginRequestFactory {
        override val method: String = "configure"
        override fun create(json: String): PluginRequest {
            val p = PluginCoder.json.decodeFromString(Params.serializer(), json)
            return ConfigureRequest(
                p.apiKey, p.logLevel,
                p.platformFlavor, p.platformVersion,
            )
        }

        @Serializable
        private data class Params(
            @SerialName("api_key") val apiKey: String,
            @SerialName("log_level") val logLevel: String? = null,
            @SerialName("platform_flavor") val platformFlavor: String? = null,
            @SerialName("platform_version") val platformVersion: String? = null,
        )
    }
}
