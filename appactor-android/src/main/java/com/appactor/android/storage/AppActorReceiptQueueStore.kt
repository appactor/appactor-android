package com.appactor.android.storage

import android.content.Context
import com.appactor.android.backend.client.AppActorBackendJson
import com.appactor.android.internal.logging.AppActorLogger
import com.appactor.android.models.AppActorProductType
import com.appactor.android.models.appActorPublicReceiptId
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Serializable
internal enum class AppActorReceiptQueuePhase {
    NeedsPost,
    Posting,
    NeedsFinish,
    DeadLettered,
}

@Serializable
internal data class AppActorReceiptQueueItem(
    val key: String,
    val appUserId: String,
    val packageName: String,
    val environment: String,
    val productId: String,
    val productType: String,
    val purchaseToken: String,
    val purchaseTime: String,
    val purchaseState: String,
    val orderId: String? = null,
    val basePlanId: String? = null,
    val offerId: String? = null,
    val priceAmountMicros: Long? = null,
    val currencyCode: String? = null,
    val isAutoRenewing: Boolean? = null,
    val obfuscatedAccountId: String? = null,
    val sourceIntent: String = "purchase",
    val idempotencyKey: String,
    val rawPurchaseData: String? = null,
    val purchaseSignature: String? = null,
    val countryCode: String? = null,
    val clientPurchaseAttemptStartedAt: String? = null,
    val clientObservedAt: String? = null,
    val clientDeliverySource: String? = null,
    val clientPurchaseAttemptId: String? = null,
    val placement: String? = null,
    val sdkOriginated: Boolean? = null,
    val sdkVersion: String? = null,
    val isAcknowledged: Boolean = false,
    val shouldAcknowledge: Boolean = false,
    val shouldConsume: Boolean = false,
    /** A dead letter consumed or acknowledged on Play; a later post must not finish it again. */
    val finishedOnDevice: Boolean = false,
    /** When the item was first dead-lettered. */
    val deadLetteredAtMillis: Long? = null,
    val retryCount: Int = 0,
    val nextRetryAtMillis: Long = 0L,
    val createdAtMillis: Long,
    val lastUpdatedAtMillis: Long,
    val claimedAtMillis: Long? = null,
    val phase: AppActorReceiptQueuePhase = AppActorReceiptQueuePhase.NeedsPost,
    val lastError: String? = null,
    val offeringId: String? = null,
    val packageId: String? = null,
) {
    /** Older versions did not stamp [deadLetteredAtMillis]; their last update is the closest. */
    val deadLetterRetentionStartMillis: Long
        get() = deadLetteredAtMillis ?: lastUpdatedAtMillis

    val hasPurchaseAttempt: Boolean
        get() = clientPurchaseAttemptStartedAt != null && !clientPurchaseAttemptId.isNullOrBlank()

    /** An unknown-type dead letter stays queued: seeing its purchase again, once typed, revives it. */
    val isRecoverableDeadLetter: Boolean
        get() = phase == AppActorReceiptQueuePhase.DeadLettered && productType == AppActorProductType.Unknown.wireValue

    /**
     * Who the item belongs to once its purchase is seen again for [incomingAppUserId]. One being
     * posted or finished keeps its user. So does one a purchase flow queued: it belongs to the user
     * who bought it, whichever session sees it later (after a logout or an account switch), as on iOS.
     */
    fun ownerAfterSighting(incomingAppUserId: String): String =
        if (phase == AppActorReceiptQueuePhase.Posting || phase == AppActorReceiptQueuePhase.NeedsFinish || hasPurchaseBinding) {
            appUserId
        } else {
            incomingAppUserId
        }

    private val hasPurchaseBinding: Boolean
        get() = hasPurchaseAttempt || clientDeliverySource == "purchase_flow" || offeringId != null || packageId != null

    companion object {
        fun makeKey(
            purchaseToken: String,
            productId: String,
            basePlanId: String? = null,
            orderId: String? = null,
            purchaseTime: String? = null,
        ): String {
            return listOfNotNull(
                "google",
                productId,
                basePlanId,
                purchaseToken,
                postedLedgerRevision(orderId = orderId, purchaseTime = purchaseTime),
            ).joinToString(":")
        }

        private fun postedLedgerRevision(orderId: String?, purchaseTime: String?): String? {
            return orderId
                ?.takeIf { it.isNotBlank() }
                ?.let { "orderId=$it" }
                ?: purchaseTime
                    ?.takeIf { it.isNotBlank() }
                    ?.let { "purchaseTime=$it" }
        }
    }
}

