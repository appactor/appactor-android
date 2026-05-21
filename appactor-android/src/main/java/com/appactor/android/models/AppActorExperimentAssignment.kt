package com.appactor.android.models

public data class AppActorExperimentAssignment(
    val experimentId: String,
    val experimentKey: String,
    val variantId: String,
    val variantKey: String,
    val payload: AppActorConfigValue,
    val valueType: AppActorConfigValueType,
    val assignedAt: String,
)
