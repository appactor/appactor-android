package com.appactor.android.backend.mappers

import com.appactor.android.backend.dto.AppActorCustomerDTO
import com.appactor.android.backend.dto.AppActorCustomerEnvelopeDTO
import com.appactor.android.backend.dto.AppActorEntitlementDTO
import com.appactor.android.backend.dto.AppActorGoogleReceiptPostResult
import com.appactor.android.backend.dto.AppActorGoogleReceiptResponseDTO
import com.appactor.android.backend.dto.AppActorGoogleRestoreResponseDTO
import com.appactor.android.backend.dto.AppActorGoogleRestoreResult
import com.appactor.android.backend.dto.AppActorNonSubscriptionDTO
import com.appactor.android.backend.dto.AppActorOfferingDTO
import com.appactor.android.backend.dto.AppActorOfferingsEnvelopeDTO
import com.appactor.android.backend.dto.AppActorPackageDTO
import com.appactor.android.backend.dto.AppActorProductReferenceDTO
import com.appactor.android.backend.dto.AppActorSubscriptionDTO
import com.appactor.android.models.AppActorCancellationReason
import com.appactor.android.models.AppActorCustomerInfo
import com.appactor.android.models.AppActorEntitlementInfo
import com.appactor.android.models.AppActorNonSubscription
import com.appactor.android.models.AppActorOffering
import com.appactor.android.models.AppActorOfferings
import com.appactor.android.models.AppActorOwnershipType
import com.appactor.android.models.AppActorPackage
import com.appactor.android.models.AppActorPackageType
import com.appactor.android.models.AppActorPeriodType
import com.appactor.android.models.AppActorProductType
import com.appactor.android.models.AppActorStore
import com.appactor.android.models.AppActorSubscriptionInfo
import com.appactor.android.models.AppActorSubscriptionStatus
import com.appactor.android.models.AppActorTokenBalance
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

internal fun AppActorOfferingsEnvelopeDTO.toModel(): AppActorOfferings {
    val currentOffering = data.currentOffering?.toModel()
    val offerings = data.offerings
        .map { offering -> offering.toModel() }
        .associateByTo(linkedMapOf()) { it.id }

    currentOffering?.let { offerings[it.id] = it }

    return AppActorOfferings(
        current = currentOffering,
        all = offerings,
        productEntitlements = data.productEntitlements,
    )
}

internal fun AppActorOfferingDTO.toModel(): AppActorOffering {
    return AppActorOffering(
        id = id,
        displayName = displayName ?: id,
        isCurrent = isCurrent,
        lookupKey = lookupKey,
        metadata = metadata.toMetadata(),
        packages = packages.map { it.toModel() },
    )
}

internal fun AppActorPackageDTO.toModel(): AppActorPackage {
    val selectedProduct = products.firstOrNull { AppActorStore.fromWireValue(it.store) == AppActorStore.PlayStore }
        ?: products.firstOrNull()
        ?: AppActorProductReferenceDTO(
            productId = id,
            storeProductId = id,
        )

    val resolvedPackageType = AppActorPackageType.fromServerValue(packageType)
    val customTypeIdentifier = if (resolvedPackageType == AppActorPackageType.Custom) packageType else null

    return AppActorPackage(
        id = id,
        packageType = resolvedPackageType,
        customTypeIdentifier = customTypeIdentifier,
        store = AppActorStore.fromWireValue(selectedProduct.store),
        productId = selectedProduct.productId,
        storeProductId = selectedProduct.storeProductId ?: selectedProduct.productId,
        serverId = selectedProduct.id,
        productType = AppActorProductType.fromWireValue(selectedProduct.productType),
        basePlanId = selectedProduct.basePlanId,
        offerId = selectedProduct.offerId,
        localizedPriceString = null,
        price = null,
        currencyCode = null,
        displayName = displayName,
        productName = selectedProduct.displayName,
        productDescription = null,
        metadata = metadata.toMetadata(),
        tokenAmount = tokenAmount,
        position = position,
    )
}

internal fun AppActorCustomerEnvelopeDTO.toModel(
    productEntitlements: Map<String, List<String>> = emptyMap(),
): AppActorCustomerInfo {
    return customer.toModel(
        appUserId = appUserId,
        requestId = requestId,
        requestDate = requestDate,
        productEntitlements = productEntitlements,
    )
}

internal fun AppActorCustomerDTO.toModel(
    appUserId: String? = null,
    requestId: String? = null,
    requestDate: String? = null,
    productEntitlements: Map<String, List<String>> = emptyMap(),
): AppActorCustomerInfo {
    return AppActorCustomerInfo(
        entitlements = entitlements.mapValues { (identifier, dto) -> dto.toModel(identifier) },
        subscriptions = subscriptions.mapValues { (key, dto) -> dto.toModel(key) },
        nonSubscriptions = nonSubscriptions.mapValues { (_, values) -> values.map { it.toModel() } },
        consumableBalances = null,
        tokenBalance = tokenBalance?.let { AppActorTokenBalance(it.renewable, it.nonRenewable, it.total) },
        snapshotDate = requestDate,
        appUserId = appUserId,
        requestId = requestId,
        requestDate = requestDate,
        firstSeen = firstSeen,
        lastSeen = lastSeen,
        managementUrl = managementUrl,
        isComputedOffline = false,
        productEntitlements = productEntitlements,
    )
}

