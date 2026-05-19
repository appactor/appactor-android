package com.appactor.android.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class AppActorReceiptQueueStoreTests {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `queue store upserts claims and persists items`() {
        val directory = tempDirectory("queue-upsert")
        val store = AppActorAtomicJsonReceiptQueueStore(context, directory)
        val createdAt = 1_710_000_000_000L
        val item = queueItem(createdAtMillis = createdAt)

        store.upsert(item)
        val claimed = store.claimReady(limit = 10, nowMillis = createdAt + 1_000)

        assertEquals(1, claimed.size)
        assertEquals(AppActorReceiptQueuePhase.Posting, claimed.first().phase)

        val reloaded = AppActorAtomicJsonReceiptQueueStore(context, directory)
        assertEquals(1, reloaded.pendingCount())
        assertEquals(item.key, reloaded.snapshot().first().key)
    }

    @Test
    fun `queue store removes corrupt file and recovers cleanly`() {
        val directory = tempDirectory("queue-corrupt")
        File(directory, "receipt_queue.json").apply {
            parentFile?.mkdirs()
            writeText("{broken")
        }

        val store = AppActorAtomicJsonReceiptQueueStore(context, directory)

        assertTrue(store.snapshot().isEmpty())
        store.upsert(queueItem())
        assertEquals(1, store.pendingCount())
    }

    @Test
    fun `queue store tracks cooldown and dead letter count`() {
        val store = AppActorAtomicJsonReceiptQueueStore(context, tempDirectory("queue-cooldown"))
        val item = queueItem(phase = AppActorReceiptQueuePhase.DeadLettered)

        store.upsert(item)
        store.setRateLimitCooldownMillis(1_710_000_005_000L)

        assertEquals(1, store.deadLetteredCount())
        assertEquals(1_710_000_005_000L, store.getRateLimitCooldownMillis())
    }

    @Test
    fun `queue store persists google price snapshot across reload`() {
        val directory = tempDirectory("queue-price")
        val store = AppActorAtomicJsonReceiptQueueStore(context, directory)
        val item = queueItem().copy(
            priceAmountMicros = 4_990_000,
            currencyCode = "USD",
        )

        store.upsert(item)
        val reloaded = AppActorAtomicJsonReceiptQueueStore(context, directory)

        assertEquals(4_990_000L, reloaded.snapshot().single().priceAmountMicros)
        assertEquals("USD", reloaded.snapshot().single().currencyCode)
    }

    @Test
    fun `queue store decodes old items without price snapshot fields`() {
        val directory = tempDirectory("queue-old-price")
        File(directory, "receipt_queue.json").writeText(
            """
            {"items":[{"key":"google:com.appactor.pro.monthly:monthly001:token_123","appUserId":"user_android_123","packageName":"com.appactor.android","environment":"production","productId":"com.appactor.pro.monthly","productType":"subscription","purchaseToken":"token_123","purchaseTime":"1710000000000","purchaseState":"PURCHASED","basePlanId":"monthly001","idempotencyKey":"google:purchase:token_123","createdAtMillis":1710000000000,"lastUpdatedAtMillis":1710000000000,"phase":"NeedsPost"}],"rateLimitCooldownMillis":null}
            """.trimIndent()
        )

        val reloaded = AppActorAtomicJsonReceiptQueueStore(context, directory)

        assertEquals(1, reloaded.pendingCount())
        assertEquals(null, reloaded.snapshot().single().priceAmountMicros)
        assertEquals(null, reloaded.snapshot().single().currencyCode)
    }

    @Test
    fun `queue store preserves in memory state when disk persist fails`() {
        val brokenDirectory = brokenDirectory("queue-broken")
        val store = AppActorAtomicJsonReceiptQueueStore(context, brokenDirectory)

        val item = queueItem()
        store.upsert(item)

        // In-memory state is updated so the current session can still process
        // the item, even though disk write failed (it will be lost on restart).
        assertEquals(1, store.pendingCount())
        assertEquals(listOf(item.key), store.snapshot().map { it.key })
    }

    @Test
    fun `queue store purges expired dead lettered items on load`() {
        val directory = tempDirectory("queue-dead-letter-retention")
        val now = System.currentTimeMillis()
        val oldDeadLetter = queueItem(
            createdAtMillis = now - AppActorAtomicJsonReceiptQueueStore.DEAD_LETTER_RETENTION_MILLIS - 1_000,
            phase = AppActorReceiptQueuePhase.DeadLettered,
            purchaseToken = "token_old",
        ).copy(
            lastUpdatedAtMillis = now - AppActorAtomicJsonReceiptQueueStore.DEAD_LETTER_RETENTION_MILLIS - 1_000,
        )
        val freshPending = queueItem(
            createdAtMillis = now,
            phase = AppActorReceiptQueuePhase.NeedsPost,
            purchaseToken = "token_fresh",
        ).copy(lastUpdatedAtMillis = now)
        val first = AppActorAtomicJsonReceiptQueueStore(context, directory)
        first.upsert(oldDeadLetter)
        first.upsert(freshPending)

        val reloaded = AppActorAtomicJsonReceiptQueueStore(context, directory)

        assertEquals(listOf(freshPending.key), reloaded.snapshot().map { it.key })
        assertEquals(0, reloaded.deadLetteredCount())
        assertEquals(1, reloaded.pendingCount())
    }

    @Test
    fun `consumeDeadLettered removes resolved items and preserves unknown-type items`() {
        val store = AppActorAtomicJsonReceiptQueueStore(context, tempDirectory("queue-consume-dead"))
        val resolvedDead = queueItem(
            phase = AppActorReceiptQueuePhase.DeadLettered,
            purchaseToken = "token_resolved",
        )
        val unknownDead = queueItem(
            phase = AppActorReceiptQueuePhase.DeadLettered,
            purchaseToken = "token_unknown",
        ).copy(productType = "unknown")
        val pendingItem = queueItem(
            phase = AppActorReceiptQueuePhase.NeedsPost,
            purchaseToken = "token_pending",
        )

        store.upsert(resolvedDead)
        store.upsert(unknownDead)
        store.upsert(pendingItem)

        val consumed = store.consumeDeadLettered()

        assertEquals(1, consumed.size)
        assertEquals(resolvedDead.key, consumed.single().key)
        assertEquals(1, store.deadLetteredCount())
        assertEquals(1, store.pendingCount())
        assertEquals(unknownDead.key, store.snapshot().first { it.phase == AppActorReceiptQueuePhase.DeadLettered }.key)
    }

    @Test
    fun `consumeDeadLettered returns empty when no dead letters exist`() {
        val store = AppActorAtomicJsonReceiptQueueStore(context, tempDirectory("queue-consume-empty"))
        val pending = queueItem(phase = AppActorReceiptQueuePhase.NeedsPost)
        store.upsert(pending)

        val consumed = store.consumeDeadLettered()

        assertTrue(consumed.isEmpty())
        assertEquals(1, store.pendingCount())
    }

    @Test
    fun `consumeDeadLettered returns empty when only unknown-type dead letters exist`() {
        val store = AppActorAtomicJsonReceiptQueueStore(context, tempDirectory("queue-consume-unknown-only"))
        val unknownDead = queueItem(
            phase = AppActorReceiptQueuePhase.DeadLettered,
        ).copy(productType = "unknown")
        store.upsert(unknownDead)

        val consumed = store.consumeDeadLettered()

        assertTrue(consumed.isEmpty())
        assertEquals(1, store.deadLetteredCount())
    }

    @Test
    fun `upsert preserves original source intent across retries`() {
        val store = AppActorAtomicJsonReceiptQueueStore(context, tempDirectory("queue-source-intent"))
        val purchaseItem = queueItem().copy(sourceIntent = "purchase")
        store.upsert(purchaseItem)

        val restoreRetry = purchaseItem.copy(sourceIntent = "restore", lastUpdatedAtMillis = purchaseItem.lastUpdatedAtMillis + 1)
        store.upsert(restoreRetry)

        assertEquals("purchase", store.get(purchaseItem.key)?.sourceIntent)
    }

    @Test
    fun `upsert upgrades queue source intent when explicit purchase arrives`() {
        val store = AppActorAtomicJsonReceiptQueueStore(context, tempDirectory("queue-source-intent-upgrade"))
        val queueItem = queueItem().copy(sourceIntent = "queue")
        store.upsert(queueItem)

        val purchaseRetry = queueItem.copy(sourceIntent = "purchase", lastUpdatedAtMillis = queueItem.lastUpdatedAtMillis + 1)
        store.upsert(purchaseRetry)

        assertEquals("purchase", store.get(queueItem.key)?.sourceIntent)
    }

    @Test
    fun `upsert does not modernize legacy purchase item with contextless transaction update`() {
        val store = AppActorAtomicJsonReceiptQueueStore(context, tempDirectory("queue-legacy-contextless-update"))
        val legacyPurchase = queueItem().copy(sourceIntent = "purchase")
        store.upsert(legacyPurchase)

        val liveUpdate = legacyPurchase.copy(
            sourceIntent = "queue",
            clientObservedAt = "2024-03-09T16:10:00Z",
            clientDeliverySource = "transaction_updates",
            sdkOriginated = true,
            sdkVersion = "9.9.9",
            lastUpdatedAtMillis = legacyPurchase.lastUpdatedAtMillis + 1,
        )
        store.upsert(liveUpdate)

        val stored = store.get(legacyPurchase.key)
        assertEquals("purchase", stored?.sourceIntent)
        assertNull(stored?.clientObservedAt)
        assertNull(stored?.clientDeliverySource)
        assertNull(stored?.sdkOriginated)
        assertNull(stored?.sdkVersion)
    }

    private fun queueItem(
        createdAtMillis: Long = System.currentTimeMillis(),
        phase: AppActorReceiptQueuePhase = AppActorReceiptQueuePhase.NeedsPost,
        purchaseToken: String = "token_123",
    ): AppActorReceiptQueueItem {
        return AppActorReceiptQueueItem(
            key = AppActorReceiptQueueItem.makeKey(purchaseToken, "com.appactor.pro.monthly", "monthly001"),
            appUserId = "user_android_123",
            packageName = "com.appactor.android",
            environment = "production",
            productId = "com.appactor.pro.monthly",
            productType = "subscription",
            purchaseToken = purchaseToken,
            purchaseTime = "1710000000000",
            purchaseState = "PURCHASED",
            basePlanId = "monthly001",
            idempotencyKey = "google:purchase:$purchaseToken",
            createdAtMillis = createdAtMillis,
            lastUpdatedAtMillis = createdAtMillis,
            phase = phase,
        )
    }

    private fun tempDirectory(name: String): File {
        val directory = File(context.filesDir, "tests/$name-${UUID.randomUUID()}")
        directory.mkdirs()
        return directory
    }

    private fun brokenDirectory(name: String): File {
        val file = File(context.filesDir, "tests/$name-${UUID.randomUUID()}")
        file.parentFile?.mkdirs()
        file.writeText("not-a-directory")
        assertFalse(file.isDirectory)
        return file
    }
}
