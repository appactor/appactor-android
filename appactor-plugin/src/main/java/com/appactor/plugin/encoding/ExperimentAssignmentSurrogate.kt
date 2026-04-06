package com.appactor.plugin.encoding

import com.appactor.android.models.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class ExperimentAssignmentSurrogate(
    @SerialName("experiment_id") val experimentId: String,
    @SerialName("experiment_key") val experimentKey: String,
    @SerialName("variant_id") val variantId: String,
    @SerialName("variant_key") val variantKey: String,
    @Serializable(with = ConfigValueSerializer::class) val payload: AppActorConfigValue,
    @SerialName("value_type") val valueType: String,
    @SerialName("assigned_at") val assignedAt: String,
) {
    constructor(from: AppActorExperimentAssignment) : this(
        experimentId = from.experimentId,
        experimentKey = from.experimentKey,
        variantId = from.variantId,
        variantKey = from.variantKey,
        payload = from.payload,
        valueType = from.valueType.name.lowercase(),
        assignedAt = from.assignedAt,
    )
}
