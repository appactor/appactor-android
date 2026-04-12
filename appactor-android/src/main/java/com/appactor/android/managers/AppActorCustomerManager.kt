package com.appactor.android.managers

import android.os.Build
import com.appactor.android.backend.client.AppActorBackendClient
import com.appactor.android.backend.client.AppActorBackendException
import com.appactor.android.backend.client.AppActorBackendHttpResponse
import com.appactor.android.backend.client.AppActorBackendJson
import com.appactor.android.backend.dto.AppActorCustomerEnvelopeDTO
import com.appactor.android.backend.dto.AppActorIdentifyRequestDTO
import com.appactor.android.backend.dto.AppActorLoginRequestDTO
import com.appactor.android.backend.dto.AppActorLoginResponseDTO
import com.appactor.android.backend.dto.AppActorLogoutRequestDTO
import com.appactor.android.backend.mappers.toModel
import com.appactor.android.billing.AppActorStoreAdapter
import com.appactor.android.cache.AppActorCustomerCacheStore
import com.appactor.android.internal.AppActorSDK
import com.appactor.android.models.AppActorConfiguration
import com.appactor.android.models.AppActorCustomerInfo
import com.appactor.android.models.AppActorDiagnosticsDataSource
import com.appactor.android.models.AppActorVerificationResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.Locale

