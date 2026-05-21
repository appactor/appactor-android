package com.appactor.plugin.requests

import com.appactor.android.api.AppActor
import com.appactor.plugin.infrastructure.*
import kotlinx.serialization.builtins.serializer

internal class GetSdkVersionRequest : PluginRequest {

    override suspend fun execute(): PluginResult {
        return PluginResult.encoding(String.serializer(), AppActor.sdkVersion)
    }

    companion object : PluginRequestFactory {
        override val method: String = "get_sdk_version"
        override fun create(json: String): PluginRequest = GetSdkVersionRequest()
    }
}
