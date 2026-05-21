package com.appactor.plugin.requests

import com.appactor.android.api.AppActor
import com.appactor.plugin.infrastructure.*
import kotlinx.serialization.Serializable

internal class ActiveEntitlementsOfflineRequest : PluginRequest {

    override suspend fun execute(): PluginResult {
        val keys = AppActor.activeEntitlementKeysOffline()
        return PluginResult.encoding(KeysResponse.serializer(), KeysResponse(keys.toList()))
    }

    companion object : PluginRequestFactory {
        override val method: String = "active_entitlement_keys_offline"
        override fun create(json: String): PluginRequest = ActiveEntitlementsOfflineRequest()
    }
}

@Serializable
private data class KeysResponse(val keys: List<String>)