internal interface AppActorReceiptQueueStore {
    fun upsert(item: AppActorReceiptQueueItem)
    fun upsertAll(items: List<AppActorReceiptQueueItem>)
    fun get(key: String): AppActorReceiptQueueItem?
    fun claimReady(limit: Int, nowMillis: Long = System.currentTimeMillis()): List<AppActorReceiptQueueItem>
    fun update(item: AppActorReceiptQueueItem)
    fun remove(key: String)
    fun clear()
    fun pendingCount(): Int
    fun deadLetteredCount(): Int
    fun snapshot(): List<AppActorReceiptQueueItem>
    fun consumeDeadLettered(): List<AppActorReceiptQueueItem>
    fun getRateLimitCooldownMillis(): Long?
    fun setRateLimitCooldownMillis(value: Long?)

    /**
     * Deletes the queue for good, for reset(): later writes through this instance are dropped, so
     * an operation of the ended session still running can't bring its receipts back.
     */
    fun wipe()
}

internal class AppActorAtomicJsonReceiptQueueStore(
    context: Context,
    directory: File = File(context.filesDir, "appactor"),
) : AppActorReceiptQueueStore {

    companion object {
        private const val TAG = "ReceiptQueueStore"
        private const val CORRUPT_SIDECAR_SUFFIX = ".corrupt"
        const val STALE_CLAIM_THRESHOLD_MILLIS: Long = 2 * 60 * 1_000L
        const val DEAD_LETTER_RETENTION_MILLIS: Long = 30L * 24 * 60 * 60 * 1_000
        private val SOURCE_INTENT_PRIORITY = mapOf(
            "queue" to 0,
            "sync" to 1,
            "restore" to 2,
            "purchase" to 3,
        )

        private fun mergeSourceIntent(
            existing: AppActorReceiptQueueItem,
            incoming: AppActorReceiptQueueItem,
        ): String {
            val existingPriority = SOURCE_INTENT_PRIORITY[existing.sourceIntent] ?: 0
            val incomingPriority = SOURCE_INTENT_PRIORITY[incoming.sourceIntent] ?: 0
            return if (incomingPriority > existingPriority) incoming.sourceIntent else existing.sourceIntent
        }

        private fun shouldAdoptClientPurchaseContext(
            existing: AppActorReceiptQueueItem,
            incoming: AppActorReceiptQueueItem,
        ): Boolean {
            val incomingHasAttempt = incoming.hasPurchaseAttempt
            if (incoming.clientDeliverySource == null &&
                incoming.clientPurchaseAttemptStartedAt == null &&
                incoming.clientPurchaseAttemptId == null &&
                incoming.clientObservedAt == null
            ) {
                return false
            }
            if (existing.clientDeliverySource == null &&
                existing.clientPurchaseAttemptStartedAt == null &&
                existing.clientPurchaseAttemptId == null &&
                existing.clientObservedAt == null
            ) {
                return incomingHasAttempt || incoming.clientDeliverySource != "transaction_updates"
            }
            if (incomingHasAttempt && !existing.hasPurchaseAttempt) return true
            return incomingHasAttempt &&
                incoming.clientDeliverySource == "purchase_flow" &&
                existing.clientDeliverySource != "purchase_flow"
        }
    }

    private val lock = ReentrantLock()
    private val file: File = File(directory, "receipt_queue.json")
    private val corruptSidecarFile: File get() = File(file.parentFile, file.name + CORRUPT_SIDECAR_SUFFIX)
    private var items: MutableMap<String, AppActorReceiptQueueItem>? = null
    private var wiped = false
    private var rateLimitCooldownMillis: Long? = null
    private var cooldownLoaded: Boolean = false

    override fun upsert(item: AppActorReceiptQueueItem) {
        lock.withLock {
            val current = loadState()
            val updated = current.toMutableMap()
            val existing = current[item.key]
            updated[item.key] = if (existing == null) {
                item
            } else {
                val adoptClientContext = shouldAdoptClientPurchaseContext(existing, item)
                val appUserId = existing.ownerAfterSighting(item.appUserId)
                item.copy(
                    appUserId = appUserId,
                    productType = if (item.productType != AppActorProductType.Unknown.wireValue) item.productType else existing.productType,
                    orderId = item.orderId ?: existing.orderId,
                    basePlanId = item.basePlanId ?: existing.basePlanId,
                    offerId = item.offerId ?: existing.offerId,
                    priceAmountMicros = item.priceAmountMicros ?: existing.priceAmountMicros,
                    currencyCode = item.currencyCode ?: existing.currencyCode,
                    isAutoRenewing = item.isAutoRenewing ?: existing.isAutoRenewing,
                    obfuscatedAccountId = item.obfuscatedAccountId ?: existing.obfuscatedAccountId,
                    sourceIntent = mergeSourceIntent(existing, item),
                    rawPurchaseData = item.rawPurchaseData ?: existing.rawPurchaseData,
                    purchaseSignature = item.purchaseSignature ?: existing.purchaseSignature,
                    countryCode = item.countryCode ?: existing.countryCode,
                    clientPurchaseAttemptStartedAt = if (adoptClientContext) {
                        item.clientPurchaseAttemptStartedAt
                    } else {
                        existing.clientPurchaseAttemptStartedAt
                    },
                    clientObservedAt = if (adoptClientContext) item.clientObservedAt else existing.clientObservedAt,
                    clientDeliverySource = if (adoptClientContext) item.clientDeliverySource else existing.clientDeliverySource,
                    clientPurchaseAttemptId = if (adoptClientContext) item.clientPurchaseAttemptId else existing.clientPurchaseAttemptId,
                    placement = existing.placement ?: item.placement,
                    sdkOriginated = if (adoptClientContext) item.sdkOriginated else existing.sdkOriginated,
                    sdkVersion = if (adoptClientContext) item.sdkVersion else existing.sdkVersion,
                    offeringId = item.offeringId ?: existing.offeringId,
                    packageId = item.packageId ?: existing.packageId,
                    isAcknowledged = existing.isAcknowledged || item.isAcknowledged,
                    shouldAcknowledge = existing.shouldAcknowledge || item.shouldAcknowledge,
                    shouldConsume = existing.shouldConsume || item.shouldConsume,
                    finishedOnDevice = existing.finishedOnDevice || item.finishedOnDevice,
                    deadLetteredAtMillis = existing.deadLetteredAtMillis ?: item.deadLetteredAtMillis,
                    retryCount = existing.retryCount,
                    nextRetryAtMillis = existing.nextRetryAtMillis,
                    createdAtMillis = existing.createdAtMillis,
                    claimedAtMillis = if (appUserId != existing.appUserId) null else existing.claimedAtMillis,
                    phase = existing.phase,
                    lastError = existing.lastError,
                )
            }
            if (!persist(updated, rateLimitCooldownMillis)) {
                // Disk write failed — keep the in-memory state so the current
                // session can still process this item. It will be lost on restart.
                items = updated.toMutableMap()
                AppActorLogger.warn("[$TAG] Receipt queue persist failed on upsert for ${appActorPublicReceiptId(item.key)}; in-memory state updated, will be lost on restart")
            }
        }
    }

    override fun upsertAll(items: List<AppActorReceiptQueueItem>) {
        if (items.isEmpty()) return
        lock.withLock {
            val updated = loadState().toMutableMap()
            items.forEach { item -> updated[item.key] = item }
            if (!persist(updated, rateLimitCooldownMillis)) {
                this.items = updated.toMutableMap()
                AppActorLogger.warn("[$TAG] Receipt queue persist failed on upsertAll (${items.size} items); in-memory state updated, will be lost on restart")
            }
        }
    }

    override fun get(key: String): AppActorReceiptQueueItem? = lock.withLock {
        loadState()[key]
    }

    override fun claimReady(limit: Int, nowMillis: Long): List<AppActorReceiptQueueItem> = lock.withLock {
        val current = loadState()
        val updated = current.toMutableMap()
        val staleThresholdMillis = nowMillis - STALE_CLAIM_THRESHOLD_MILLIS
        val ready = mutableListOf<AppActorReceiptQueueItem>()

        current.entries.forEach { entry ->
            if (ready.size >= limit) return@forEach
            val item = entry.value
            val shouldClaim = when (item.phase) {
                AppActorReceiptQueuePhase.NeedsPost -> item.nextRetryAtMillis <= nowMillis
                AppActorReceiptQueuePhase.Posting -> (item.claimedAtMillis ?: 0L) <= staleThresholdMillis
                AppActorReceiptQueuePhase.NeedsFinish -> item.nextRetryAtMillis <= nowMillis
                AppActorReceiptQueuePhase.DeadLettered -> false
            }

            if (shouldClaim) {
                val claimed = item.copy(
                    phase = AppActorReceiptQueuePhase.Posting,
                    claimedAtMillis = nowMillis,
                    lastUpdatedAtMillis = nowMillis,
                )
                updated[entry.key] = claimed
                ready += claimed
            }
        }

        if (ready.isNotEmpty() && !persist(updated, rateLimitCooldownMillis)) {
            return emptyList()
        }
        return ready
    }

    override fun update(item: AppActorReceiptQueueItem) {
        lock.withLock {
            val updated = loadState().toMutableMap()
            updated[item.key] = item
            if (!persist(updated, rateLimitCooldownMillis)) {
                items = updated.toMutableMap()
                AppActorLogger.warn("[$TAG] Receipt queue persist failed on update for ${appActorPublicReceiptId(item.key)}; in-memory state updated, will be lost on restart")
            }
        }
    }

    override fun remove(key: String) {
        lock.withLock {
            val updated = loadState().toMutableMap()
            updated.remove(key)
            if (!persist(updated, rateLimitCooldownMillis)) {
                items = updated.toMutableMap()
                AppActorLogger.warn("[$TAG] Receipt queue persist failed on remove for ${appActorPublicReceiptId(key)}; in-memory state updated, will be lost on restart")
            }
        }
    }

    override fun clear() {
        lock.withLock {
            persist(
                map = linkedMapOf(),
                cooldownMillis = null,
            )
            // Drop any quarantined corrupt sidecar too, so an explicit clear
            // (logout/reset) leaves no queued receipt data behind (android-7).
            corruptSidecarFile.delete()
        }
    }

    override fun pendingCount(): Int = lock.withLock {
        loadState().values.count {
            it.phase == AppActorReceiptQueuePhase.NeedsPost ||
                it.phase == AppActorReceiptQueuePhase.Posting ||
                it.phase == AppActorReceiptQueuePhase.NeedsFinish
        }
    }

    override fun deadLetteredCount(): Int = lock.withLock {
        loadState().values.count { it.phase == AppActorReceiptQueuePhase.DeadLettered }
    }

    override fun snapshot(): List<AppActorReceiptQueueItem> = lock.withLock {
        loadState().values.toList()
    }

    override fun consumeDeadLettered(): List<AppActorReceiptQueueItem> = lock.withLock {
        val current = loadState()
        // Only consume dead-lettered items whose product type is resolved.
        // Items with productType "unknown" may still be revived by the payment
        // processor when offerings metadata becomes available.
        val consumable = current.values.filter {
            it.phase == AppActorReceiptQueuePhase.DeadLettered && !it.isRecoverableDeadLetter
        }
        if (consumable.isEmpty()) return emptyList()
        val updated = current.toMutableMap()
        consumable.forEach { updated.remove(it.key) }
        if (!persist(updated, rateLimitCooldownMillis)) {
            items = updated.toMutableMap()
            AppActorLogger.warn("[$TAG] Receipt queue persist failed on consumeDeadLettered (${consumable.size} items); in-memory state updated, will be lost on restart")
        }
        consumable
    }

    override fun wipe() {
        lock.withLock {
            wiped = true
            items = linkedMapOf()
            rateLimitCooldownMillis = null
            cooldownLoaded = true
            file.delete()
            // The quarantined sidecar too, so no queued (paid) receipt data is left (android-7).
            corruptSidecarFile.delete()
        }
    }

    override fun getRateLimitCooldownMillis(): Long? = lock.withLock {
        if (!cooldownLoaded) {
            loadState()
        }
        return rateLimitCooldownMillis
    }

    override fun setRateLimitCooldownMillis(value: Long?) {
        lock.withLock {
            persist(
                map = loadState().toMutableMap(),
                cooldownMillis = value,
            )
        }
    }

    private fun loadState(): MutableMap<String, AppActorReceiptQueueItem> {
        items?.let { return it }
        if (!file.exists()) {
            items = linkedMapOf()
            if (!cooldownLoaded) {
                rateLimitCooldownMillis = null
                cooldownLoaded = true
            }
            return items!!
        }

        val raw = runCatching { file.readText() }
            .onFailure { AppActorLogger.warn("[$TAG] Receipt queue read failed: ${it.message}") }
            .getOrNull()
        val persisted = raw?.let {
            runCatching {
                AppActorBackendJson.instance.decodeFromString<PersistedQueueState>(it)
            }.onFailure { AppActorLogger.warn("[$TAG] Receipt queue decode failed: ${it::class.java.simpleName}") }
                .getOrNull()
        }

        if (persisted == null) {
            quarantineCorruptFile()
            items = linkedMapOf()
            if (!cooldownLoaded) {
                rateLimitCooldownMillis = null
                cooldownLoaded = true
            }
            return items!!
        }

        val map = persisted.items
            .associateByTo(linkedMapOf()) { it.key }
        val normalized = purgeExpiredDeadLetteredItems(map)
        val finalMap = if (normalized != map && persist(normalized, persisted.rateLimitCooldownMillis)) {
            normalized
        } else {
            map
        }
        items = finalMap
        if (!cooldownLoaded) {
            rateLimitCooldownMillis = persisted.rateLimitCooldownMillis
            cooldownLoaded = true
        }
        return finalMap
    }

    /**
     * Moves an unparseable queue file aside instead of deleting it (android-7).
     * A single malformed or forward-incompatible record would otherwise wipe
     * every queued (paid) receipt. Quarantining to a `.corrupt` sidecar keeps
     * the bytes recoverable for diagnostics while the store starts fresh.
     */
    private fun quarantineCorruptFile() {
        val corruptFile = corruptSidecarFile
        val moved = runCatching {
            corruptFile.delete() // drop a stale quarantine from a previous failure
            file.renameTo(corruptFile)
        }.getOrDefault(false)
        if (moved) {
            AppActorLogger.warn("[$TAG] Receipt queue file unparseable; quarantined to ${corruptFile.name}")
        } else {
            // Rename can fail (e.g. cross-device or a locked target). Fall back to
            // deletion so loadState does not loop forever on the same bad file.
            runCatching { file.delete() }
            AppActorLogger.warn("[$TAG] Receipt queue file unparseable; quarantine failed, deleted instead")
        }
    }

    // Every launch revives and re-posts dead letters, which refreshes lastUpdatedAtMillis, so
    // retention counts from the first dead-lettering.
    private fun purgeExpiredDeadLetteredItems(
        source: LinkedHashMap<String, AppActorReceiptQueueItem>
    ): LinkedHashMap<String, AppActorReceiptQueueItem> {
        val cutoff = System.currentTimeMillis() - DEAD_LETTER_RETENTION_MILLIS
        val filtered = source.filterValues { item ->
            item.phase != AppActorReceiptQueuePhase.DeadLettered || item.deadLetterRetentionStartMillis >= cutoff
        }
        return if (filtered.size == source.size) {
            source
        } else {
            filtered.toMap(linkedMapOf())
        }
    }

    private fun persist(
        map: Map<String, AppActorReceiptQueueItem>,
        cooldownMillis: Long?,
    ): Boolean {
        if (wiped) return true
        val state = PersistedQueueState(
            items = map.values.toList(),
            rateLimitCooldownMillis = cooldownMillis,
        )
        val encoded = runCatching {
            AppActorBackendJson.instance.encodeToString(state)
        }.onFailure { AppActorLogger.warn("[$TAG] Receipt queue encode failed: ${it.message}") }
            .getOrNull() ?: return false
        file.parentFile?.mkdirs()
        val tempFile = File(file.parentFile, "${file.name}.tmp")
        val writeSucceeded = runCatching {
            tempFile.writeText(encoded)
            if (!tempFile.renameTo(file)) {
                // renameTo can fail when the destination already exists on some
                // filesystems. Delete the stale target and retry the atomic
                // rename rather than truncating the live file in place: an
                // in-place writeText that is interrupted by a crash or power
                // loss leaves a half-written, unparseable canonical file, which
                // the decode-failure path then deletes (losing all receipts).
                file.delete()
                if (!tempFile.renameTo(file)) {
                    tempFile.delete()
                    error("Receipt queue rename failed after deleting stale target")
                }
            }
        }.onFailure { AppActorLogger.warn("[$TAG] Receipt queue persist failed: ${it.message}") }
            .isSuccess
        if (writeSucceeded) {
            items = map.toMutableMap()
            rateLimitCooldownMillis = cooldownMillis
            cooldownLoaded = true
        }
        return writeSucceeded
    }

    @Serializable
    private data class PersistedQueueState(
        val items: List<AppActorReceiptQueueItem> = emptyList(),
        val rateLimitCooldownMillis: Long? = null,
    )
}
