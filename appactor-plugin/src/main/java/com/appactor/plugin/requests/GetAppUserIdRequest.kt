package com.appactor.plugin.requests

import com.appactor.android.api.AppActor
import com.appactor.plugin.infrastructure.*
import kotlinx.serialization.builtins.serializer

internal class GetAppUserIdRequest : PluginRequest {

    override suspend fun execute(): PluginResult {
        val userId = AppActor.appUserId
        return if (userId != null) {
            PluginResult.encoding(String.serializer(), userId)
        } else {
            PluginResult.nullPayload
        }
    }

    companion object : PluginRequestFactory {
        override val method: String = "get_app_user_id"
        override fun create(json: String): PluginRequest = GetAppUserIdRequest()
    }
}
