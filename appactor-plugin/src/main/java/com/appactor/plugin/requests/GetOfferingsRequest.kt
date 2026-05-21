package com.appactor.plugin.requests

import com.appactor.android.api.AppActor
import com.appactor.android.models.AppActorOfferingsFetchPolicy
import com.appactor.plugin.encoding.OfferingsSurrogate
import com.appactor.plugin.infrastructure.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal class GetOfferingsRequest private constructor(
    private val fetchPolicy: AppActorOfferingsFetchPolicy?,
) : PluginRequest {

    override suspend fun execute(): PluginResult {
        val offerings = AppActor.offerings(
            fetchPolicy = fetchPolicy ?: AppActorOfferingsFetchPolicy.FreshIfStale,
        )
        return PluginResult.encoding(OfferingsSurrogate.serializer(), OfferingsSurrogate(offerings))
    }

    companion object : PluginRequestFactory {
        override val method: String = "get_offerings"

        override fun create(json: String): PluginRequest {
            val params = if (json.isBlank()) {
                Params()
            } else {
                PluginCoder.json.decodeFromString(Params.serializer(), json)
            }
            return GetOfferingsRequest(
                fetchPolicy = AppActorOfferingsFetchPolicy.fromWireValue(
                    params.fetchPolicy ?: params.fetchPolicyAlias
                )
            )
        }

        @Serializable
        private data class Params(
            @SerialName("fetch_policy") val fetchPolicy: String? = null,
            @SerialName("fetchPolicy") val fetchPolicyAlias: String? = null,
        )
    }
}
