package com.appactor.android.cache

import android.content.Context
import com.appactor.android.backend.client.AppActorBackendJson
import com.appactor.android.internal.logging.AppActorLogger
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class AppActorCacheDiskStore(
    context: Context,
    directory: File = File(context.cacheDir, "appactor/http-cache"),
) {

    private companion object {
        private const val TAG = "CacheDiskStore"
    }

    private val lock = ReentrantLock()
    private val directory: File = directory
    private var wiped = false

    fun load(resource: AppActorCacheResource): AppActorCacheEntry? = lock.withLock {
        val file = fileFor(resource)
        if (!file.exists()) return null

        val raw = runCatching { file.readText() }
            .onFailure { AppActorLogger.warn("[$TAG] Cache read failed: ${it.message}") }
            .getOrNull() ?: return null
        val decoded = runCatching {
            AppActorBackendJson.instance.decodeFromString<AppActorCacheEntry>(raw)
        }.onFailure { AppActorLogger.warn("[$TAG] Cache decode failed: ${it.message}") }
            .getOrNull()

        if (decoded == null) {
            file.delete()
        }
        return decoded
    }

    fun save(
        entry: AppActorCacheEntry,
        resource: AppActorCacheResource,
    ) = lock.withLock {
        if (wiped) return@withLock
        ensureDirectory()
        val encoded = runCatching {
            AppActorBackendJson.instance.encodeToString(entry)
        }.onFailure { AppActorLogger.warn("[$TAG] Cache encode failed: ${it.message}") }
            .getOrNull() ?: return

        val targetFile = fileFor(resource)
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
        runCatching {
            tempFile.writeText(encoded)
            if (!tempFile.renameTo(targetFile)) {
                targetFile.writeText(encoded)
                tempFile.delete()
            }
        }.onFailure { AppActorLogger.warn("[$TAG] Cache write failed: ${it.message}") }
        Unit
    }

    fun updateTimestamp(
        resource: AppActorCacheResource,
        rotatedETag: String? = null,
    ): AppActorCacheEntry? = lock.withLock {
        val entry = load(resource) ?: return null
        val updated = entry.copy(
            eTag = rotatedETag ?: entry.eTag,
            cachedAtMillis = System.currentTimeMillis(),
        )
        save(updated, resource)
        return updated
    }

    fun resetFreshness(resource: AppActorCacheResource) = lock.withLock {
        val entry = load(resource) ?: return
        val stale = entry.copy(cachedAtMillis = 0L)
        save(stale, resource)
    }

    fun clear(resource: AppActorCacheResource) = lock.withLock {
        fileFor(resource).delete()
    }

    fun clearPrefix(prefix: String) = lock.withLock {
        directory.listFiles().orEmpty()
            .filter { file -> file.extension == "json" && file.name.startsWith(prefix) }
            .forEach { file -> file.delete() }
    }

    /**
     * Deletes the whole cache for good, for reset(): later saves through this instance are
     * dropped, so a fetch of the ended session still running can't write its user's data back.
     */
    fun wipe() = lock.withLock {
        wiped = true
        directory.deleteRecursively()
    }

    /**
     * Removes every entry that doesn't hold a verified response: failed ones, and the unverified
     * offerings and remote-config entries older versions stored from unsigned responses. The
     * offline product catalog is derived from the offerings, so it goes with them.
     */
    fun clearAllUnverified() = lock.withLock {
        var offeringsPurged = false
        val files = directory.listFiles().orEmpty()
        files.filter { it.extension == "json" }.forEach { file ->
            val entry = runCatching {
                AppActorBackendJson.instance.decodeFromString<AppActorCacheEntry>(file.readText())
            }.onFailure { AppActorLogger.warn("[$TAG] Cache entry decode failed during cleanup: ${it.message}") }
                .getOrNull()
            if (entry == null || !entry.resolvedStatus.isVerified) {
                if (file.nameWithoutExtension == AppActorCacheResource.Offerings.cacheKey) offeringsPurged = true
                file.delete()
            }
        }
        if (offeringsPurged) fileFor(AppActorCacheResource.OfflineProductCatalog).delete()
    }

    private fun fileFor(resource: AppActorCacheResource): File {
        return File(directory, "${resource.cacheKey}.json")
    }

    private fun ensureDirectory() {
        if (!directory.exists()) {
            directory.mkdirs()
        }
    }
}
