package com.appactor.plugin.requests

import com.appactor.android.api.AppActor
import com.appactor.plugin.encoding.CustomerInfoSurrogate
import com.appactor.plugin.infrastructure.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal class LogInRequest private constructor(
    private val appUserId: String,
) : PluginRequest {

    override suspend fun execute(): PluginResult {
        val info = AppActor.logIn(appUserId)
        return PluginResult.encoding(CustomerInfoSurrogate.serializer(), CustomerInfoSurrogate(info))
    }

    companion object : PluginRequestFactory {
        override val method: String = "log_in"
        override fun create(json: String): PluginRequest {
            val p = PluginCoder.json.decodeFromString(Params.serializer(), json)
            return LogInRequest(p.newAppUserId)
        }

        @Serializable
        private data class Params(@SerialName("new_app_user_id") val newAppUserId: String)
    }
}
