package com.appactor.plugin.requests

import com.appactor.android.api.AppActor
import com.appactor.android.models.AppActorLogLevel
import com.appactor.plugin.infrastructure.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal class SetLogLevelRequest private constructor(
    private val logLevel: String,
) : PluginRequest {

    override suspend fun execute(): PluginResult {
        val parsed = PluginCoder.parseLogLevel(logLevel)
        if (parsed != null) {
            AppActor.setLogLevel(parsed)
        }
        return PluginResult.successVoid
    }

    companion object : PluginRequestFactory {
        override val method: String = "set_log_level"
        override fun create(json: String): PluginRequest {
            val p = PluginCoder.json.decodeFromString(Params.serializer(), json)
            return SetLogLevelRequest(p.logLevel)
        }

        @Serializable
        private data class Params(@SerialName("log_level") val logLevel: String)
    }
}
