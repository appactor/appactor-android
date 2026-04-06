package com.appactor.android.models

public sealed interface AppActorPurchaseResult {
    public data class Success(
        val customerInfo: AppActorCustomerInfo,
        val purchaseInfo: AppActorPurchaseInfo? = null,
    ) : AppActorPurchaseResult

    public data object Pending : AppActorPurchaseResult

    public data object Cancelled : AppActorPurchaseResult
}
