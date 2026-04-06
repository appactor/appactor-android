package com.appactor.plugin.requests

import com.appactor.android.api.AppActor
import com.appactor.plugin.infrastructure.*

internal class EnableInstallReferrerRequest : PluginRequest {

    override suspend fun execute(): PluginResult {
        AppActor.enableInstallReferrer()
        return PluginResult.successVoid
    }

    companion object : PluginRequestFactory {
        override val method: String = "enable_install_referrer"
        override fun create(json: String): PluginRequest = EnableInstallReferrerRequest()
    }
}
