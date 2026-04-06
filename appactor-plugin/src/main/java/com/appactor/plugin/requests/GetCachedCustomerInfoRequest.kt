package com.appactor.plugin.requests

import com.appactor.android.api.AppActor
import com.appactor.plugin.encoding.CustomerInfoSurrogate
import com.appactor.plugin.infrastructure.*

internal class GetCachedCustomerInfoRequest : PluginRequest {

    override suspend fun execute(): PluginResult {
        val info = AppActor.customerInfo
        return PluginResult.encoding(CustomerInfoSurrogate.serializer(), CustomerInfoSurrogate(info))
    }

    companion object : PluginRequestFactory {
        override val method: String = "get_cached_customer_info"
        override fun create(json: String): PluginRequest = GetCachedCustomerInfoRequest()
    }
}
