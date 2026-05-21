package com.appactor.plugin.requests

import com.appactor.android.api.AppActor
import com.appactor.plugin.encoding.OfferingsSurrogate
import com.appactor.plugin.infrastructure.*

internal class GetCachedOfferingsRequest : PluginRequest {

    override suspend fun execute(): PluginResult {
        val offerings = AppActor.cachedOfferings
            ?: return PluginResult.nullPayload
        return PluginResult.encoding(OfferingsSurrogate.serializer(), OfferingsSurrogate(offerings))
    }

    companion object : PluginRequestFactory {
        override val method: String = "get_cached_offerings"
        override fun create(json: String): PluginRequest = GetCachedOfferingsRequest()
    }
}
