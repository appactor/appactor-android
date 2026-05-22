package com.appactor.android.pipeline

import com.appactor.android.internal.AppActorSDK
import com.appactor.android.models.AppActorBridgeReceiptEvent
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
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
    val placement: String? = null,
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

    fun toPendingEntry(
        productId: String,
        recordedAtMillis: Long,
        appUserId: String? = null,
    ): String {
        return listOf(
            productId,
            recordedAtMillis.toString(),
            clientPurchaseAttemptStartedAtMillis?.toString().orEmpty(),
            clientPurchaseAttemptId.orEmpty(),
            PendingPurchaseEntry.encodeAppUserId(appUserId),
            PendingPurchaseEntry.encodePlacement(placement),
        ).joinToString("|")
    }

    companion object {
        fun purchaseAttempt(
            startedAtMillis: Long,
            placement: String? = null,
        ): AppActorClientPurchaseContext {
            return AppActorClientPurchaseContext(
                clientPurchaseAttemptStartedAtMillis = startedAtMillis,
                clientObservedAtMillis = startedAtMillis,
                clientDeliverySource = AppActorClientDeliverySource.PurchaseFlow,
                clientPurchaseAttemptId = UUID.randomUUID().toString().lowercase(),
                placement = placement.normalizePlacement(),
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
                placement = entry.placement,
            )
        }
    }
}

internal data class PendingPurchaseEntry(
    val productId: String,
    val recordedAtMillis: Long,
    val clientPurchaseAttemptStartedAtMillis: Long? = null,
    val clientPurchaseAttemptId: String? = null,
    val appUserId: String? = null,
    val placement: String? = null,
) {
    companion object {
        private const val ENCODED_APP_USER_ID_PREFIX = "url:"
        private const val ENCODED_PLACEMENT_PREFIX = "url:"

        fun parse(raw: String): PendingPurchaseEntry? {
            val parts = raw.split("|")
            return when {
                parts.size >= 4 -> PendingPurchaseEntry(
                    productId = parts[0].takeIf { it.isNotBlank() } ?: return null,
                    recordedAtMillis = parts[1].toLongOrNull() ?: return null,
                    clientPurchaseAttemptStartedAtMillis = parts[2].toLongOrNull(),
                    clientPurchaseAttemptId = parts[3].takeIf { it.isNotBlank() },
                    appUserId = parts.getOrNull(4)?.let(::decodeAppUserId)?.takeIf { it.isNotBlank() },
                    placement = parts.getOrNull(5)?.let(::decodePlacement).normalizePlacement(),
                )
                parts.size == 2 -> PendingPurchaseEntry(
                    productId = parts[0].takeIf { it.isNotBlank() } ?: return null,
                    recordedAtMillis = parts[1].toLongOrNull() ?: return null,
                )
                else -> null
            }
        }

        fun encodeAppUserId(appUserId: String?): String {
            val normalized = appUserId?.trim()?.takeIf { it.isNotEmpty() } ?: return ""
            return ENCODED_APP_USER_ID_PREFIX + URLEncoder.encode(normalized, StandardCharsets.UTF_8.name())
        }

        fun encodePlacement(placement: String?): String {
            val normalized = placement.normalizePlacement() ?: return ""
            return ENCODED_PLACEMENT_PREFIX + URLEncoder.encode(normalized, StandardCharsets.UTF_8.name())
        }

        private fun decodeAppUserId(value: String): String {
            if (value.isBlank()) return ""
            if (!value.startsWith(ENCODED_APP_USER_ID_PREFIX)) return value
            return runCatching {
                URLDecoder.decode(
                    value.removePrefix(ENCODED_APP_USER_ID_PREFIX),
                    StandardCharsets.UTF_8.name(),
                )
            }.getOrDefault("")
        }

        private fun decodePlacement(value: String): String {
            if (value.isBlank()) return ""
            if (!value.startsWith(ENCODED_PLACEMENT_PREFIX)) return value
            return runCatching {
                URLDecoder.decode(
                    value.removePrefix(ENCODED_PLACEMENT_PREFIX),
                    StandardCharsets.UTF_8.name(),
                )
            }.getOrDefault("")
        }
    }
}

internal fun String?.normalizePlacement(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
