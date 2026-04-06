package com.appactor.plugin.requests

import com.appactor.android.api.AppActor
import com.appactor.plugin.infrastructure.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal class LogOutRequest : PluginRequest {

    override suspend fun execute(): PluginResult {
        val isAnonymous = AppActor.logOut()
        return PluginResult.encoding(ValueResponse.serializer(), ValueResponse(isAnonymous))
    }

    companion object : PluginRequestFactory {
        override val method: String = "log_out"
        override fun create(json: String): PluginRequest = LogOutRequest()
    }
}

@Serializable
private data class ValueResponse(val value: Boolean)
