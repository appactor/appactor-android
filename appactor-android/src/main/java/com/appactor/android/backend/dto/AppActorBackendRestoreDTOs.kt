package com.appactor.android.backend.dto

import kotlinx.serialization.Serializable

@Serializable
internal data class AppActorGoogleRestoreRequestDTO(
    val appUserId: String,
    val obfuscatedAccountId: String? = null,
    val obfuscatedProfileId: String? = null,
    val source: String? = null,
    val observedAt: String? = null,
    val purchases: List<AppActorGoogleRestorePurchaseDTO>,
)

@Serializable
internal data class AppActorGoogleRestorePurchaseDTO(
    val productId: String,
    val productType: String,
    val purchaseToken: String,
    val purchaseTime: String,
    val orderId: String? = null,
    val basePlanId: String? = null,
    val offerId: String? = null,
    val isAutoRenewing: Boolean? = null,
)

@Serializable
internal data class AppActorGoogleRestoreResponseDTO(
    val appUserId: String? = null,
    val customer: AppActorCustomerDTO,
    val restoredCount: Int = 0,
    val transferred: Boolean = false,
    val results: List<AppActorGoogleBatchResultDTO> = emptyList(),
    override val requestId: String? = null,
) : AppActorRequestIdCarrier

internal data class AppActorGoogleRestoreResult(
    val customerInfo: com.appactor.android.models.AppActorCustomerInfo,
    val restoredCount: Int,
    val transferred: Boolean,
    val requestId: String?,
)

@Serializable
internal data class AppActorGoogleSyncRequestDTO(
    val appUserId: String,
    val obfuscatedAccountId: String? = null,
    val obfuscatedProfileId: String? = null,
    val source: String,
    val observedAt: String? = null,
    val purchases: List<AppActorGoogleRestorePurchaseDTO>,
)

@Serializable
internal data class AppActorGoogleSyncResponseDTO(
    val appUserId: String? = null,
    val customer: AppActorCustomerDTO,
    val syncedCount: Int = 0,
    val transferred: Boolean = false,
    val results: List<AppActorGoogleBatchResultDTO> = emptyList(),
    override val requestId: String? = null,
) : AppActorRequestIdCarrier

@Serializable
internal data class AppActorGoogleBatchResultDTO(
    val purchaseToken: String,
    val productId: String,
    val basePlanId: String? = null,
    val offerId: String? = null,
    val status: String,
    val message: String? = null,
)

internal fun AppActorGoogleSyncRequestDTO.toRestoreRequest(): AppActorGoogleRestoreRequestDTO {
    return AppActorGoogleRestoreRequestDTO(
        appUserId = appUserId,
        obfuscatedAccountId = obfuscatedAccountId,
        obfuscatedProfileId = obfuscatedProfileId,
        source = source,
        observedAt = observedAt,
        purchases = purchases,
    )
}

internal fun AppActorGoogleRestoreResponseDTO.toSyncResponse(): AppActorGoogleSyncResponseDTO {
    return AppActorGoogleSyncResponseDTO(
        appUserId = appUserId,
        customer = customer,
        syncedCount = restoredCount,
        transferred = transferred,
        results = results,
        requestId = requestId,
    )
}