internal class AppActorCustomerManager(
    private val configuration: AppActorConfiguration,
    private val backendClient: AppActorBackendClient,
    private val cacheStore: AppActorCustomerCacheStore,
    private val identityStore: com.appactor.android.storage.AppActorIdentityStore,
    private val offeringsManager: AppActorOfferingsManager,
    private val storeAdapter: AppActorStoreAdapter,
    private val dateProviderMillis: () -> Long = { System.currentTimeMillis() },
) {

    private val inflightMutex = Mutex()
    private val inFlight = linkedMapOf<String, CompletableDeferred<AppActorCustomerInfo>>()
    @Volatile
    private var lastLoadSource: AppActorDiagnosticsDataSource? = null
    private val offlineEntitlementManager = AppActorOfflineEntitlementManager(
        customerCacheStore = cacheStore,
        offeringsManager = offeringsManager,
        storeAdapter = storeAdapter,
        dateProviderMillis = dateProviderMillis,
    )

    suspend fun identify(): AppActorCustomerInfo {
        val resolvedAppUserId = identityStore.currentAppUserId
            ?: identityStore.ensureAppUserId()

        identityStore.setAppUserId(resolvedAppUserId)
        val response = backendClient.identify(
            AppActorIdentifyRequestDTO(
                appUserId = resolvedAppUserId,
                appVersion = appVersion(),
                sdkVersion = AppActorSDK.version,
                deviceLocale = Locale.getDefault().toLanguageTag(),
                deviceModel = Build.MODEL,
                osVersion = Build.VERSION.RELEASE,
            )
        )
        val body = requireNotNull(response.body) { "Identify response body was null." }
        val mapped = body.toModel(productEntitlements = offeringsManager.currentProductEntitlements())
        val finalAppUserId = body.appUserId ?: resolvedAppUserId
        identityStore.setAppUserId(finalAppUserId)
        identityStore.setServerUserId(finalAppUserId)
        identityStore.setLastRequestId(response.requestId)
        saveEnvelope(
            appUserId = finalAppUserId,
            envelope = body.copy(appUserId = finalAppUserId),
            eTag = response.eTag,
            verified = response.signatureVerified,
        )
        lastLoadSource = AppActorDiagnosticsDataSource.Network
        return mapped.copy(
            appUserId = finalAppUserId,
            verification = AppActorVerificationResult.from(response.signatureVerified),
        )
    }

    suspend fun logIn(
        currentAppUserId: String,
        newAppUserId: String,
    ): AppActorCustomerInfo {
        val response = backendClient.login(
            AppActorLoginRequestDTO(
                currentAppUserId = currentAppUserId,
                newAppUserId = newAppUserId,
            )
        )
        val body = requireNotNull(response.body) { "Login response body was null." }
        val finalAppUserId = body.appUserId.ifBlank { newAppUserId }
        val mapped = body.toCustomerEnvelope(finalAppUserId).toModel(
            productEntitlements = offeringsManager.currentProductEntitlements(),
        )
        identityStore.setAppUserId(finalAppUserId)
        identityStore.setServerUserId(body.serverUserId ?: finalAppUserId)
        identityStore.setLastRequestId(response.requestId ?: body.requestId)
        saveEnvelope(
            appUserId = finalAppUserId,
            envelope = body.toCustomerEnvelope(finalAppUserId),
            eTag = response.eTag,
            verified = response.signatureVerified,
        )
        lastLoadSource = AppActorDiagnosticsDataSource.Network
        return mapped.copy(
            appUserId = finalAppUserId,
            requestId = response.requestId ?: mapped.requestId,
            verification = AppActorVerificationResult.from(response.signatureVerified),
        )
    }

    suspend fun logOut(currentAppUserId: String): Boolean {
        val response = backendClient.logout(
            AppActorLogoutRequestDTO(appUserId = currentAppUserId)
        )
        identityStore.setLastRequestId(response.requestId ?: response.body?.requestId)
        lastLoadSource = AppActorDiagnosticsDataSource.Network
        return response.body?.success ?: true
    }

    suspend fun getCustomerInfo(
        appUserId: String,
        forceRefresh: Boolean = false,
        persistIdentityState: Boolean = true,
    ): AppActorCustomerInfo {
        // No cache guard — always goes to network (ETag/304 handles bandwidth).
        // forceRefresh only controls: skip ETag (guarantee fresh 200) + skip in-flight dedup.

        if (!forceRefresh) {
            inflightMutex.withLock {
                inFlight[appUserId]
            }?.let { existing ->
                return existing.await()
            }
        }

        val task = CompletableDeferred<AppActorCustomerInfo>()
        if (!forceRefresh) {
            val existing = inflightMutex.withLock {
                inFlight[appUserId] ?: run {
                    inFlight[appUserId] = task
                    null
                }
            }
            if (existing != null) {
                return existing.await()
            }
        }

        return try {
            val response = backendClient.getCustomer(
                appUserId = appUserId,
                eTag = cacheStore.eTag(appUserId = appUserId, forceRefresh = forceRefresh),
            )
            val result = when {
                response.isNotModified -> {
                    val cached = cacheStore.handleNotModified(appUserId = appUserId, rotatedETag = response.eTag)
                        ?: cacheStore.load(appUserId)
                    if (cached != null) {
                        val decoded = decodeCachedCustomer(appUserId, cached.payload)
                        lastLoadSource = AppActorDiagnosticsDataSource.Cache
                        decoded.copy(
                            requestId = response.requestId ?: decoded.requestId,
                            verification = cached.verification,
                        )
                    } else {
                        // 304 but cache is missing/corrupt — retry without ETag to get fresh 200.
                        val retry = backendClient.getCustomer(appUserId = appUserId, eTag = null)
                        processFreshResponse(appUserId, retry)
                    }
                }

                else -> processFreshResponse(appUserId, response)
            }

            if (persistIdentityState) {
                identityStore.setAppUserId(result.appUserId ?: appUserId)
                identityStore.setServerUserId(result.appUserId ?: appUserId)
                identityStore.setLastRequestId(response.requestId ?: result.requestId)
            }
            if (!forceRefresh) {
                task.complete(result)
            }
            result
        } catch (throwable: Throwable) {
            val fallback = if (!forceRefresh) {
                cacheStore.load(appUserId)
                    ?.takeIf { shouldFallbackToCache(throwable) }
                    ?.let {
                        val decoded = decodeCachedCustomer(appUserId, it.payload)
                        decoded.copy(verification = it.verification)
                    }
            } else {
                null
            }
            if (fallback != null) {
                lastLoadSource = AppActorDiagnosticsDataSource.Cache
                if (!forceRefresh) {
                    task.complete(fallback)
                }
                return fallback
            }
            if (!forceRefresh) {
                task.completeExceptionally(throwable)
            }
            throw throwable
        } finally {
            if (!forceRefresh) {
                inflightMutex.withLock {
                    val current = inFlight[appUserId]
                    if (current === task) {
                        inFlight.remove(appUserId)
                    }
                }
            }
        }
    }

    fun cachedInfo(appUserId: String): AppActorCustomerInfo? {
        val cached = cacheStore.load(appUserId) ?: return null
        return runCatching {
            decodeCachedCustomer(appUserId, cached.payload).copy(verification = cached.verification)
        }.getOrNull()
    }

    fun lastLoadSource(): AppActorDiagnosticsDataSource? = lastLoadSource

    fun seedEnvelope(
        appUserId: String,
        envelope: AppActorCustomerEnvelopeDTO,
        eTag: String?,
        verified: Boolean,
    ) {
        saveEnvelope(appUserId, envelope, eTag, verified)
    }

    fun resetFreshness(appUserId: String) {
        cacheStore.resetFreshness(appUserId)
    }

    fun clearCache(appUserId: String) {
        cacheStore.clear(appUserId)
        lastLoadSource = null
    }

    fun isCustomerCacheFresh(appUserId: String): Boolean {
        val cached = cacheStore.load(appUserId) ?: return false
        return isCacheFresh(cached.cachedAtMillis)
    }

    suspend fun activeEntitlementKeysOffline(appUserId: String): Set<String> {
        return offlineEntitlementManager.activeEntitlementKeysOffline(appUserId)
    }

    private fun decodeCachedCustomer(
        appUserId: String,
        payload: String,
    ): AppActorCustomerInfo {
        val envelope = AppActorBackendJson.instance.decodeFromString<AppActorCustomerEnvelopeDTO>(payload)
        return envelope.toModel(productEntitlements = offeringsManager.currentProductEntitlements())
            .copy(
                appUserId = envelope.appUserId ?: appUserId,
            )
    }

    private fun saveEnvelope(
        appUserId: String,
        envelope: AppActorCustomerEnvelopeDTO,
        eTag: String?,
        verified: Boolean,
    ) {
        cacheStore.save(
            appUserId = appUserId,
            payload = AppActorBackendJson.instance.encodeToString(envelope),
            eTag = eTag,
            verified = verified,
        )
    }

    private fun processFreshResponse(
        appUserId: String,
        response: AppActorBackendHttpResponse<AppActorCustomerEnvelopeDTO>,
    ): AppActorCustomerInfo {
        val body = requireNotNull(response.body) { "Customer response body was null." }
        saveEnvelope(
            appUserId = appUserId,
            envelope = body.copy(appUserId = body.appUserId ?: appUserId),
            eTag = response.eTag,
            verified = response.signatureVerified,
        )
        lastLoadSource = AppActorDiagnosticsDataSource.Network
        return body.toModel(productEntitlements = offeringsManager.currentProductEntitlements())
            .copy(
                appUserId = body.appUserId ?: appUserId,
                requestId = response.requestId ?: body.requestId,
                verification = AppActorVerificationResult.from(response.signatureVerified),
            )
    }

    private fun appVersion(): String? {
        return runCatching {
            val packageManager = configuration.applicationContext.packageManager
            val packageName = configuration.applicationContext.packageName
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull()
    }

    private fun isCacheFresh(cachedAtMillis: Long): Boolean {
        return dateProviderMillis() - cachedAtMillis < FOREGROUND_TTL_MILLIS
    }

    private fun shouldFallbackToCache(throwable: Throwable): Boolean {
        return when (throwable) {
            is AppActorBackendException.Network -> true
            is AppActorBackendException.Http -> throwable.statusCode >= 500
            is IOException -> true
            is IllegalStateException -> true
            else -> false
        }
    }

    private companion object {
        const val FOREGROUND_TTL_MILLIS: Long = 5 * 60 * 1_000
    }
}

private fun AppActorLoginResponseDTO.toCustomerEnvelope(appUserId: String): AppActorCustomerEnvelopeDTO {
    return AppActorCustomerEnvelopeDTO(
        requestDate = requestDate,
        requestDateMs = requestDateMs,
        requestId = requestId,
        appUserId = appUserId,
        customer = customer,
    )
}
