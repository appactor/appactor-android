package com.appactor.android.backend.dto

import kotlinx.serialization.Serializable

@Serializable
internal data class AppActorGoogleReceiptRequestDTO(
    val appUserId: String,
    val packageName: String,
    val environment: String,
    val productId: String,
    val productType: String,
    val purchaseToken: String,
    val purchaseTime: String,
    val purchaseState: String,
    val orderId: String? = null,
    val basePlanId: String? = null,
    val offerId: String? = null,
    val priceAmountMicros: Long? = null,
    val currency: String? = null,
    val isAutoRenewing: Boolean? = null,
    val obfuscatedAccountId: String? = null,
    val obfuscatedProfileId: String? = null,
    val sourceIntent: String,
    val source: String? = null,
    val observedAt: String? = null,
    val clientPurchaseAttemptStartedAt: String? = null,
    val clientObservedAt: String? = null,
    val clientDeliverySource: String? = null,
    val clientPurchaseAttemptId: String? = null,
    val placement: String? = null,
    val sdkOriginated: Boolean? = null,
    val sdkVersion: String? = null,
    val idempotencyKey: String,
    val rawPurchaseData: String? = null,
    val purchaseSignature: String? = null,
    val countryCode: String? = null,
    val offeringId: String? = null,
    val packageId: String? = null,
)

@Serializable
internal data class AppActorGoogleReceiptResponseDTO(
    val status: String,
    override val requestId: String? = null,
    val customer: AppActorCustomerDTO? = null,
    val error: AppActorBackendErrorDTO? = null,
    val retryAfterSeconds: Double? = null,
    val acknowledgePurchase: Boolean? = null,
    val consumePurchase: Boolean? = null,
) : AppActorRequestIdCarrier

internal sealed interface AppActorGoogleReceiptPostResult {
    data class Success(
        val requestId: String?,
        val customerInfo: com.appactor.android.models.AppActorCustomerInfo?,
        val acknowledgePurchase: Boolean,
        val consumePurchase: Boolean,
    ) : AppActorGoogleReceiptPostResult

    data class RetryableError(
        val requestId: String?,
        val error: AppActorBackendErrorDTO?,
        val retryAfterSeconds: Double?,
        val acknowledgePurchase: Boolean,
        val consumePurchase: Boolean,
    ) : AppActorGoogleReceiptPostResult

    data class PermanentError(
        val requestId: String?,
        val error: AppActorBackendErrorDTO?,
        val acknowledgePurchase: Boolean,
        val consumePurchase: Boolean,
    ) : AppActorGoogleReceiptPostResult
}
