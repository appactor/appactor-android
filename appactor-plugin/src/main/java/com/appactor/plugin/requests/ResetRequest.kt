package com.appactor.plugin.requests

import com.appactor.android.api.AppActor
import com.appactor.plugin.infrastructure.*

internal class ResetRequest : PluginRequest {

    override suspend fun execute(): PluginResult {
        AppActor.reset()
        return PluginResult.successVoid
    }

    companion object : PluginRequestFactory {
        override val method: String = "reset"
        override fun create(json: String): PluginRequest = ResetRequest()
    }
}
