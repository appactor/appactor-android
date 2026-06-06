package com.appactor.android.storage

import android.content.Context
import com.appactor.android.backend.client.AppActorBackendJson
import com.appactor.android.internal.logging.AppActorLogger
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal interface AppActorPostedLedgerStore {
    fun isPosted(key: String): Boolean
    fun markPosted(key: String, postedAtMillis: Long = System.currentTimeMillis())
    fun purgeExpired(olderThanMillis: Long)
    fun clear()
    fun snapshot(): Map<String, Long>
}

internal class AppActorAtomicJsonPostedLedgerStore(
    context: Context,
    directory: File = File(context.filesDir, "appactor"),
) : AppActorPostedLedgerStore {

    private val lock = ReentrantLock()
    private val file: File = File(directory, "posted_ledger.json")
    private var ledger: MutableMap<String, Long>? = null

    override fun isPosted(key: String): Boolean = lock.withLock {
        loadLedger().containsKey(key)
    }

    override fun markPosted(key: String, postedAtMillis: Long) {
        lock.withLock {
            val updated = loadLedger().toMutableMap()
            updated[key] = postedAtMillis
            if (updated.size > MAX_LEDGER_ENTRIES) {
                val newest = updated.entries.sortedByDescending { it.value }.take(MAX_LEDGER_ENTRIES)
                updated.clear()
                newest.forEach { (ledgerKey, value) -> updated[ledgerKey] = value }
            }
            persist(updated)
        }
    }

    override fun purgeExpired(olderThanMillis: Long) {
        lock.withLock {
            val updated = loadLedger().toMutableMap()
            val cutoff = System.currentTimeMillis() - olderThanMillis
            val iterator = updated.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.value < cutoff) {
                    iterator.remove()
                }
            }
            persist(updated)
        }
    }

    override fun clear() {
        lock.withLock {
            persist(linkedMapOf())
        }
    }

    override fun snapshot(): Map<String, Long> = lock.withLock {
        loadLedger().toMap()
    }

    private fun loadLedger(): MutableMap<String, Long> {
        ledger?.let { return it }
        if (!file.exists()) {
            ledger = linkedMapOf()
            return ledger!!
        }

        val raw = runCatching { file.readText() }
            .onFailure { AppActorLogger.warn("[$TAG] Posted ledger read failed: ${it.message}") }
            .getOrNull()
        val persisted = raw?.let {
            runCatching {
                AppActorBackendJson.instance.decodeFromString<PersistedLedgerState>(it)
            }.onFailure { AppActorLogger.warn("[$TAG] Posted ledger decode failed: ${it.message}") }
                .getOrNull()
        }

        if (persisted == null) {
            file.delete()
            ledger = linkedMapOf()
            return ledger!!
        }

        val normalized = normalizeLedger(persisted.entries)
        val finalEntries = if (normalized != persisted.entries && persist(normalized)) {
            normalized
        } else {
            persisted.entries
        }
        ledger = finalEntries.toMutableMap()
        return ledger!!
    }

    private fun normalizeLedger(entries: Map<String, Long>): Map<String, Long> {
        val cutoff = System.currentTimeMillis() - LEDGER_RETENTION_MILLIS
        val filtered = entries
            .filterValues { postedAtMillis -> postedAtMillis >= cutoff }
            .entries
            .sortedByDescending { it.value }
            .take(MAX_LEDGER_ENTRIES)
        return linkedMapOf<String, Long>().apply {
            filtered.forEach { entry -> put(entry.key, entry.value) }
        }
    }

    private fun persist(entries: Map<String, Long>): Boolean {
        val encoded = runCatching {
            AppActorBackendJson.instance.encodeToString(
                PersistedLedgerState(entries = entries)
            )
        }.onFailure { AppActorLogger.warn("[$TAG] Posted ledger encode failed: ${it.message}") }
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
                // the decode-failure path then deletes (losing the ledger).
                file.delete()
                if (!tempFile.renameTo(file)) {
                    tempFile.delete()
                    error("Posted ledger rename failed after deleting stale target")
                }
            }
        }.onFailure { AppActorLogger.warn("[$TAG] Posted ledger persist failed: ${it.message}") }
            .isSuccess
        if (writeSucceeded) {
            ledger = entries.toMutableMap()
        }
        return writeSucceeded
    }

    @Serializable
    private data class PersistedLedgerState(
        val entries: Map<String, Long> = emptyMap(),
    )

    companion object {
        private const val TAG = "PostedLedgerStore"
        const val LEDGER_RETENTION_MILLIS: Long = 90L * 24 * 60 * 60 * 1_000
        const val MAX_LEDGER_ENTRIES: Int = 5_000
        fun deletePersistedFile(context: Context) {
            val file = File(File(context.filesDir, "appactor"), "posted_ledger.json")
            file.delete()
        }
    }
}
