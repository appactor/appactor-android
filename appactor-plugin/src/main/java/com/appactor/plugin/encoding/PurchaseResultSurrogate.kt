package com.appactor.plugin.encoding

import com.appactor.android.models.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class PurchaseResultSurrogate(
    val status: String,
    @SerialName("customer_info") val customerInfo: CustomerInfoSurrogate? = null,
    @SerialName("purchase_info") val purchaseInfo: PurchaseInfoSurrogate? = null,
) {
    constructor(from: AppActorPurchaseResult) : this(
        status = when (from) {
            is AppActorPurchaseResult.Success -> "success"
            is AppActorPurchaseResult.Pending -> "pending"
            is AppActorPurchaseResult.Cancelled -> "cancelled"
        },
        customerInfo = (from as? AppActorPurchaseResult.Success)?.customerInfo?.let { CustomerInfoSurrogate(it) },
        purchaseInfo = (from as? AppActorPurchaseResult.Success)?.purchaseInfo?.let { PurchaseInfoSurrogate(it) },
    )
}

@Serializable
internal data class PurchaseInfoSurrogate(
    val store: String,
    @SerialName("product_id") val productId: String,
    @SerialName("transaction_id") val transactionId: String? = null,
    @SerialName("original_transaction_id") val originalTransactionId: String? = null,
    @SerialName("purchase_date") val purchaseDate: String? = null,
    @SerialName("is_sandbox") val isSandbox: Boolean = false,
) {
    constructor(from: AppActorPurchaseInfo) : this(
        store = from.store.wireValue,
        productId = from.productId,
        transactionId = from.transactionId,
        originalTransactionId = from.originalTransactionId,
        purchaseDate = from.purchaseDate,
        isSandbox = from.isSandbox,
    )
}
