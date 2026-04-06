package com.appactor.plugin.requests

import com.appactor.android.api.AppActor
import com.appactor.plugin.encoding.StorefrontSurrogate
import com.appactor.plugin.infrastructure.*

internal class GetStorefrontRequest : PluginRequest {

    override suspend fun execute(): PluginResult {
        val storefront = AppActor.getStorefront()
        return if (storefront != null) {
            PluginResult.encoding(StorefrontSurrogate.serializer(), StorefrontSurrogate(storefront))
        } else {
            PluginResult.nullPayload
        }
    }

    companion object : PluginRequestFactory {
        override val method: String = "get_storefront"
        override fun create(json: String): PluginRequest = GetStorefrontRequest()
    }
}
