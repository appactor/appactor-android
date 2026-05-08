package com.appactor.android.api

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.appactor.android.cache.AppActorCacheDiskStore
import com.appactor.android.cache.AppActorCacheEntry
import com.appactor.android.cache.AppActorCacheResource
import com.appactor.android.models.AppActorConfiguration
import com.appactor.android.models.AppActorOptions
import com.appactor.android.models.AppActorOffering
import com.appactor.android.models.AppActorOfferings
import com.appactor.android.models.AppActorPackage
import com.appactor.android.models.AppActorPackageType
import com.appactor.android.models.AppActorPlatformInfo
import com.appactor.android.storage.AppActorAtomicJsonPostedLedgerStore
import com.appactor.android.storage.AppActorAtomicJsonReceiptQueueStore
import com.appactor.android.models.toLegacyOptions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class AppActorConfigureTests {

    @Before
    fun setUp() {
        resetApiTestState()
    }

    @Test
    fun `configure ignores repeated calls until reset`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        stubBackend().use { backend ->
            AppActor.configure(
                AppActorConfiguration(
                    context = context,
                    apiKey = "pk_test_123",
                    appUserId = "user_a",
                    baseUrl = backend.baseUrl,
                    options = testOptionsForLocalBackend(),
                )
            )

            AppActor.configure(
                AppActorConfiguration(
                    context = context,
                    apiKey = "pk_test_456",
                    appUserId = "user_b",
                    baseUrl = backend.baseUrl,
                    options = testOptionsForLocalBackend(),
                )
            )

            assertEquals("user_a", AppActor.appUserId)
        }
    }

    @Test
    fun `shared exposes singleton style api surface`() {
        assertTrue(AppActor.shared === AppActor)
    }

    @Test
    fun `is anonymous defaults true before configure`() {
        assertTrue(AppActor.isAnonymous)
        assertNull(AppActor.appUserId)
    }

    @Test
    fun `offerings lookup key helper finds offering`() {
        val mainOffering = AppActorOffering(
            id = "off_main_android",
            displayName = "Main",
            isCurrent = true,
            lookupKey = "main",
        )
        val offerings = AppActorOfferings(
            current = mainOffering,
            all = mapOf(mainOffering.id to mainOffering),
        )

        assertEquals(mainOffering, offerings.offering("off_main_android"))
        assertEquals(mainOffering, offerings.offeringByLookupKey("main"))
    }

    @Test
    fun `offering convenience helpers expose package lookups`() {
        val monthlyPackage = AppActorPackage(
            id = "monthly",
            packageType = AppActorPackageType.Monthly,
            store = com.appactor.android.models.AppActorStore.PlayStore,
            productId = "monthly_sku",
        )
        val offering = AppActorOffering(
            id = "off_main_android",
            packages = listOf(monthlyPackage),
        )

        assertEquals("off_main_android", offering.displayName)
        assertEquals(monthlyPackage, offering.monthly)
        assertEquals(monthlyPackage, offering.packageFor(AppActorPackageType.Monthly))
        assertNull(offering.annual)
    }

    @Test
    fun `configuration stores application context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val configuration = AppActorConfiguration(
            context = context,
            apiKey = "pk_test_123",
        )

        assertEquals(context.applicationContext, configuration.applicationContext)
    }

    @Test
    fun `app actor options configure seeds anonymous identity with parity defaults`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        stubBackend().use { backend ->
            AppActor.configure(
                AppActorConfiguration(
                    context = context,
                    apiKey = "pk_test_options",
                    baseUrl = backend.baseUrl,
                    options = testOptionsForLocalBackend(),
                )
            )

            assertTrue(AppActor.shared.isAnonymous)
            assertTrue(AppActor.shared.appUserId?.startsWith("appactor-anon-") == true)
        }
    }

    @Test
    fun `app actor options expose wrapper safe startup knobs while preserving defaults`() {
        val defaults = AppActorOptions().toLegacyOptions()
        assertNull(defaults.platformInfo)

        val customized = AppActorOptions(
            platformInfo = AppActorPlatformInfo("flutter", "0.1.1"),
        ).toLegacyOptions()

        assertEquals(AppActorPlatformInfo("flutter", "0.1.1"), customized.platformInfo)
    }

    @Test
    fun `configure debug event is emitted through log handler`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val logs = mutableListOf<Triple<String, String, String>>()
        AppActor.setLogHandler { level, message, category, _ ->
            logs += Triple(level, message, category)
        }
        try {
            stubBackend().use { backend ->
                AppActor.configure(
                    AppActorConfiguration(
                        context = context,
                        apiKey = "pk_test_log_handler",
                        appUserId = "user_log_handler",
                        baseUrl = backend.baseUrl,
                        options = testOptionsForLocalBackend(),
                    )
                )
            }
        } finally {
            AppActor.setLogHandler(null)
        }

        assertTrue(
            logs.any { (level, message, category) ->
                level == "info" &&
                    category == "Lifecycle" &&
                    message.contains("configured") &&
                    message.contains("AppActor configured.")
            }
        )
        assertFalse(logs.any { (_, message, _) -> message.contains("user_log_handler") })
    }

    @Test
    fun `configure seeds explicit app user id into identity storage`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("appactor_identity", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()

        stubBackend().use { backend ->
            AppActor.configure(
                AppActorConfiguration(
                    context = context,
                    apiKey = "pk_test_123",
                    appUserId = "user_android_123",
                    baseUrl = backend.baseUrl,
                    options = testOptionsForLocalBackend(),
                )
            )

            assertEquals("user_android_123", preferences.getString("appactor_billing_app_user_id", null))
            assertNotNull(preferences.getString("appactor_billing_install_id", null))
        }
    }

    @Test
    fun `configure preserves explicit non empty app user id formatting`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("appactor_identity", Context.MODE_PRIVATE)
        val explicitAppUserId = " user_android_123 "
        preferences.edit().clear().commit()

        stubBackend().use { backend ->
            AppActor.configure(
                AppActorConfiguration(
                    context = context,
                    apiKey = "pk_test_123",
                    appUserId = explicitAppUserId,
                    baseUrl = backend.baseUrl,
                    options = testOptionsForLocalBackend(),
                )
            )

            assertEquals(explicitAppUserId, AppActor.appUserId)
            assertEquals(explicitAppUserId, preferences.getString("appactor_billing_app_user_id", null))
        }
    }

    @Test
    fun `configure clears stale unverified cache when verification is enabled`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val diskStore = AppActorCacheDiskStore(context)
        diskStore.save(
            entry = AppActorCacheEntry(
                payload = """{"offerings":[]}""",
                eTag = "\"etag_123\"",
                cachedAtMillis = System.currentTimeMillis(),
                responseVerified = false,
            ),
            resource = AppActorCacheResource.Offerings,
        )

        stubBackend().use { backend ->
            AppActor.configure(
                AppActorConfiguration(
                    context = context,
                    apiKey = "pk_test_123",
                    baseUrl = backend.baseUrl,
                    options = AppActorConfiguration.Options(
                        verifyResponseSignatures = true,
                        requireResponseSignatures = false,
                    ),
                )
            )

            assertNull(AppActorCacheDiskStore(context).load(AppActorCacheResource.Offerings))
        }
    }

    @Test
    fun `reset clears persisted identity cache queue and ledger state`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("appactor_identity", Context.MODE_PRIVATE)

        stubBackend().use { backend ->
            AppActor.configure(
                AppActorConfiguration(
                    context = context,
                    apiKey = "pk_test_123",
                    appUserId = "user_android_123",
                    baseUrl = backend.baseUrl,
                    options = testOptionsForLocalBackend(),
                )
            )
        }

        preferences.edit()
            .putString("appactor_billing_server_user_id", "server_user_123")
            .putString("appactor_billing_last_request_id", "req_123")
            .commit()

        AppActorCacheDiskStore(context).save(
            entry = AppActorCacheEntry(
                payload = """{"customer":{}}""",
                eTag = "\"etag_customer\"",
                cachedAtMillis = System.currentTimeMillis(),
                responseVerified = true,
            ),
            resource = AppActorCacheResource.Customer("user_android_123"),
        )
        AppActorAtomicJsonReceiptQueueStore(context).upsert(queueItem())
        AppActorAtomicJsonPostedLedgerStore(context).markPosted("google:purchase:token_123")

        AppActor.reset()

        assertNull(preferences.getString("appactor_billing_app_user_id", null))
        assertNull(preferences.getString("appactor_billing_server_user_id", null))
        assertNull(preferences.getString("appactor_billing_last_request_id", null))
        assertNotNull(preferences.getString("appactor_billing_install_id", null))
        assertTrue(AppActorAtomicJsonReceiptQueueStore(context).snapshot().isEmpty())
        assertTrue(AppActorAtomicJsonPostedLedgerStore(context).snapshot().isEmpty())
        assertFalse(File(context.cacheDir, "appactor/http-cache").exists())
    }

    @Test
    fun `configure clears legacy server user id state`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("appactor_identity", Context.MODE_PRIVATE)
        preferences.edit()
            .putString("appactor_billing_server_user_id", "legacy_server_user_123")
            .commit()

        stubBackend().use { backend ->
            AppActor.configure(
                AppActorConfiguration(
                    context = context,
                    apiKey = "pk_test_123",
                    appUserId = "user_android_123",
                    baseUrl = backend.baseUrl,
                    options = testOptionsForLocalBackend(),
                )
            )
        }

        assertNull(preferences.getString("appactor_billing_server_user_id", null))
    }

}
