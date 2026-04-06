package com.appactor.android.managers

import com.appactor.android.backend.client.AppActorBackendClient
import com.appactor.android.backend.client.AppActorBackendException
import com.appactor.android.backend.client.AppActorBackendJson
import com.appactor.android.backend.client.toAppActorError
import com.appactor.android.backend.dto.AppActorRemoteConfigsEnvelopeDTO
import com.appactor.android.cache.AppActorRemoteConfigsCacheStore
import com.appactor.android.models.AppActorConfigValue
import com.appactor.android.models.AppActorConfigValueType
import com.appactor.android.models.AppActorDiagnosticsDataSource
import com.appactor.android.models.AppActorRemoteConfigItem
import com.appactor.android.models.AppActorRemoteConfigs
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

internal class AppActorRemoteConfigManager(
    private val backendClient: AppActorBackendClient,
    private val cacheStore: AppActorRemoteConfigsCacheStore,
    private val appVersionProvider: () -> String?,
    private val countryProvider: () -> String?,
    private val dateProviderMillis: () -> Long = { System.currentTimeMillis() },
) {

    private val stateLock = ReentrantLock()
    @Volatile
    private var cachedConfigs: AppActorRemoteConfigs? = null
    @Volatile
    private var cachedAtMillis: Long? = null
    @Volatile
    private var lastRequestId: String? = null
    @Volatile
    private var inFlight: CompletableDeferred<AppActorRemoteConfigs>? = null
    @Volatile
    private var cacheGeneration: Long = 0
    @Volatile
    private var lastLoadSource: AppActorDiagnosticsDataSource? = null

    suspend fun getRemoteConfigs(appUserId: String): AppActorRemoteConfigs {
        val request = CompletableDeferred<AppActorRemoteConfigs>()
        return when (val requestState = prepareRequest(request)) {
            is RemoteConfigRequestState.Cached -> requestState.configs
            is RemoteConfigRequestState.Await -> requestState.deferred.await()
            is RemoteConfigRequestState.Execute -> {
                try {
                    val result = fetchRemoteConfigs(
                        appUserId = appUserId,
                        requestGeneration = requestState.generation,
                    )
                    request.complete(result)
                    result
                } catch (throwable: Throwable) {
                    request.completeExceptionally(throwable)
                    throw throwable
                } finally {
                    stateLock.withLock {
                        if (inFlight === request) {
                            inFlight = null
                        }
                    }
                }
            }
        }
    }

    fun cached(): AppActorRemoteConfigs? = cachedConfigs

    fun requestId(): String? = lastRequestId

    fun lastLoadSource(): AppActorDiagnosticsDataSource? = lastLoadSource

    fun clearCache(appUserId: String? = null) {
        val cancelled = stateLock.withLock {
            cacheGeneration += 1
            val current = inFlight
            inFlight = null
            cachedConfigs = null
            cachedAtMillis = null
            lastRequestId = null
            lastLoadSource = null
            current
        }
        cancelled?.cancel(CancellationException("Remote config cache cleared."))
        appUserId?.let(cacheStore::clear)
    }

    private fun prepareRequest(
        request: CompletableDeferred<AppActorRemoteConfigs>,
    ): RemoteConfigRequestState {
        return stateLock.withLock {
            if (isMemoryCacheFreshLocked()) {
                lastLoadSource = AppActorDiagnosticsDataSource.Cache
                return@withLock RemoteConfigRequestState.Cached(requireNotNull(cachedConfigs))
            }
            inFlight?.let { existing ->
                return@withLock RemoteConfigRequestState.Await(existing)
            }
            inFlight = request
            RemoteConfigRequestState.Execute(cacheGeneration)
        }
    }

    private suspend fun fetchRemoteConfigs(
        appUserId: String,
        requestGeneration: Long,
    ): AppActorRemoteConfigs {
        return try {
            val response = backendClient.getRemoteConfigs(
                appUserId = appUserId,
                appVersion = appVersionProvider(),
                country = countryProvider(),
                eTag = cacheStore.eTag(appUserId = appUserId, forceRefresh = false),
            )
            ensureGeneration(requestGeneration)
            when {
                response.isNotModified -> {
                    val cached = stateLock.withLock {
                        ensureGenerationLocked(requestGeneration)
                        cachedConfigs?.also {
                            cachedAtMillis = dateProviderMillis()
                            lastRequestId = response.requestId
                            lastLoadSource = AppActorDiagnosticsDataSource.Cache
                        }
                    }
                    cached ?: run {
                        val cachedValue = cacheStore.handleNotModified(appUserId, response.eTag) ?: cacheStore.load(appUserId)
                            ?: throw IllegalStateException("Remote config cache missing for 304 response.")
                        decodeCached(
                            payload = cachedValue.payload,
                            cachedAtMillis = cachedValue.cachedAtMillis,
                            requestId = response.requestId,
                            requestGeneration = requestGeneration,
                        )
                    }
                }

                else -> {
                    val body = requireNotNull(response.body) { "Remote config response body was null." }
                    val payload = AppActorBackendJson.instance.encodeToString(body)
                    persistDecoded(
                        payload = payload,
                        cachedAtMillis = dateProviderMillis(),
                        requestId = response.requestId ?: body.requestId,
                        requestGeneration = requestGeneration,
                        appUserId = appUserId,
                        eTag = response.eTag,
                        verified = response.signatureVerified,
                    )
                }
            }
        } catch (throwable: Throwable) {
            ensureGeneration(requestGeneration)
            val cachedValue = cacheStore.load(appUserId)
            if (cachedValue != null && shouldFallbackToCache(throwable)) {
                lastLoadSource = AppActorDiagnosticsDataSource.Cache
                decodeCached(
                    payload = cachedValue.payload,
                    cachedAtMillis = cachedValue.cachedAtMillis,
                    requestId = null,
                    requestGeneration = requestGeneration,
                )
            } else {
                throw throwable.toAppActorError("Failed to fetch remote configs.")
            }
        }
    }

    private suspend fun decodeCached(
        payload: String,
        cachedAtMillis: Long,
        requestId: String?,
        requestGeneration: Long,
    ): AppActorRemoteConfigs {
        val decoded = AppActorBackendJson.instance.decodeFromString<AppActorRemoteConfigsEnvelopeDTO>(payload)
        val configs = decoded.toModel()
        return stateLock.withLock {
            ensureGenerationLocked(requestGeneration)
            cachedConfigs = configs
            this.cachedAtMillis = cachedAtMillis
            this.lastRequestId = requestId ?: decoded.requestId
            lastLoadSource = AppActorDiagnosticsDataSource.Cache
            configs
        }
    }

    private suspend fun persistDecoded(
        payload: String,
        cachedAtMillis: Long,
        requestId: String?,
        requestGeneration: Long,
        appUserId: String,
        eTag: String?,
        verified: Boolean,
    ): AppActorRemoteConfigs {
        val decoded = AppActorBackendJson.instance.decodeFromString<AppActorRemoteConfigsEnvelopeDTO>(payload)
        val configs = decoded.toModel()
        return stateLock.withLock {
            ensureGenerationLocked(requestGeneration)
            cacheStore.save(
                appUserId = appUserId,
                payload = payload,
                eTag = eTag,
                verified = verified,
            )
            ensureGenerationLocked(requestGeneration)
            cachedConfigs = configs
            this.cachedAtMillis = cachedAtMillis
            this.lastRequestId = requestId ?: decoded.requestId
            lastLoadSource = AppActorDiagnosticsDataSource.Network
            configs
        }
    }

    private fun isMemoryCacheFreshLocked(): Boolean {
        val cachedAt = cachedAtMillis ?: return false
        return dateProviderMillis() - cachedAt < CACHE_TTL_MILLIS
    }

    private fun shouldFallbackToCache(throwable: Throwable): Boolean {
        return when (throwable) {
            is AppActorBackendException.Network -> true
            is AppActorBackendException.Http -> throwable.statusCode >= 500
            is IOException -> true
            else -> false
        }
    }

    private fun ensureGeneration(expected: Long) {
        stateLock.withLock {
            ensureGenerationLocked(expected)
        }
    }

    private fun ensureGenerationLocked(expected: Long) {
        if (cacheGeneration != expected) {
            throw CancellationException("Remote config request invalidated by cache clear.")
        }
    }

    private fun AppActorRemoteConfigsEnvelopeDTO.toModel(): AppActorRemoteConfigs {
        return AppActorRemoteConfigs(
            items = data.map { item ->
                AppActorRemoteConfigItem(
                    key = item.key,
                    value = AppActorConfigValue(item.value),
                    valueType = AppActorConfigValueType.fromWireValue(item.valueType),
                )
            },
        )
    }

    private companion object {
        const val CACHE_TTL_MILLIS: Long = 5 * 60 * 1_000
    }

    private sealed interface RemoteConfigRequestState {
        data class Cached(
            val configs: AppActorRemoteConfigs,
        ) : RemoteConfigRequestState

        data class Await(
            val deferred: CompletableDeferred<AppActorRemoteConfigs>,
        ) : RemoteConfigRequestState

        data class Execute(
            val generation: Long,
        ) : RemoteConfigRequestState
    }
}
