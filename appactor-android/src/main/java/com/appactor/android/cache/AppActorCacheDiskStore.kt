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

    fun clearAll() = lock.withLock {
        directory.deleteRecursively()
    }

    fun clearAllUnverified() = lock.withLock {
        val files = directory.listFiles().orEmpty()
        files.filter { it.extension == "json" }.forEach { file ->
            val entry = runCatching {
                AppActorBackendJson.instance.decodeFromString<AppActorCacheEntry>(file.readText())
            }.onFailure { AppActorLogger.warn("[$TAG] Cache entry decode failed during cleanup: ${it.message}") }
                .getOrNull()
            if (entry == null || !entry.responseVerified) {
                file.delete()
            }
        }
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
