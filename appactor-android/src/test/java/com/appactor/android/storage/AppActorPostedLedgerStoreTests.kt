package com.appactor.android.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class AppActorPostedLedgerStoreTests {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `posted ledger persists duplicate markers`() {
        val directory = tempDirectory("ledger-persist")
        val first = AppActorAtomicJsonPostedLedgerStore(context, directory)
        val postedAtMillis = System.currentTimeMillis()

        first.markPosted("google:purchase:token_123", postedAtMillis = postedAtMillis)

        val second = AppActorAtomicJsonPostedLedgerStore(context, directory)
        assertTrue(second.isPosted("google:purchase:token_123"))
        assertEquals(1, second.snapshot().size)
    }

    @Test
    fun `posted ledger purges expired entries`() {
        val store = AppActorAtomicJsonPostedLedgerStore(context, tempDirectory("ledger-purge"))
        val oldTime = System.currentTimeMillis() - 10_000
        val freshTime = System.currentTimeMillis()
        store.markPosted("old_key", postedAtMillis = oldTime)
        store.markPosted("fresh_key", postedAtMillis = freshTime)

        store.purgeExpired(olderThanMillis = 5_000)

        assertFalse(store.isPosted("old_key"))
        assertTrue(store.isPosted("fresh_key"))
    }

    @Test
    fun `posted ledger does not keep in memory markers when disk persist fails`() {
        val store = AppActorAtomicJsonPostedLedgerStore(context, brokenDirectory("ledger-broken"))

        store.markPosted("google:purchase:token_123", postedAtMillis = 1_710_000_000_000L)

        assertFalse(store.isPosted("google:purchase:token_123"))
        assertTrue(store.snapshot().isEmpty())
    }

    @Test
    fun `posted ledger keeps prior markers intact when atomic write fails`() {
        val directory = tempDirectory("ledger-atomic-fail")
        val store = AppActorAtomicJsonPostedLedgerStore(context, directory)
        store.markPosted("google:purchase:token_a", postedAtMillis = 1_710_000_000_000L)

        // Pre-create a directory at the temp-file target so the next atomic
        // write cannot truncate the canonical file: tempFile.writeText fails,
        // persist reports failure, and the canonical posted_ledger.json keeps
        // the previously persisted marker rather than becoming half-written.
        assertTrue(File(directory, "posted_ledger.json.tmp").mkdirs())

        store.markPosted("google:purchase:token_b", postedAtMillis = 1_710_000_005_000L)

        // The failed write must not advance in-memory state with the new marker.
        assertTrue(store.isPosted("google:purchase:token_a"))
        assertFalse(store.isPosted("google:purchase:token_b"))

        // The canonical file is not corrupted: the prior marker still loads.
        val reloaded = AppActorAtomicJsonPostedLedgerStore(context, directory)
        assertTrue(reloaded.isPosted("google:purchase:token_a"))
        assertFalse(reloaded.isPosted("google:purchase:token_b"))
        assertEquals(1, reloaded.snapshot().size)
    }

    @Test
    fun `posted ledger purges expired entries on load`() {
        val directory = tempDirectory("ledger-load-retention")
        val oldTime = System.currentTimeMillis() - AppActorAtomicJsonPostedLedgerStore.LEDGER_RETENTION_MILLIS - 1_000
        val freshTime = System.currentTimeMillis()
        val first = AppActorAtomicJsonPostedLedgerStore(context, directory)
        first.markPosted("old_key", postedAtMillis = oldTime)
        first.markPosted("fresh_key", postedAtMillis = freshTime)

        val reloaded = AppActorAtomicJsonPostedLedgerStore(context, directory)

        assertFalse(reloaded.isPosted("old_key"))
        assertTrue(reloaded.isPosted("fresh_key"))
        assertEquals(1, reloaded.snapshot().size)
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
        return file
    }
}
