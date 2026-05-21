package com.appactor.android.models

import java.security.MessageDigest

internal enum class AppActorDiagnosticsDataSource {
    Network,
    Cache,
    Offline,
    Unknown,
}

internal enum class AppActorDebugCategory {
    Lifecycle,
    Billing,
    Network,
    Cache,
    Purchase,
    ReceiptPipeline,
}

internal data class AppActorDebugEvent(
    val category: AppActorDebugCategory,
    val level: AppActorLogLevel,
    val name: String,
    val message: String,
    val timestampMillis: Long,
    val requestId: String? = null,
    val attributes: Map<String, String> = emptyMap(),
)

public sealed interface AppActorReceiptPipelineEvent {
    public val appUserId: String

    public data class PostedOk(
        val key: String,
        val productId: String,
        val requestId: String?,
        override val appUserId: String,
        val orderId: String? = null,
    ) : AppActorReceiptPipelineEvent

    public data class RetryScheduled(
        val key: String,
        val productId: String,
        val retryCount: Int,
        val nextRetryAtMillis: Long,
        val errorCode: String?,
        override val appUserId: String,
        val orderId: String? = null,
    ) : AppActorReceiptPipelineEvent

    public data class PermanentlyRejected(
        val key: String,
        val productId: String,
        val code: String?,
        val message: String?,
        override val appUserId: String,
        val orderId: String? = null,
    ) : AppActorReceiptPipelineEvent

    public data class DeadLettered(
        val key: String,
        val productId: String,
        val retryCount: Int,
        val lastError: String?,
        override val appUserId: String,
        val orderId: String? = null,
    ) : AppActorReceiptPipelineEvent

    public data class DuplicateSkipped(
        val key: String,
        val productId: String,
        override val appUserId: String,
    ) : AppActorReceiptPipelineEvent
}

internal fun appActorPublicReceiptId(rawKey: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(rawKey.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
    return "receipt_${digest.take(16)}"
}
