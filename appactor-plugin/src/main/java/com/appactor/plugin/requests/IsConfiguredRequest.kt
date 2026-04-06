package com.appactor.plugin.requests

import com.appactor.android.api.AppActorBridge
import com.appactor.plugin.infrastructure.*
import kotlinx.serialization.builtins.serializer

internal class IsConfiguredRequest : PluginRequest {

    override suspend fun execute(): PluginResult {
        return PluginResult.encoding(Boolean.serializer(), AppActorBridge.isConfigured())
    }

    companion object : PluginRequestFactory {
        override val method: String = "is_configured"
        override fun create(json: String): PluginRequest = IsConfiguredRequest()
    }
}
