package com.appactor.plugin.encoding

import com.appactor.android.models.AppActorBridgeReceiptEvent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class BridgeReceiptEventSurrogate(
    val type: String,
    @SerialName("transaction_id") val transactionId: String? = null,
    @SerialName("product_id") val productId: String,
    @SerialName("app_user_id") val appUserId: String,
    @SerialName("retry_count") val retryCount: Int? = null,
    @SerialName("next_attempt_at") val nextAttemptAt: String? = null,
    @SerialName("error_code") val errorCode: String? = null,
    val key: String? = null,
) {
    constructor(from: AppActorBridgeReceiptEvent) : this(
        type = from.type.lowercase(),
        transactionId = from.transactionId,
        productId = from.productId,
        appUserId = from.appUserId,
        retryCount = from.retryCount,
        nextAttemptAt = from.nextAttemptAt,
        errorCode = from.errorCode,
        key = from.key,
    )
}
