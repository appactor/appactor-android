package com.appactor.android.billing

import com.appactor.android.backend.client.AppActorBackendClient
import com.appactor.android.backend.client.AppActorBackendException
import com.appactor.android.backend.client.AppActorBackendHttpResponse
import com.appactor.android.backend.dto.AppActorAttributionRequestDTO
import com.appactor.android.managers.AppActorAttributesManager
import com.appactor.android.storage.AppActorAttributeQueueStore
import com.appactor.android.storage.AppActorIdentityStore
import com.appactor.android.storage.AppActorQueuedAttributeMutation
import io.mockk.coEvery
import io.mockk.slot
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppActorInstallReferrerManagerTests {

    @Test
    fun `fetchReferrerOnce returns hashed cached referrer without connecting`() = runBlocking {
        val store = mockk<AppActorIdentityStore>(relaxed = true)
        every { store.installReferrer } returns "sha256:existing"
        every { store.currentAppUserId } returns "user_123"
        every { store.installId } returns "install_123"

        val manager = AppActorInstallReferrerManager(
            context = androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            identityStore = store,
        )

        val result = manager.fetchReferrerOnce()

        assertEquals("sha256:existing", result)
        verify(exactly = 0) { store.setInstallReferrer(any()) }
    }

    @Test
    fun `fetchReferrerOnce upgrades legacy raw cached referrer into queued attribution`() = runBlocking {
        val store = mockk<AppActorIdentityStore>(relaxed = true)
        every { store.installReferrer } returns "utm_source=google&utm_campaign=spring"
        every { store.currentAppUserId } returns "user_123"
        every { store.installId } returns "install_123"
        val backend = mockk<AppActorBackendClient>(relaxed = true)
        coEvery { backend.postAttribution(any(), any()) } throws AppActorBackendException.Network("offline")
        val queueStore = InMemoryAttributeQueueStore()
        val attributesManager = AppActorAttributesManager(
            backendClient = backend,
            queueStore = queueStore,
            identityStore = store,
            packageName = "com.appactor.test",
            appVersionProvider = { "1.0.0" },
            countryProvider = { "TR" },
        )

        val manager = AppActorInstallReferrerManager(
            context = androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            identityStore = store,
            attributesManager = attributesManager,
        )

        val result = manager.fetchReferrerOnce()

        assertEquals(true, result?.startsWith("sha256:"))
        assertEquals("google", queueStore.load("user_123")?.attribution?.source)
        assertEquals("spring", queueStore.load("user_123")?.attribution?.campaign)
        verify { store.setInstallReferrer(match { it.startsWith("sha256:") }) }
    }

    @Test
    fun `persistAndSendReferrer sends minimized attribution without raw referrer`() = runBlocking {
        val store = mockk<AppActorIdentityStore>(relaxed = true)
        every { store.installId } returns "install_123"
        val backend = mockk<AppActorBackendClient>(relaxed = true)
        val attributionSlot = slot<AppActorAttributionRequestDTO>()
        coEvery { backend.postAttribution("user_123", capture(attributionSlot)) } returns AppActorBackendHttpResponse(
            body = Unit,
            statusCode = 204,
        )
        val attributesManager = AppActorAttributesManager(
            backendClient = backend,
            queueStore = InMemoryAttributeQueueStore(),
            identityStore = store,
            packageName = "com.appactor.test",
            appVersionProvider = { "1.0.0" },
            countryProvider = { "TR" },
        )
        val manager = AppActorInstallReferrerManager(
            context = androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            identityStore = store,
            attributesManager = attributesManager,
        )

        val persisted = manager.persistAndSendReferrer(
            appUserId = "user_123",
            details = AppActorInstallReferrerDetails(
                installReferrer = "utm_source=google&utm_campaign=spring&gclid=raw-click-id",
                referrerClickTimestampSeconds = 1710000000,
                installBeginTimestampSeconds = 1710000100,
                googlePlayInstant = false,
            ),
        )

        assertEquals(true, persisted?.startsWith("sha256:"))
        verify { store.setInstallReferrer(match { it.startsWith("sha256:") }) }
        val request = attributionSlot.captured
        assertEquals("google_play_install_referrer", request.provider)
        assertEquals("google", request.source)
        assertEquals("spring", request.campaign)
        assertEquals(false, request.metadata.values.any { it.toString().contains("raw-click-id") })
        assertEquals(false, request.identifiers.values.any { it.contains("raw-click-id") })
        assertEquals(true, request.identifiers["gclidSha256"]?.isNotBlank())
    }

    @Test
    fun `persistAndSendReferrer queues attribution and caches hash when delivery fails`() = runBlocking {
        val store = mockk<AppActorIdentityStore>(relaxed = true)
        every { store.installId } returns "install_123"
        val backend = mockk<AppActorBackendClient>(relaxed = true)
        coEvery { backend.postAttribution(any(), any()) } throws AppActorBackendException.Network("offline")
        val queueStore = InMemoryAttributeQueueStore()
        val attributesManager = AppActorAttributesManager(
            backendClient = backend,
            queueStore = queueStore,
            identityStore = store,
            packageName = "com.appactor.test",
            appVersionProvider = { "1.0.0" },
            countryProvider = { "TR" },
        )
        val manager = AppActorInstallReferrerManager(
            context = androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            identityStore = store,
            attributesManager = attributesManager,
        )

        val persisted = manager.persistAndSendReferrer(
            appUserId = "user_123",
            details = AppActorInstallReferrerDetails(
                installReferrer = "utm_source=google&utm_campaign=spring",
                referrerClickTimestampSeconds = 1710000000,
                installBeginTimestampSeconds = 1710000100,
                googlePlayInstant = false,
            ),
        )

        assertEquals(true, persisted?.startsWith("sha256:"))
        assertEquals("google", queueStore.load("user_123")?.attribution?.source)
        assertEquals("spring", queueStore.load("user_123")?.attribution?.campaign)
        verify { store.setInstallReferrer(match { it.startsWith("sha256:") }) }
    }

    private class InMemoryAttributeQueueStore : AppActorAttributeQueueStore {
        private val mutations = linkedMapOf<String, AppActorQueuedAttributeMutation>()
        private val snapshots = linkedMapOf<String, AppActorAttributionRequestDTO>()

        override fun load(appUserId: String): AppActorQueuedAttributeMutation? = mutations[appUserId]

        override fun save(
            appUserId: String,
            mutation: AppActorQueuedAttributeMutation?,
        ) {
            if (mutation == null || mutation.isEmpty()) {
                mutations.remove(appUserId)
            } else {
                mutations[appUserId] = mutation
            }
        }

        override fun pendingAppUserIds(): List<String> = mutations.keys.sorted()

        override fun loadAttributionSnapshot(appUserId: String): com.appactor.android.backend.dto.AppActorAttributionRequestDTO? =
            snapshots[appUserId]

        override fun saveAttributionSnapshot(
            appUserId: String,
            attribution: com.appactor.android.backend.dto.AppActorAttributionRequestDTO?,
        ) {
            if (attribution == null) {
                snapshots.remove(appUserId)
            } else {
                snapshots[appUserId] = attribution
            }
        }

        override fun loadProfileContextFingerprint(appUserId: String): String? = fingerprints[appUserId]

        override fun saveProfileContextFingerprint(appUserId: String, fingerprint: String?) {
            if (fingerprint == null) fingerprints.remove(appUserId) else fingerprints[appUserId] = fingerprint
        }

        override fun clearAll() {
            mutations.clear()
            snapshots.clear()
            fingerprints.clear()
        }

        private val fingerprints = linkedMapOf<String, String>()
    }
}
