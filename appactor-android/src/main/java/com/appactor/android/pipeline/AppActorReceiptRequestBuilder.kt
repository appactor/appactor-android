package com.appactor.android.pipeline

import com.appactor.android.backend.dto.AppActorGoogleReceiptRequestDTO
import com.appactor.android.models.AppActorBridgeReceiptEvent
import com.appactor.android.models.AppActorEnvironment
import com.appactor.android.storage.AppActorReceiptQueueItem

internal object AppActorReceiptRequestBuilder {
    fun buildGoogleReceiptRequest(
        item: AppActorReceiptQueueItem,
    ): AppActorGoogleReceiptRequestDTO {
        val clientDeliverySource = if (item.retryCount > 0 && item.clientDeliverySource != null) {
            AppActorClientDeliverySource.QueueRetry.wireValue
        } else {
            item.clientDeliverySource
        }
        return AppActorGoogleReceiptRequestDTO(
            appUserId = item.appUserId,
            packageName = item.packageName,
            environment = item.environment,
            productId = item.productId,
            productType = item.productType,
            purchaseToken = item.purchaseToken,
            purchaseTime = item.purchaseTime,
            purchaseState = item.purchaseState,
            orderId = item.orderId,
            basePlanId = item.basePlanId,
            offerId = item.offerId,
            priceAmountMicros = item.priceAmountMicros,
            currency = item.currencyCode,
            isAutoRenewing = item.isAutoRenewing,
            obfuscatedAccountId = item.obfuscatedAccountId,
            obfuscatedProfileId = null,
            sourceIntent = item.sourceIntent,
            source = "purchase_update",
            observedAt = item.purchaseTime.toLongOrNull()?.let { AppActorBridgeReceiptEvent.millisToIso8601(it) },
            clientPurchaseAttemptStartedAt = item.clientPurchaseAttemptStartedAt,
            clientObservedAt = item.clientObservedAt,
            clientDeliverySource = clientDeliverySource,
            clientPurchaseAttemptId = item.clientPurchaseAttemptId,
            placement = item.placement.normalizePlacement(),
            sdkOriginated = item.sdkOriginated,
            sdkVersion = item.sdkVersion,
            idempotencyKey = item.idempotencyKey,
            rawPurchaseData = item.rawPurchaseData,
            purchaseSignature = item.purchaseSignature,
            countryCode = item.countryCode,
            offeringId = item.offeringId,
            packageId = item.packageId,
        )
    }

    fun environmentWireValue(environment: AppActorEnvironment): String {
        return when (environment) {
            AppActorEnvironment.Production -> "production"
            AppActorEnvironment.Sandbox -> "sandbox"
        }
    }

}
