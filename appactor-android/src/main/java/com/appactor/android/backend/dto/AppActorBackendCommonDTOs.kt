package com.appactor.android.backend.dto

import kotlinx.serialization.Serializable

internal interface AppActorRequestIdCarrier {
    val requestId: String?
}

@Serializable
internal data class AppActorBackendErrorDTO(
    val code: String? = null,
    val message: String? = null,
    val details: String? = null,
    val scope: String? = null,
)

@Serializable
internal data class AppActorBackendErrorEnvelopeDTO(
    override val requestId: String? = null,
    val error: AppActorBackendErrorDTO? = null,
) : AppActorRequestIdCarrier

@Serializable
internal data class AppActorTokenBalanceDTO(
    val renewable: Int = 0,
    val nonRenewable: Int = 0,
    val total: Int = renewable + nonRenewable,
)
