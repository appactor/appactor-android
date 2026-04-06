package com.appactor.plugin.requests

import com.appactor.android.api.AppActor
import com.appactor.plugin.encoding.OfferingsSurrogate
import com.appactor.plugin.infrastructure.*

internal class GetOfferingsRequest : PluginRequest {

    override suspend fun execute(): PluginResult {
        val offerings = AppActor.offerings()
        return PluginResult.encoding(OfferingsSurrogate.serializer(), OfferingsSurrogate(offerings))
    }

    companion object : PluginRequestFactory {
        override val method: String = "get_offerings"
        override fun create(json: String): PluginRequest = GetOfferingsRequest()
    }
}
