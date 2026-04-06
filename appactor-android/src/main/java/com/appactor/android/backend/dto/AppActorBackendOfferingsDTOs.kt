package com.appactor.android.backend.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
internal data class AppActorOfferingsEnvelopeDTO(
    val data: AppActorOfferingsPayloadDTO,
    override val requestId: String? = null,
) : AppActorRequestIdCarrier

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
