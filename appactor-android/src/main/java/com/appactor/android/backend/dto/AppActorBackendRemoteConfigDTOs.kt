package com.appactor.android.backend.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
internal data class AppActorRemoteConfigsEnvelopeDTO(
    val data: List<AppActorRemoteConfigItemDTO> = emptyList(),
    override val requestId: String? = null,
) : AppActorRequestIdCarrier

@Serializable
internal data class AppActorRemoteConfigItemDTO(
    val key: String,
    val value: JsonElement,
    val valueType: String? = null,
)
