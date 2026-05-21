package com.appactor.plugin.requests

import android.util.Base64
import com.appactor.android.api.AppActor
import com.appactor.plugin.infrastructure.PluginCoder
import com.appactor.plugin.infrastructure.PluginRequest
import com.appactor.plugin.infrastructure.PluginRequestFactory
import com.appactor.plugin.infrastructure.PluginResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class SetFallbackOfferingsParams(
    @SerialName("json_data") val jsonData: String,
)

internal class SetFallbackOfferingsRequest(
    private val params: SetFallbackOfferingsParams,
) : PluginRequest {

    override suspend fun execute(): PluginResult {
        val bytes = Base64.decode(params.jsonData, Base64.DEFAULT)
        AppActor.setFallbackOfferings(bytes)
        return PluginResult.successVoid
    }

    companion object : PluginRequestFactory {
        override val method: String = "set_fallback_offerings"
        override fun create(json: String): PluginRequest =
            SetFallbackOfferingsRequest(PluginCoder.json.decodeFromString(SetFallbackOfferingsParams.serializer(), json))
    }
}
