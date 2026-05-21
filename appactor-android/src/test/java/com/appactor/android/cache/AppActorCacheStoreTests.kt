package com.appactor.android.cache

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.appactor.android.models.AppActorVerificationResult
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
import java.util.Locale
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
    fun `offerings cache locale safe lookups reject mismatched locales`() {
        val diskStore = AppActorCacheDiskStore(context, tempDirectory("cache-locale-safe"))
        val manager = AppActorETagManager(diskStore = diskStore, responseVerificationEnabled = false)
        val store = AppActorOfferingsCacheStore(manager)

        store.save(
            payload = """{"hello":"world"}""",
            eTag = "\"etag_123\"",
            verified = true,
            preferredLocales = listOf("en-US"),
        )

        assertNotNull(store.loadLocaleCompatible(listOf("en-US")))
        assertNotNull(store.eTag(currentLocales = listOf("en-US")))
        assertNull(store.loadLocaleCompatible(listOf("tr-TR")))
        assertNull(store.eTag(currentLocales = listOf("tr-TR")))
    }

    @Test
    fun `legacy offerings cache payloads remain readable for locale safe lookups`() {
        val diskStore = AppActorCacheDiskStore(context, tempDirectory("cache-locale-legacy"))
        val manager = AppActorETagManager(diskStore = diskStore, responseVerificationEnabled = false)
        val store = AppActorOfferingsCacheStore(manager)

        manager.storeFresh(
            resource = AppActorCacheResource.Offerings,
            payload = """{"hello":"world"}""",
            eTag = "\"etag_legacy\"",
            verified = true,
        )

        assertNotNull(store.loadLocaleCompatible(listOf(Locale.getDefault().toLanguageTag())))
        assertNotNull(store.eTag(currentLocales = listOf(Locale.getDefault().toLanguageTag())))
    }

    @Test
    fun `not requested entries are served when verification is enabled`() {
        val diskStore = AppActorCacheDiskStore(context, tempDirectory("cache-not-requested"))
        val manager = AppActorETagManager(diskStore = diskStore, responseVerificationEnabled = true)

        manager.storeFresh(
            resource = AppActorCacheResource.Offerings,
            payload = """{"hello":"world"}""",
            eTag = "\"etag_123\"",
            verified = false,
        )

        assertNotNull(
            "NotRequested entries should be served (transitional)",
            manager.eTag(AppActorCacheResource.Offerings),
        )
        assertNotNull(
            "NotRequested entries should be served (transitional)",
            manager.cached(AppActorCacheResource.Offerings),
        )
    }

    @Test
    fun `failed entries are rejected when verification is enabled`() {
        val directory = tempDirectory("cache-failed")
        val diskStore = AppActorCacheDiskStore(context, directory)
        val manager = AppActorETagManager(diskStore = diskStore, responseVerificationEnabled = true)

        diskStore.save(
            entry = AppActorCacheEntry(
                payload = """{"hello":"world"}""",
                eTag = "\"etag_123\"",
                cachedAtMillis = System.currentTimeMillis(),
                responseVerified = false,
                verificationStatus = AppActorVerificationResult.Failed,
            ),
            resource = AppActorCacheResource.Offerings,
        )

        assertNull("Failed entries should be rejected", manager.eTag(AppActorCacheResource.Offerings))
        assertNull("Failed entries should be rejected", manager.cached(AppActorCacheResource.Offerings))
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

    @Test
    fun `legacy cache entry without verificationStatus falls back to responseVerified`() {
        val directory = tempDirectory("cache-legacy")
        val diskStore = AppActorCacheDiskStore(context, directory)
        val resource = AppActorCacheResource.Offerings

        val legacyJson = """{"payload":"{\"data\":{}}","eTag":"\"etag_legacy\"","cachedAtMillis":${System.currentTimeMillis()},"responseVerified":true}"""
        directory.mkdirs()
        File(directory, "${resource.cacheKey}.json").writeText(legacyJson)

        val entry = diskStore.load(resource)

        assertNotNull(entry)
        assertNull("Legacy entry should have null verificationStatus", entry?.verificationStatus)
        assertEquals(AppActorVerificationResult.Verified, entry?.resolvedStatus)
    }

    @Test
    fun `legacy cache entry with responseVerified false resolves to Failed`() {
        val directory = tempDirectory("cache-legacy-false")
        val diskStore = AppActorCacheDiskStore(context, directory)
        val resource = AppActorCacheResource.Offerings

        val legacyJson = """{"payload":"{\"data\":{}}","eTag":"\"etag_legacy\"","cachedAtMillis":${System.currentTimeMillis()},"responseVerified":false}"""
        directory.mkdirs()
        File(directory, "${resource.cacheKey}.json").writeText(legacyJson)

        val entry = diskStore.load(resource)

        assertNotNull(entry)
        assertEquals(AppActorVerificationResult.Failed, entry?.resolvedStatus)
    }

    @Test
    fun `clearAllUnverified deletes Failed but keeps NotRequested and Verified`() {
        val directory = tempDirectory("cache-clear-unverified")
        val diskStore = AppActorCacheDiskStore(context, directory)
        val manager = AppActorETagManager(diskStore = diskStore, responseVerificationEnabled = true)

        manager.storeFresh(
            resource = AppActorCacheResource.Offerings,
            payload = """{"verified":"true"}""",
            eTag = "\"etag_v\"",
            verified = true,
        )

        manager.storeFresh(
            resource = AppActorCacheResource.RemoteConfigs("user1"),
            payload = """{"not_requested":"true"}""",
            eTag = "\"etag_nr\"",
            verified = false,
        )

        diskStore.save(
            entry = AppActorCacheEntry(
                payload = """{"failed":"true"}""",
                eTag = "\"etag_f\"",
                cachedAtMillis = System.currentTimeMillis(),
                responseVerified = false,
                verificationStatus = AppActorVerificationResult.Failed,
            ),
            resource = AppActorCacheResource.Customer("failed_user"),
        )

        manager.clearUnverifiedIfNeeded()

        assertNotNull("Verified entry should survive", diskStore.load(AppActorCacheResource.Offerings))
        assertNotNull("NotRequested entry should survive", diskStore.load(AppActorCacheResource.RemoteConfigs("user1")))
        assertNull("Failed entry should be deleted", diskStore.load(AppActorCacheResource.Customer("failed_user")))
    }

    private fun tempDirectory(name: String): File {
        val directory = File(context.cacheDir, "tests/$name-${UUID.randomUUID()}")
        directory.mkdirs()
        return directory
    }
}
