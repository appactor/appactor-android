package com.appactor.android.models

import com.appactor.android.billing.AppActorStoreProductRequest
import com.appactor.android.billing.toBillingReplacementMode

/**
 * Explicit direct Google Play purchase target.
 *
 * Prefer [AppActorPackage] from [AppActor.offerings] whenever possible. This
 * model is intended for direct store purchases where the exact Play target is
 * already known.
 */
public data class AppActorPurchaseParams @JvmOverloads constructor(
    public val productId: String,
    public val storeProductId: String? = null,
    public val basePlanId: String? = null,
    public val offerId: String? = null,
    public val oldPurchaseToken: String? = null,
    public val replacementMode: AppActorSubscriptionReplacementMode? = null,
    public val metadata: AppActorMetadata = emptyMap(),
    public val productType: AppActorProductType = AppActorProductType.Unknown,
) {
    init {
        require(productId.isNotBlank()) {
            "purchase params productId must not be blank."
        }
    }
}

internal fun AppActorPurchaseParams.toAppActorPackage(): AppActorPackage {
    return AppActorPackage(
        id = storeLookupProductId(),
        store = AppActorStore.PlayStore,
        productId = productId,
        storeProductId = storeProductId?.takeIf { it.isNotBlank() },
        productType = resolvedProductType(),
        basePlanId = basePlanId,
        offerId = offerId,
        metadata = metadata,
        oldPurchaseToken = oldPurchaseToken,
        replacementMode = replacementMode,
    )
}

internal fun AppActorPackage.toPurchaseParams(): AppActorPurchaseParams {
    return AppActorPurchaseParams(
        productId = productId,
        storeProductId = storeProductId,
        basePlanId = basePlanId,
        offerId = offerId,
        oldPurchaseToken = oldPurchaseToken,
        replacementMode = replacementMode,
        metadata = metadata,
        productType = productType,
    )
}

internal fun AppActorPurchaseParams.toResolvedPurchaseTarget(
    appUserId: String,
): AppActorResolvedPurchaseTarget {
    validateDirectPurchaseContract()
    val lookupProductId = storeLookupProductId()
    return AppActorResolvedPurchaseTarget(
        request = AppActorStoreProductRequest(
            productId = lookupProductId,
            productType = productType,
            basePlanId = basePlanId,
            offerId = offerId,
            obfuscatedAccountId = appActorGoogleObfuscatedAccountId(appUserId),
            oldPurchaseToken = oldPurchaseToken,
            replacementMode = replacementMode?.toBillingReplacementMode(),
        ),
        expectedProductId = lookupProductId,
        expectedProductType = productType,
        expectedBasePlanId = basePlanId,
        expectedOfferId = offerId,
        requiresStoreResolution = true,
    )
}

private fun AppActorPurchaseParams.resolvedProductType(): AppActorProductType {
    return when {
        productType != AppActorProductType.Unknown -> productType
        basePlanId != null || offerId != null || oldPurchaseToken != null || replacementMode != null ->
            AppActorProductType.Subscription

        else -> AppActorProductType.Unknown
    }
}

private fun AppActorPurchaseParams.validateDirectPurchaseContract() {
    if (productType == AppActorProductType.Unknown) {
        throw AppActorError.InvalidConfiguration(
            "Underspecified direct purchase for $productId. " +
                "AppActorPurchaseParams requires an explicit productType."
        )
    }

    val hasBasePlanId = !basePlanId.isNullOrBlank()
    val hasOfferId = !offerId.isNullOrBlank()
    val hasOldPurchaseToken = !oldPurchaseToken.isNullOrBlank()

    when (productType) {
        AppActorProductType.Subscription -> {
            if (!hasBasePlanId) {
                throw AppActorError.InvalidConfiguration(
                    "Underspecified direct purchase for $productId. " +
                        "Subscription purchases require a basePlanId."
                )
            }
            if (replacementMode != null && !hasOldPurchaseToken) {
                throw AppActorError.InvalidConfiguration(
                    "Invalid subscription replacement params for $productId. " +
                        "replacementMode requires oldPurchaseToken."
                )
            }
        }

        AppActorProductType.Consumable,
        AppActorProductType.NonConsumable -> {
            if (hasBasePlanId || hasOfferId || hasOldPurchaseToken || replacementMode != null) {
                throw AppActorError.InvalidConfiguration(
                    "Invalid direct one-time purchase params for $productId. " +
                        "basePlanId, offerId, oldPurchaseToken, and replacementMode are only valid for subscriptions."
                )
            }
        }

        AppActorProductType.Unknown -> Unit
    }
}
