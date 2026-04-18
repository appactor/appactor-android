package com.appactor.android.models

import java.time.Instant

public data class AppActorBridgeReceiptEvent(
    public val type: String,
    public val transactionId: String? = null,
    public val productId: String,
    public val appUserId: String,
    public val retryCount: Int? = null,
    public val nextAttemptAt: String? = null,
    public val errorCode: String? = null,
    public val key: String? = null,
) {
    public companion object {
        public const val TYPE_POSTED_OK: String = "POSTED_OK"
        public const val TYPE_DEFERRED_WAITING_FOR_IDENTITY: String = "DEFERRED_WAITING_FOR_IDENTITY"
        public const val TYPE_RETRY_SCHEDULED: String = "RETRY_SCHEDULED"
        public const val TYPE_PERMANENTLY_REJECTED: String = "PERMANENTLY_REJECTED"
        public const val TYPE_DEAD_LETTERED: String = "DEAD_LETTERED"
        public const val TYPE_DUPLICATE_SKIPPED: String = "DUPLICATE_SKIPPED"

        public fun millisToIso8601(millis: Long): String =
            Instant.ofEpochMilli(millis).toString()
    }
}

public fun AppActorReceiptPipelineEvent.toBridgeEvent(): AppActorBridgeReceiptEvent {
    return when (this) {
        is AppActorReceiptPipelineEvent.PostedOk -> AppActorBridgeReceiptEvent(
            type = AppActorBridgeReceiptEvent.TYPE_POSTED_OK,
            transactionId = orderId,
            productId = productId,
            appUserId = appUserId,
        )

        is AppActorReceiptPipelineEvent.DeferredWaitingForIdentity -> AppActorBridgeReceiptEvent(
            type = AppActorBridgeReceiptEvent.TYPE_DEFERRED_WAITING_FOR_IDENTITY,
            transactionId = transactionId ?: orderId,
            productId = productId,
            appUserId = appUserId,
        )

        is AppActorReceiptPipelineEvent.RetryScheduled -> AppActorBridgeReceiptEvent(
            type = AppActorBridgeReceiptEvent.TYPE_RETRY_SCHEDULED,
            transactionId = orderId,
            productId = productId,
            appUserId = appUserId,
            retryCount = retryCount,
            nextAttemptAt = AppActorBridgeReceiptEvent.millisToIso8601(nextRetryAtMillis),
            errorCode = errorCode,
        )

        is AppActorReceiptPipelineEvent.PermanentlyRejected -> AppActorBridgeReceiptEvent(
            type = AppActorBridgeReceiptEvent.TYPE_PERMANENTLY_REJECTED,
            transactionId = orderId,
            productId = productId,
            appUserId = appUserId,
            errorCode = code,
        )

        is AppActorReceiptPipelineEvent.DeadLettered -> AppActorBridgeReceiptEvent(
            type = AppActorBridgeReceiptEvent.TYPE_DEAD_LETTERED,
            transactionId = orderId,
            productId = productId,
            appUserId = appUserId,
            retryCount = retryCount,
            errorCode = lastError,
        )

        is AppActorReceiptPipelineEvent.DuplicateSkipped -> AppActorBridgeReceiptEvent(
            type = AppActorBridgeReceiptEvent.TYPE_DUPLICATE_SKIPPED,
            productId = productId,
            appUserId = appUserId,
            key = key,
        )
    }
}
