package com.appactor.plugin.requests

import com.appactor.android.api.AppActor
import com.appactor.plugin.encoding.StoreCapabilitySerializer
import com.appactor.plugin.infrastructure.*
import kotlinx.serialization.builtins.SetSerializer

internal class GetStoreCapabilitiesRequest : PluginRequest {

    override suspend fun execute(): PluginResult {
        val capabilities = AppActor.getStoreCapabilities()
        return PluginResult.encoding(capabilitiesSerializer, capabilities)
    }

    companion object : PluginRequestFactory {
        override val method: String = "get_store_capabilities"
        override fun create(json: String): PluginRequest = GetStoreCapabilitiesRequest()
        private val capabilitiesSerializer = SetSerializer(StoreCapabilitySerializer)
    }
}
