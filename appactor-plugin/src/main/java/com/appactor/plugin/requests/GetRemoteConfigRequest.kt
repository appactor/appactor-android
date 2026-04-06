package com.appactor.plugin.requests

import com.appactor.android.api.AppActor
import com.appactor.plugin.encoding.RemoteConfigItemSurrogate
import com.appactor.plugin.infrastructure.*
import kotlinx.serialization.Serializable

internal class GetRemoteConfigRequest private constructor(
    private val key: String,
) : PluginRequest {

    override suspend fun execute(): PluginResult {
        val configs = AppActor.cachedRemoteConfigs
            ?: return PluginResult.nullPayload
        val item = configs.items.firstOrNull { it.key == key }
            ?: return PluginResult.nullPayload
        return PluginResult.encoding(RemoteConfigItemSurrogate.serializer(), RemoteConfigItemSurrogate(item))
    }

    companion object : PluginRequestFactory {
        override val method: String = "get_remote_config"
        override fun create(json: String): PluginRequest {
            val p = PluginCoder.json.decodeFromString(Params.serializer(), json)
            return GetRemoteConfigRequest(p.key)
        }

        @Serializable
        private data class Params(val key: String)
    }
}
