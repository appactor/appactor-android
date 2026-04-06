package com.appactor.android.cache

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class AppActorCacheStoreTests {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `cache store saves and loads offerings payload`() {
        val diskStore = AppActorCacheDiskStore(context, tempDirectory("cache-save-load"))
        val manager = AppActorETagManager(diskStore = diskStore, responseVerificationEnabled = false)
        val store = AppActorOfferingsCacheStore(manager)

        store.save(
            payload = """{"hello":"world"}""",
            eTag = "\"etag_123\"",
            verified = true,
        )

        val cached = store.load()

        assertNotNull(cached)
        assertEquals("""{"hello":"world"}""", cached?.payload)
        assertEquals("\"etag_123\"", cached?.eTag)
    }

    @Test
    fun `etag manager ignores unverified cache when verification is required`() {
        val diskStore = AppActorCacheDiskStore(context, tempDirectory("cache-verification"))
        val manager = AppActorETagManager(diskStore = diskStore, responseVerificationEnabled = true)

        manager.storeFresh(
            resource = AppActorCacheResource.Offerings,
            payload = """{"hello":"world"}""",
            eTag = "\"etag_123\"",
            verified = false,
        )

        assertNull(manager.eTag(AppActorCacheResource.Offerings))
        assertNull(manager.cached(AppActorCacheResource.Offerings))
    }

    @Test
    fun `cache disk store deletes corrupt files`() {
        val directory = tempDirectory("cache-corrupt")
        val diskStore = AppActorCacheDiskStore(context, directory)
        val resource = AppActorCacheResource.Offerings
        File(directory, "${resource.cacheKey}.json").writeText("{not-json")

        val loaded = diskStore.load(resource)

        assertNull(loaded)
        assertFalse(File(directory, "${resource.cacheKey}.json").exists())
    }

    @Test
    fun `handle not modified rotates etag and updates freshness`() {
        val diskStore = AppActorCacheDiskStore(context, tempDirectory("cache-304"))
        val manager = AppActorETagManager(diskStore = diskStore, responseVerificationEnabled = false)

        manager.storeFresh(
            resource = AppActorCacheResource.Offerings,
            payload = """{"hello":"world"}""",
            eTag = "\"etag_old\"",
            verified = true,
        )

        Thread.sleep(5)
        val updated = manager.handleNotModified(
            resource = AppActorCacheResource.Offerings,
            rotatedETag = "\"etag_new\"",
        )

        assertEquals("\"etag_new\"", updated?.eTag)
        assertTrue(manager.isFresh(AppActorCacheResource.Offerings, ttlMillis = 1_000))
    }

    @Test
    fun `customer cache resource uses collision resistant hash key`() {
        val first = AppActorCacheResource.Customer("foo/bar").cacheKey
        val second = AppActorCacheResource.Customer("foo?bar").cacheKey

        assertNotEquals(first, second)
        assertTrue(first.startsWith("customer_"))
        assertTrue(second.startsWith("customer_"))
    }

    private fun tempDirectory(name: String): File {
        val directory = File(context.cacheDir, "tests/$name-${UUID.randomUUID()}")
        directory.mkdirs()
        return directory
    }
}
