package com.appactor.android.backend.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
internal data class AppActorExperimentAssignmentEnvelopeDTO(
    val data: AppActorExperimentAssignmentResponseDTO,
    override val requestId: String? = null,
) : AppActorRequestIdCarrier

@Serializable
internal data class AppActorExperimentAssignmentResponseDTO(
    val inExperiment: Boolean = false,
    val reason: String? = null,
    val experiment: AppActorExperimentDTO? = null,
    val variant: AppActorExperimentVariantDTO? = null,
    val assignedAt: String? = null,
)

@Serializable
internal data class AppActorExperimentDTO(
    val id: String,
    val key: String,
)

@Serializable
internal data class AppActorExperimentVariantDTO(
    val id: String,
    val key: String,
    val valueType: String? = null,
    val payload: JsonElement,
)
