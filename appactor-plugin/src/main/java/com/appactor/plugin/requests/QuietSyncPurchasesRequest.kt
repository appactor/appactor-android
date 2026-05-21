package com.appactor.plugin.requests

import com.appactor.android.api.AppActor
import com.appactor.plugin.encoding.CustomerInfoSurrogate
import com.appactor.plugin.infrastructure.*

internal class QuietSyncPurchasesRequest : PluginRequest {

    override suspend fun execute(): PluginResult {
        val info = AppActor.syncPurchases()
        return PluginResult.encoding(CustomerInfoSurrogate.serializer(), CustomerInfoSurrogate(info))
    }

    companion object : PluginRequestFactory {
        override val method: String = "quiet_sync_purchases"
        override fun create(json: String): PluginRequest = QuietSyncPurchasesRequest()
    }
}
