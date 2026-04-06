package com.appactor.plugin.requests

import com.appactor.android.api.AppActor
import com.appactor.plugin.encoding.RemoteConfigsSurrogate
import com.appactor.plugin.infrastructure.*

internal class GetCachedRemoteConfigsRequest : PluginRequest {

    override suspend fun execute(): PluginResult {
        val configs = AppActor.cachedRemoteConfigs
            ?: return PluginResult.nullPayload
        return PluginResult.encoding(RemoteConfigsSurrogate.serializer(), RemoteConfigsSurrogate(configs))
    }

    companion object : PluginRequestFactory {
        override val method: String = "get_cached_remote_configs"
        override fun create(json: String): PluginRequest = GetCachedRemoteConfigsRequest()
    }
}
