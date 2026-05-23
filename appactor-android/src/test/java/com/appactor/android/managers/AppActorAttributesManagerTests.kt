package com.appactor.android.managers

import com.appactor.android.backend.client.AppActorBackendClient
import com.appactor.android.backend.client.AppActorBackendException
import com.appactor.android.backend.client.AppActorBackendHttpResponse
import com.appactor.android.backend.dto.AppActorAttributionRequestDTO
import com.appactor.android.backend.dto.AppActorAttributesPatchRequestDTO
import com.appactor.android.backend.dto.AppActorCustomerEnvelopeDTO
import com.appactor.android.backend.dto.AppActorExperimentAssignmentEnvelopeDTO
import com.appactor.android.backend.dto.AppActorGoogleReceiptRequestDTO
import com.appactor.android.backend.dto.AppActorGoogleReceiptResponseDTO
import com.appactor.android.backend.dto.AppActorGoogleRestoreRequestDTO
import com.appactor.android.backend.dto.AppActorGoogleRestoreResponseDTO
import com.appactor.android.backend.dto.AppActorGoogleSyncRequestDTO
import com.appactor.android.backend.dto.AppActorGoogleSyncResponseDTO
import com.appactor.android.backend.dto.AppActorIdentifyRequestDTO
import com.appactor.android.backend.dto.AppActorIntegrationIdentifierRequestDTO
import com.appactor.android.backend.dto.AppActorLoginRequestDTO
import com.appactor.android.backend.dto.AppActorLoginResponseDTO
import com.appactor.android.backend.dto.AppActorOfferingsEnvelopeDTO
import com.appactor.android.backend.dto.AppActorRemoteConfigsEnvelopeDTO
import com.appactor.android.models.AppActorAttributeReservedKeys
import com.appactor.android.models.AppActorAttributeValue
import com.appactor.android.models.AppActorAttribution
import com.appactor.android.models.AppActorPlatformInfo
import com.appactor.android.storage.AppActorAttributeQueueStore
import com.appactor.android.storage.AppActorIdentityStore
import com.appactor.android.storage.AppActorQueuedAttributeMutation
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AppActorAttributesManagerTests {

    @Test
    fun `queued attribute writes coalesce before a later successful flush`() = runBlocking {
        val backend = FakeAttributesBackendClient(failMutations = true)
        val store = InMemoryAttributeQueueStore()
        val manager = manager(backend, store)

        manager.setAttribute("user_a", "tier", AppActorAttributeValue.string("gold"))
        manager.setAttribute("user_a", "tier", AppActorAttributeValue.string("platinum"))
        manager.unsetAttribute("user_a", "old_key")

        assertNotNull(store.load("user_a"))

        backend.failMutations = false
        manager.flushPending("user_a")

        assertEquals(1, backend.patchRequests.size)
        assertEquals("user_a", backend.patchRequests.single().first)
        assertEquals(JsonPrimitive("platinum"), backend.patchRequests.single().second.attributes["tier"])
        assertEquals(listOf("old_key"), backend.deleteRequests.map { it.second })
        assertNull(store.load("user_a"))
    }

    @Test
    fun `nullable custom attribute values are routed as unsets`() = runBlocking {
        val backend = FakeAttributesBackendClient()
        val manager = manager(backend, InMemoryAttributeQueueStore())

        manager.setAttributes(
            appUserId = "user_a",
            attributes = mapOf(
                "tier" to AppActorAttributeValue.string("gold"),
                "old_key" to null,
            ),
        )

        assertEquals(JsonPrimitive("gold"), backend.patchRequests.single().second.attributes["tier"])
        assertEquals(listOf("old_key"), backend.deleteRequests.map { it.second })
    }

    @Test
    fun `flushPending only sends mutations for the requested app user id`() = runBlocking {
        val backend = FakeAttributesBackendClient(failMutations = true)
        val store = InMemoryAttributeQueueStore()
        val manager = manager(backend, store)

        manager.setAttribute("user_old", "tier", AppActorAttributeValue.string("gold"))
        manager.setAttribute("user_new", "tier", AppActorAttributeValue.string("silver"))

        backend.failMutations = false
        manager.flushPending("user_new")

        assertEquals(listOf("user_new"), backend.patchRequests.map { it.first })
        assertNotNull(store.load("user_old"))
        assertNull(store.load("user_new"))
    }

    @Test
    fun `reserved helper writes dollar key attributes`() = runBlocking {
        val backend = FakeAttributesBackendClient()
        val manager = manager(backend, InMemoryAttributeQueueStore())

        manager.setReservedString("user_a", AppActorAttributeReservedKeys.email, "hello@appactor.com")

        assertEquals(
            JsonPrimitive("hello@appactor.com"),
            backend.patchRequests.single().second.attributes["\$email"],
        )
    }

    @Test
    fun `reserved helper rejects unknown dollar keys before queueing`() = runBlocking {
        val backend = FakeAttributesBackendClient()
        val store = InMemoryAttributeQueueStore()
        val manager = manager(backend, store)

        val error = runCatching {
            manager.setReservedString("user_a", "\$notARealReservedKey", "value")
        }.exceptionOrNull()

        assertEquals(IllegalArgumentException::class.java, error?.javaClass)
        assertNull(store.load("user_a"))
        assertEquals(0, backend.patchRequests.size)
    }

    @Test
    fun `collectAutomaticProfileContext writes backend canonical system keys without identifiers`() = runBlocking {
        val backend = FakeAttributesBackendClient()
        val manager = manager(backend, InMemoryAttributeQueueStore())

        manager.collectAutomaticProfileContext("user_a")

        val attributes = backend.patchRequests.single().second.attributes
        assertEquals(JsonPrimitive("com.appactor.test"), attributes["\$bundleId"])
        assertNotNull(attributes["\$locale"])
        assertEquals(JsonPrimitive("android"), attributes["\$platform"])
        assertEquals(JsonPrimitive("flutter"), attributes["\$platformFlavor"])
        assertEquals(JsonPrimitive("0.0.8"), attributes["\$platformVersion"])
        assertNotNull(attributes["\$timezone"])
        assertEquals(JsonPrimitive("1.2.3"), attributes["\$appVersion"])
        assertEquals(JsonPrimitive("TR"), attributes["\$localeCountry"])
        assertEquals(null, attributes["\$storefrontCountry"])
        assertEquals(null, attributes["\$appactorInstallId"])
        assertEquals(null, attributes["\$androidPackageName"])
        assertEquals(0, backend.integrationRequests.size)
    }

    @Test
    fun `collectAutomaticProfileContext drops non alpha2 locale country`() = runBlocking {
        val backend = FakeAttributesBackendClient()
        val manager = manager(
            backend,
            InMemoryAttributeQueueStore(),
            countryProvider = { "419" },
        )

        manager.collectAutomaticProfileContext("user_a")

        val attributes = backend.patchRequests.single().second.attributes
        assertEquals(JsonPrimitive("android"), attributes["\$platform"])
        assertEquals(null, attributes["\$localeCountry"])
    }

    @Test
    fun `collectAutomaticProfileContext drops queued context after permanent backend failure`() = runBlocking {
        val backend = FakeAttributesBackendClient(permanentMutationStatus = 404)
        val store = InMemoryAttributeQueueStore()
        val manager = manager(backend, store)

        manager.collectAutomaticProfileContext("user_a")

        assertNull(store.load("user_a"))
        assertEquals(0, backend.patchRequests.size)

        backend.permanentMutationStatus = null
        manager.setAttribute("user_a", "tier", AppActorAttributeValue.string("gold"))

        assertEquals(JsonPrimitive("gold"), backend.patchRequests.single().second.attributes["tier"])
        assertNull(store.load("user_a"))
    }

    @Test
    fun `collectDeviceIdentifiers keeps manual integration id path`() = runBlocking {
        val backend = FakeAttributesBackendClient()
        val manager = manager(backend, InMemoryAttributeQueueStore())

        manager.collectDeviceIdentifiers("user_a")

        val attributes = backend.patchRequests.single().second.attributes
        assertEquals(JsonPrimitive("android"), attributes["\$platform"])
        assertEquals(JsonPrimitive("TR"), attributes["\$localeCountry"])
        assertEquals("appactor_install_id", backend.integrationRequests.single().second.type)
    }

    @Test
    fun `nullable integration identifier clears existing identifier`() = runBlocking {
        val backend = FakeAttributesBackendClient()
        val manager = manager(backend, InMemoryAttributeQueueStore())

        manager.setIntegrationIdentifier("user_a", "firebase_app_instance_id", "fid-1")
        manager.setIntegrationIdentifier("user_a", "firebase_app_instance_id", null)

        assertEquals("fid-1", backend.integrationRequests.single().second.value)
        assertEquals(listOf("firebase_app_instance_id"), backend.integrationDeleteRequests.map { it.second })
    }

    @Test
    fun `attribution request keeps custom provider with canonical acquisition fields`() = runBlocking {
        val backend = FakeAttributesBackendClient()
        val manager = manager(backend, InMemoryAttributeQueueStore())

        manager.updateAttribution(
            "user_a",
            AppActorAttribution(
                provider = "custom",
                providerName = "facebook",
                network = "facebook",
                source = "facebook",
                campaignName = "spring_sale",
                campaign = "spring_sale",
            ),
        )

        val request = backend.attributionRequests.single().second
        assertEquals("custom", request.provider)
        assertEquals("facebook", request.providerName)
        assertEquals("facebook", request.network)
        assertEquals("facebook", request.source)
        assertEquals("spring_sale", request.campaignName)
        assertEquals("spring_sale", request.campaign)
    }

    @Test
    fun `custom attribution helper updates merge into the current payload`() = runBlocking {
        val backend = FakeAttributesBackendClient()
        val manager = manager(backend, InMemoryAttributeQueueStore())

        manager.updateCustomAttribution(
            "user_a",
            AppActorAttribution(
                provider = "custom",
                providerName = "facebook",
                network = "facebook",
                source = "facebook",
            ),
        )
        manager.updateCustomAttribution(
            "user_a",
            AppActorAttribution(provider = "custom", campaignName = "spring_sale", campaign = "spring_sale"),
        )

        val request = backend.attributionRequests.last().second
        assertEquals("custom", request.provider)
        assertEquals("facebook", request.providerName)
        assertEquals("facebook", request.network)
        assertEquals("facebook", request.source)
        assertEquals("spring_sale", request.campaignName)
        assertEquals("spring_sale", request.campaign)
    }

    @Test
    fun `custom attribution helper null clears the selected fields`() = runBlocking {
        val backend = FakeAttributesBackendClient()
        val manager = manager(backend, InMemoryAttributeQueueStore())

        manager.updateCustomAttribution(
            "user_a",
            AppActorAttribution(
                provider = "custom",
                providerName = "facebook",
                network = "facebook",
                source = "facebook",
                campaignName = "spring_sale",
                campaign = "spring_sale",
            ),
        )
        manager.updateCustomAttribution(
            appUserId = "user_a",
            patch = AppActorAttribution(provider = "custom"),
            clearFields = setOf(AppActorCustomAttributionField.MediaSource),
        )

        val request = backend.attributionRequests.last().second
        assertEquals("custom", request.provider)
        assertNull(request.providerName)
        assertNull(request.network)
        assertNull(request.source)
        assertEquals("spring_sale", request.campaignName)
        assertEquals("spring_sale", request.campaign)
    }

    @Test
    fun `direct attribution update refreshes custom helper snapshot`() = runBlocking {
        val backend = FakeAttributesBackendClient()
        val manager = manager(backend, InMemoryAttributeQueueStore())

        manager.updateCustomAttribution(
            "user_a",
            AppActorAttribution(
                provider = "custom",
                providerName = "facebook",
                network = "facebook",
                source = "facebook",
            ),
        )
        manager.updateAttribution(
            "user_a",
            AppActorAttribution(
                provider = "custom",
                providerName = "tiktok",
                network = "tiktok",
                source = "tiktok",
            ),
        )
        manager.updateCustomAttribution(
            "user_a",
            AppActorAttribution(provider = "custom", campaignName = "spring_sale", campaign = "spring_sale"),
        )

        val request = backend.attributionRequests.last().second
        assertEquals("custom", request.provider)
        assertEquals("tiktok", request.providerName)
        assertEquals("tiktok", request.network)
        assertEquals("tiktok", request.source)
        assertEquals("spring_sale", request.campaignName)
        assertEquals("spring_sale", request.campaign)
    }

    @Test
    fun `custom attribution helper reloads snapshot after successful flush`() = runBlocking {
        val backend = FakeAttributesBackendClient()
        val store = InMemoryAttributeQueueStore()
        val manager = manager(backend, store)

        manager.updateCustomAttribution(
            "user_a",
            AppActorAttribution(
                provider = "custom",
                providerName = "facebook",
                network = "facebook",
                source = "facebook",
            ),
        )
        assertNull(store.load("user_a"))

        val relaunchedManager = manager(backend, store)
        relaunchedManager.updateCustomAttribution(
            "user_a",
            AppActorAttribution(provider = "custom", campaignName = "spring_sale", campaign = "spring_sale"),
        )

        val request = backend.attributionRequests.last().second
        assertEquals("custom", request.provider)
        assertEquals("facebook", request.providerName)
        assertEquals("facebook", request.network)
        assertEquals("facebook", request.source)
        assertEquals("spring_sale", request.campaignName)
        assertEquals("spring_sale", request.campaign)
    }

    @Test
    fun `custom attribution helper state is isolated per app user id`() = runBlocking {
        val backend = FakeAttributesBackendClient()
        val manager = manager(backend, InMemoryAttributeQueueStore())

        manager.updateCustomAttribution(
            "user_a",
            AppActorAttribution(
                provider = "custom",
                providerName = "facebook",
                network = "facebook",
                source = "facebook",
            ),
        )
        manager.updateCustomAttribution(
            "user_b",
            AppActorAttribution(provider = "custom", campaignName = "spring_sale", campaign = "spring_sale"),
        )

        val request = backend.attributionRequests.last().second
        assertEquals("custom", request.provider)
        assertNull(request.providerName)
        assertNull(request.network)
        assertNull(request.source)
        assertEquals("spring_sale", request.campaignName)
        assertEquals("spring_sale", request.campaign)
    }

    @Test
    fun `attribution updates remain queued when offline and flush later`() = runBlocking {
        val backend = FakeAttributesBackendClient(failMutations = true)
        val store = InMemoryAttributeQueueStore()
        val manager = manager(backend, store)

        manager.updateAttribution(
            "user_a",
            AppActorAttribution(
                provider = "custom",
                source = "facebook",
                campaign = "spring_sale",
            ),
        )

        val queued = store.load("user_a")
        assertNotNull(queued)
        assertEquals("custom", queued?.attribution?.provider)
        assertEquals(0, backend.attributionRequests.size)

        backend.failMutations = false
        manager.flushPending("user_a")

        val request = backend.attributionRequests.single().second
        assertEquals("custom", request.provider)
        assertEquals("facebook", request.source)
        assertEquals("spring_sale", request.campaign)
        assertNull(store.load("user_a"))
    }

    @Test
    fun `flushPendingForAllUsers drains previous identity buckets`() = runBlocking {
        val backend = FakeAttributesBackendClient(failMutations = true)
        val store = InMemoryAttributeQueueStore()
        val manager = manager(backend, store)

        manager.setAttribute("user_old", "tier", AppActorAttributeValue.string("gold"))
        manager.setAttribute("user_new", "tier", AppActorAttributeValue.string("silver"))

        backend.failMutations = false
        manager.flushPendingForAllUsers()

        assertEquals(listOf("user_new", "user_old"), backend.patchRequests.map { it.first }.sorted())
        assertNull(store.load("user_old"))
        assertNull(store.load("user_new"))
    }

    private fun manager(
        backend: FakeAttributesBackendClient,
        store: InMemoryAttributeQueueStore,
        countryProvider: () -> String? = { "TR" },
    ): AppActorAttributesManager {
        val identityStore = mockk<AppActorIdentityStore>(relaxed = true)
        every { identityStore.installId } returns "appactor-install-test"
        return AppActorAttributesManager(
            backendClient = backend,
            queueStore = store,
            identityStore = identityStore,
            packageName = "com.appactor.test",
            appVersionProvider = { "1.2.3" },
            platformInfoProvider = { AppActorPlatformInfo("flutter", "0.0.8") },
            countryProvider = countryProvider,
        )
    }

    private class InMemoryAttributeQueueStore : AppActorAttributeQueueStore {
        private val mutations = linkedMapOf<String, AppActorQueuedAttributeMutation>()

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

        override fun loadAttributionSnapshot(appUserId: String): AppActorAttributionRequestDTO? =
            snapshots[appUserId]

        override fun saveAttributionSnapshot(
            appUserId: String,
            attribution: AppActorAttributionRequestDTO?,
        ) {
            if (attribution == null) {
                snapshots.remove(appUserId)
            } else {
                snapshots[appUserId] = attribution
            }
        }

        override fun clearAll() {
            mutations.clear()
            snapshots.clear()
        }

        private val snapshots = linkedMapOf<String, AppActorAttributionRequestDTO>()
    }

    private class FakeAttributesBackendClient(
        var failMutations: Boolean = false,
        var permanentMutationStatus: Int? = null,
    ) : AppActorBackendClient {
        val patchRequests = mutableListOf<Pair<String, AppActorAttributesPatchRequestDTO>>()
        val deleteRequests = mutableListOf<Pair<String, String>>()
        val integrationRequests = mutableListOf<Pair<String, AppActorIntegrationIdentifierRequestDTO>>()
        val integrationDeleteRequests = mutableListOf<Pair<String, String>>()
        val attributionRequests = mutableListOf<Pair<String, AppActorAttributionRequestDTO>>()

        override suspend fun postUserAttributes(
            appUserId: String,
            request: AppActorAttributesPatchRequestDTO,
        ): AppActorBackendHttpResponse<Unit> = mutation {
            patchRequests += appUserId to request
        }

        override suspend fun patchUserAttributes(
            appUserId: String,
            request: AppActorAttributesPatchRequestDTO,
        ): AppActorBackendHttpResponse<Unit> = mutation {
            patchRequests += appUserId to request
        }

        override suspend fun deleteUserAttribute(
            appUserId: String,
            key: String,
        ): AppActorBackendHttpResponse<Unit> = mutation {
            deleteRequests += appUserId to key
        }

        override suspend fun postIntegrationIdentifier(
            appUserId: String,
            request: AppActorIntegrationIdentifierRequestDTO,
        ): AppActorBackendHttpResponse<Unit> = mutation {
            integrationRequests += appUserId to request
        }

        override suspend fun deleteIntegrationIdentifier(
            appUserId: String,
            type: String,
        ): AppActorBackendHttpResponse<Unit> = mutation {
            integrationDeleteRequests += appUserId to type
        }

        override suspend fun postAttribution(
            appUserId: String,
            request: AppActorAttributionRequestDTO,
        ): AppActorBackendHttpResponse<Unit> = mutation {
            attributionRequests += appUserId to request
        }

        private fun mutation(block: () -> Unit): AppActorBackendHttpResponse<Unit> {
            if (failMutations) {
                throw AppActorBackendException.Network("offline")
            }
            permanentMutationStatus?.let { status ->
                throw AppActorBackendException.Http(statusCode = status)
            }
            block()
            return AppActorBackendHttpResponse(body = Unit, statusCode = 204)
        }

        override suspend fun identify(request: AppActorIdentifyRequestDTO): AppActorBackendHttpResponse<AppActorCustomerEnvelopeDTO> =
            unused()

        override suspend fun login(request: AppActorLoginRequestDTO): AppActorBackendHttpResponse<AppActorLoginResponseDTO> =
            unused()

        override suspend fun getOfferings(eTag: String?): AppActorBackendHttpResponse<AppActorOfferingsEnvelopeDTO> =
            unused()

        override suspend fun getCustomer(
            appUserId: String,
            eTag: String?,
        ): AppActorBackendHttpResponse<AppActorCustomerEnvelopeDTO> = unused()

        override suspend fun getRemoteConfigs(
            appUserId: String?,
            appVersion: String?,
            country: String?,
            eTag: String?,
        ): AppActorBackendHttpResponse<AppActorRemoteConfigsEnvelopeDTO> = unused()

        override suspend fun postExperimentAssignment(
            experimentKey: String,
            appUserId: String,
            appVersion: String?,
            country: String?,
        ): AppActorBackendHttpResponse<AppActorExperimentAssignmentEnvelopeDTO> = unused()

        override suspend fun postGoogleReceipt(
            request: AppActorGoogleReceiptRequestDTO,
        ): AppActorBackendHttpResponse<AppActorGoogleReceiptResponseDTO> = unused()

        override suspend fun postGoogleRestore(
            request: AppActorGoogleRestoreRequestDTO,
        ): AppActorBackendHttpResponse<AppActorGoogleRestoreResponseDTO> = unused()

        override suspend fun postGoogleSync(
            request: AppActorGoogleSyncRequestDTO,
        ): AppActorBackendHttpResponse<AppActorGoogleSyncResponseDTO> = unused()

        private fun <T> unused(): AppActorBackendHttpResponse<T> {
            throw IllegalStateException("Unused in attributes manager tests.")
        }
    }
}
