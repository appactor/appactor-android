package com.appactor.plugin.requests

import com.appactor.android.api.AppActor
import com.appactor.plugin.encoding.CustomerInfoSurrogate
import com.appactor.plugin.infrastructure.*

internal class GetCustomerInfoRequest : PluginRequest {

    override suspend fun execute(): PluginResult {
        val info = AppActor.getCustomerInfo()
        return PluginResult.encoding(CustomerInfoSurrogate.serializer(), CustomerInfoSurrogate(info))
    }

    companion object : PluginRequestFactory {
        override val method: String = "get_customer_info"
        override fun create(json: String): PluginRequest = GetCustomerInfoRequest()
    }
}
