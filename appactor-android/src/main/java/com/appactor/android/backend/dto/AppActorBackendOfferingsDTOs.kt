package com.appactor.android.backend.dto

import com.appactor.android.backend.client.AppActorBackendJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

@Serializable
internal data class AppActorOfferingsEnvelopeDTO(
    val data: AppActorOfferingsPayloadDTO,
    override val requestId: String? = null,
) : AppActorRequestIdCarrier

/**
 * Reads a fallback offerings file: a saved GET /v1/payment/offerings body or its data object, the
 * two shapes iOS takes. As on iOS, one without an offerings array is refused, so a file holding
 * some other JSON object isn't taken for an app without offerings.
 */
internal fun decodeFallbackOfferings(json: String): AppActorOfferingsEnvelopeDTO {
    val root = AppActorBackendJson.instance.parseToJsonElement(json).jsonObject
    val envelope = if ("data" in root) root else JsonObject(mapOf("data" to root))
    require("offerings" in envelope.getValue("data").jsonObject) { "Fallback offerings JSON has no offerings." }
    return AppActorBackendJson.instance.decodeFromJsonElement(AppActorOfferingsEnvelopeDTO.serializer(), envelope)
}

@Serializable
internal data class AppActorOfferingsPayloadDTO(
    val currentOffering: AppActorOfferingDTO? = null,
    val offerings: List<AppActorOfferingDTO> = emptyList(),
    val productEntitlements: Map<String, List<String>> = emptyMap(),
)

@Serializable
internal data class AppActorOfferingDTO(
    val id: String,
    val lookupKey: String? = null,
    val displayName: String? = null,
    val isCurrent: Boolean = false,
    val metadata: Map<String, JsonElement> = emptyMap(),
    val packages: List<AppActorPackageDTO> = emptyList(),
)

@Serializable
internal data class AppActorPackageDTO(
    val id: String,
    val packageType: String? = null,
    val displayName: String? = null,
    val position: Int? = null,
    val isActive: Boolean? = null,
    val metadata: Map<String, JsonElement> = emptyMap(),
    val tokenAmount: Int? = null,
    val products: List<AppActorProductReferenceDTO> = emptyList(),
)

@Serializable
internal data class AppActorProductReferenceDTO(
    val id: String? = null,
    val store: String? = null,
    val productId: String,
    val storeProductId: String? = null,
    val productType: String? = null,
    val basePlanId: String? = null,
    val offerId: String? = null,
    val displayName: String? = null,
)
