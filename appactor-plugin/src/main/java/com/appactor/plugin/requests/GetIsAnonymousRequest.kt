package com.appactor.plugin.requests

import com.appactor.android.api.AppActor
import com.appactor.plugin.infrastructure.*
import kotlinx.serialization.builtins.serializer

internal class GetIsAnonymousRequest : PluginRequest {

    override suspend fun execute(): PluginResult {
        return PluginResult.encoding(Boolean.serializer(), AppActor.isAnonymous)
    }

    companion object : PluginRequestFactory {
        override val method: String = "get_is_anonymous"
        override fun create(json: String): PluginRequest = GetIsAnonymousRequest()
    }
}