internal fun AppActorEntitlementDTO.toModel(identifier: String): AppActorEntitlementInfo {
    val subscriptionStatus = AppActorSubscriptionStatus.fromWireValue(status)
    return AppActorEntitlementInfo(
        identifier = identifier,
        isActive = isActive,
        status = status,
        productIdentifier = productId,
        grantedBy = grantedBy,
        ownershipType = AppActorOwnershipType.fromWireValue(ownershipType),
        periodType = AppActorPeriodType.fromWireValue(periodType),
        willRenew = subscriptionStatus.isEntitled,
        subscriptionStatus = subscriptionStatus,
        store = AppActorStore.fromWireValue(store),
        basePlanId = basePlanId,
        offerId = offerId,
        isSandbox = isSandbox,
        cancellationReason = cancellationReason?.let(AppActorCancellationReason::fromWireValue),
        purchaseDate = purchaseDate,
        startsAt = startsAt,
        latestPurchaseDate = purchaseDate,
        originalPurchaseDate = null,
        expirationDate = expiresAt,
        gracePeriodExpiresAt = gracePeriodExpiresAt,
        billingIssueDetectedAt = billingIssueDetectedAt,
        unsubscribeDetectedAt = unsubscribeDetectedAt,
        renewedAt = renewedAt,
        activePromotionalOfferType = activePromotionalOfferType,
        activePromotionalOfferId = activePromotionalOfferId,
    )
}

internal fun AppActorSubscriptionDTO.toModel(subscriptionKey: String): AppActorSubscriptionInfo {
    return AppActorSubscriptionInfo(
        subscriptionKey = subscriptionKey,
        productIdentifier = productId,
        store = AppActorStore.fromWireValue(store),
        basePlanId = basePlanId,
        offerId = offerId,
        isActive = isActive,
        expiresDate = expiresAt,
        purchaseDate = purchaseDate,
        startsAt = startsAt,
        periodType = periodType?.let(AppActorPeriodType::fromWireValue),
        status = status,
        autoRenew = autoRenew,
        isSandbox = isSandbox,
        gracePeriodExpiresAt = gracePeriodExpiresAt,
        unsubscribeDetectedAt = unsubscribeDetectedAt,
        cancellationReason = cancellationReason?.let(AppActorCancellationReason::fromWireValue),
        renewedAt = renewedAt,
        originalTransactionId = originalTransactionId,
        latestTransactionId = latestTransactionId,
        activePromotionalOfferType = activePromotionalOfferType,
        activePromotionalOfferId = activePromotionalOfferId,
    )
}

internal fun AppActorNonSubscriptionDTO.toModel(): AppActorNonSubscription {
    return AppActorNonSubscription(
        productIdentifier = productId,
        store = AppActorStore.fromWireValue(store),
        basePlanId = basePlanId,
        offerId = offerId,
        purchaseDate = purchaseDate,
        storeTransactionIdentifier = storeTransactionIdentifier,
        originalTransactionIdentifier = null,
        isSandbox = isSandbox,
        isConsumable = isConsumable,
        isRefund = isRefund,
    )
}

internal fun AppActorGoogleReceiptResponseDTO.toResult(
    productEntitlements: Map<String, List<String>> = emptyMap(),
): AppActorGoogleReceiptPostResult {
    return when (status) {
        "ok" -> AppActorGoogleReceiptPostResult.Success(
            requestId = requestId,
            customerInfo = customer?.toModel(
                requestId = requestId,
                productEntitlements = productEntitlements,
            ),
            acknowledgePurchase = acknowledgePurchase ?: false,
            consumePurchase = consumePurchase ?: false,
        )

        "retryable_error" -> AppActorGoogleReceiptPostResult.RetryableError(
            requestId = requestId,
            error = error,
            retryAfterSeconds = retryAfterSeconds,
            acknowledgePurchase = acknowledgePurchase ?: false,
            consumePurchase = consumePurchase ?: false,
        )

        else -> AppActorGoogleReceiptPostResult.PermanentError(
            requestId = requestId,
            error = error,
            acknowledgePurchase = acknowledgePurchase ?: false,
            consumePurchase = consumePurchase ?: false,
        )
    }
}

internal fun AppActorGoogleRestoreResponseDTO.toResult(
    productEntitlements: Map<String, List<String>> = emptyMap(),
): AppActorGoogleRestoreResult {
    return AppActorGoogleRestoreResult(
        customerInfo = customer.toModel(
            requestId = requestId,
            productEntitlements = productEntitlements,
        ),
        restoredCount = restoredCount,
        transferred = transferred,
        requestId = requestId,
    )
}

private fun Map<String, JsonElement>.toMetadata(): Map<String, Any?> {
    return mapValues { (_, value) -> value.toAnyValue() }
}

private fun JsonElement.toAnyValue(): Any? {
    return when (this) {
        JsonNull -> null
        is JsonPrimitive -> when {
            isString -> content
            booleanOrNull != null -> booleanOrNull
            longOrNull != null -> longOrNull
            doubleOrNull != null -> doubleOrNull
            else -> content
        }

        is JsonArray -> map { it.toAnyValue() }
        is JsonObject -> mapValues { (_, value) -> value.toAnyValue() }
    }
}
