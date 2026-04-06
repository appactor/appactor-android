package com.appactor.android.models

public data class AppActorNonSubscription(
    val productIdentifier: String,
    val store: AppActorStore = AppActorStore.Unknown,
    val basePlanId: String? = null,
    val offerId: String? = null,
    val purchaseDate: String? = null,
    val storeTransactionIdentifier: String? = null,
    val originalTransactionIdentifier: String? = null,
    val isSandbox: Boolean? = null,
    val isConsumable: Boolean? = null,
    val isRefund: Boolean? = null,
)
