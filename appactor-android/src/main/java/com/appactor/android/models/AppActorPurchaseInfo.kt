package com.appactor.android.models

public data class AppActorPurchaseInfo(
    val store: AppActorStore,
    val productId: String,
    val transactionId: String? = null,
    val originalTransactionId: String? = null,
    val purchaseDate: String? = null,
    val isSandbox: Boolean = false,
)
