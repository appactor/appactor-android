package com.appactor.android.pipeline

import com.appactor.android.internal.AppActorSDK
import com.appactor.android.models.AppActorBridgeReceiptEvent
import java.util.UUID

internal enum class AppActorClientDeliverySource(val wireValue: String) {
    PurchaseFlow("purchase_flow"),
    TransactionUpdates("transaction_updates"),
    Unfinished("unfinished"),
    CurrentEntitlements("current_entitlements"),
    RestoreFlow("restore_flow"),
    QueueRetry("queue_retry"),
    ForegroundSync("foreground_sync"),
}

internal data class AppActorClientPurchaseContext(
    val clientPurchaseAttemptStartedAtMillis: Long? = null,
    val clientObservedAtMillis: Long,
    val clientDeliverySource: AppActorClientDeliverySource,
    val clientPurchaseAttemptId: String? = null,
    val sdkOriginated: Boolean = true,
    val sdkVersion: String = AppActorSDK.version,
) {
    val hasPurchaseAttempt: Boolean
        get() = clientPurchaseAttemptStartedAtMillis != null && !clientPurchaseAttemptId.isNullOrBlank()

    val clientPurchaseAttemptStartedAt: String?
        get() = clientPurchaseAttemptStartedAtMillis?.let(AppActorBridgeReceiptEvent::millisToIso8601)

    val clientObservedAt: String
        get() = AppActorBridgeReceiptEvent.millisToIso8601(clientObservedAtMillis)

    fun withDeliverySource(
        source: AppActorClientDeliverySource,
        observedAtMillis: Long = clientObservedAtMillis,
    ): AppActorClientPurchaseContext {
        return copy(
            clientObservedAtMillis = observedAtMillis,
            clientDeliverySource = source,
        )
    }

    fun toPendingEntry(productId: String, recordedAtMillis: Long): String {
        return listOf(
            productId,
            recordedAtMillis.toString(),
            clientPurchaseAttemptStartedAtMillis?.toString().orEmpty(),
            clientPurchaseAttemptId.orEmpty(),
        ).joinToString("|")
    }

    companion object {
        fun purchaseAttempt(startedAtMillis: Long): AppActorClientPurchaseContext {
            return AppActorClientPurchaseContext(
                clientPurchaseAttemptStartedAtMillis = startedAtMillis,
                clientObservedAtMillis = startedAtMillis,
                clientDeliverySource = AppActorClientDeliverySource.PurchaseFlow,
                clientPurchaseAttemptId = UUID.randomUUID().toString().lowercase(),
            )
        }

        fun restoreFlow(observedAtMillis: Long): AppActorClientPurchaseContext {
            return AppActorClientPurchaseContext(
                clientObservedAtMillis = observedAtMillis,
                clientDeliverySource = AppActorClientDeliverySource.RestoreFlow,
            )
        }

        fun foregroundSync(observedAtMillis: Long): AppActorClientPurchaseContext {
            return AppActorClientPurchaseContext(
                clientObservedAtMillis = observedAtMillis,
                clientDeliverySource = AppActorClientDeliverySource.ForegroundSync,
            )
        }

        fun transactionUpdates(observedAtMillis: Long): AppActorClientPurchaseContext {
            return AppActorClientPurchaseContext(
                clientObservedAtMillis = observedAtMillis,
                clientDeliverySource = AppActorClientDeliverySource.TransactionUpdates,
            )
        }

        fun fromPendingEntry(entry: PendingPurchaseEntry, observedAtMillis: Long): AppActorClientPurchaseContext? {
            val attemptStartedAtMillis = entry.clientPurchaseAttemptStartedAtMillis ?: return null
            val attemptId = entry.clientPurchaseAttemptId?.takeIf { it.isNotBlank() } ?: return null
            return AppActorClientPurchaseContext(
                clientPurchaseAttemptStartedAtMillis = attemptStartedAtMillis,
                clientObservedAtMillis = observedAtMillis,
                clientDeliverySource = AppActorClientDeliverySource.TransactionUpdates,
                clientPurchaseAttemptId = attemptId,
            )
        }
    }
}

internal data class PendingPurchaseEntry(
    val productId: String,
    val recordedAtMillis: Long,
    val clientPurchaseAttemptStartedAtMillis: Long? = null,
    val clientPurchaseAttemptId: String? = null,
) {
    companion object {
        fun parse(raw: String): PendingPurchaseEntry? {
            val parts = raw.split("|")
            return when {
                parts.size >= 4 -> PendingPurchaseEntry(
                    productId = parts[0].takeIf { it.isNotBlank() } ?: return null,
                    recordedAtMillis = parts[1].toLongOrNull() ?: return null,
                    clientPurchaseAttemptStartedAtMillis = parts[2].toLongOrNull(),
                    clientPurchaseAttemptId = parts[3].takeIf { it.isNotBlank() },
                )
                parts.size == 2 -> PendingPurchaseEntry(
                    productId = parts[0].takeIf { it.isNotBlank() } ?: return null,
                    recordedAtMillis = parts[1].toLongOrNull() ?: return null,
                )
                else -> null
            }
        }
    }
}
