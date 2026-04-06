package com.appactor.android.managers

import com.appactor.android.backend.client.AppActorBackendClient
import com.appactor.android.backend.client.AppActorBackendException
import com.appactor.android.backend.client.AppActorBackendJson
import com.appactor.android.backend.client.toAppActorError
import com.appactor.android.backend.dto.AppActorExperimentAssignmentEnvelopeDTO
import com.appactor.android.backend.dto.AppActorExperimentAssignmentResponseDTO
import com.appactor.android.cache.AppActorExperimentCacheStore
import com.appactor.android.models.AppActorConfigValue
import com.appactor.android.models.AppActorConfigValueType
import com.appactor.android.models.AppActorExperimentAssignment
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.Serializable

internal class AppActorExperimentManager(
    private val backendClient: AppActorBackendClient,
    private val cacheStore: AppActorExperimentCacheStore,
    private val appVersionProvider: () -> String?,
    private val countryProvider: () -> String?,
    private val dateProviderMillis: () -> Long = { System.currentTimeMillis() },
) {

    private val stateLock = ReentrantLock()
    private val cachedAssignments = linkedMapOf<String, CachedAssignment>()
    private val inFlight = linkedMapOf<String, CompletableDeferred<AppActorExperimentAssignment?>>()
    @Volatile
    private var lastRequestId: String? = null
    @Volatile
    private var cacheGeneration: Long = 0

    suspend fun getAssignment(
        experimentKey: String,
        appUserId: String,
    ): AppActorExperimentAssignment? {
        val request = CompletableDeferred<AppActorExperimentAssignment?>()
        return when (val requestState = prepareRequest(experimentKey, request)) {
            is ExperimentRequestState.Cached -> requestState.assignment
            is ExperimentRequestState.Await -> requestState.deferred.await()
            is ExperimentRequestState.Execute -> {
                try {
                    val result = fetchAssignment(
                        experimentKey = experimentKey,
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
                        if (inFlight[experimentKey] === request) {
                            inFlight.remove(experimentKey)
                        }
                    }
                }
            }
        }
    }

    fun cached(experimentKey: String): AppActorExperimentAssignment? {
        return cachedAssignments[experimentKey]?.assignment?.toPublic()
    }

    fun requestId(): String? = lastRequestId

    fun clearCache(appUserId: String? = null) {
        val cancelled = stateLock.withLock {
            cacheGeneration += 1
            val current = inFlight.values.toList()
            cachedAssignments.clear()
            inFlight.clear()
            lastRequestId = null
            current
        }
        cancelled.forEach { it.cancel(CancellationException("Experiment cache cleared.")) }
        appUserId?.let(cacheStore::clear)
    }

    private fun prepareRequest(
        experimentKey: String,
        request: CompletableDeferred<AppActorExperimentAssignment?>,
    ): ExperimentRequestState {
        return stateLock.withLock {
            val cached = cachedAssignments[experimentKey]
            if (cached != null && isFresh(cached.cachedAtMillis)) {
                return@withLock ExperimentRequestState.Cached(cached.assignment?.toPublic())
            }
            inFlight[experimentKey]?.let { existing ->
                return@withLock ExperimentRequestState.Await(existing)
            }
            inFlight[experimentKey] = request
            ExperimentRequestState.Execute(cacheGeneration)
        }
    }

    private suspend fun fetchAssignment(
        experimentKey: String,
        appUserId: String,
        requestGeneration: Long,
    ): AppActorExperimentAssignment? {
        return try {
            val response = backendClient.postExperimentAssignment(
                experimentKey = experimentKey,
                appUserId = appUserId,
                appVersion = appVersionProvider(),
                country = countryProvider(),
            )
            val body = requireNotNull(response.body) { "Experiment response body was null." }
            val cached = CachedAssignment(
                assignment = body.data.toCached(),
                cachedAtMillis = dateProviderMillis(),
            )
            persistAssignment(
                experimentKey = experimentKey,
                cached = cached,
                requestId = response.requestId ?: body.requestId,
                requestGeneration = requestGeneration,
                appUserId = appUserId,
                verified = response.signatureVerified,
            )
        } catch (throwable: Throwable) {
            ensureGeneration(requestGeneration)
            loadFromDiskCache(appUserId, requestGeneration)
            val cached = stateLock.withLock { cachedAssignments[experimentKey] }
            if (cached != null && shouldFallbackToCache(throwable)) {
                ensureGeneration(requestGeneration)
                cached.assignment?.toPublic()
            } else {
                throw throwable.toAppActorError("Failed to fetch experiment assignment.")
            }
        }
    }

    private suspend fun loadFromDiskCache(
        appUserId: String,
        requestGeneration: Long,
    ) {
        val cachedValue = cacheStore.load(appUserId) ?: return
        val decoded = runCatching {
            AppActorBackendJson.instance.decodeFromString(
                CachedAssignmentMap.serializer(),
                cachedValue.payload,
            )
        }.getOrNull() ?: return
        stateLock.withLock {
            ensureGenerationLocked(requestGeneration)
            cachedAssignments.putAll(decoded.entries)
        }
    }

    private fun persistCache(
        appUserId: String,
        verified: Boolean,
    ) {
        val encoded = AppActorBackendJson.instance.encodeToString(
            CachedAssignmentMap.serializer(),
            CachedAssignmentMap(entries = cachedAssignments),
        )
        cacheStore.save(
            appUserId = appUserId,
            payload = encoded,
            verified = verified,
        )
    }

    private suspend fun persistAssignment(
        experimentKey: String,
        cached: CachedAssignment,
        requestId: String?,
        requestGeneration: Long,
        appUserId: String,
        verified: Boolean,
    ): AppActorExperimentAssignment? {
        return stateLock.withLock {
            ensureGenerationLocked(requestGeneration)
            lastRequestId = requestId
            cachedAssignments[experimentKey] = cached
            persistCache(appUserId, verified)
            ensureGenerationLocked(requestGeneration)
            cached.assignment?.toPublic()
        }
    }

    private fun isFresh(cachedAtMillis: Long): Boolean {
        return dateProviderMillis() - cachedAtMillis < CACHE_TTL_MILLIS
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
            throw CancellationException("Experiment request invalidated by cache clear.")
        }
    }

    @Serializable
    private data class CachedAssignmentMap(
        val entries: Map<String, CachedAssignment> = emptyMap(),
    )

    @Serializable
    private data class CachedAssignment(
        val assignment: CachedPublicAssignment? = null,
        val cachedAtMillis: Long,
    )

    @Serializable
    private data class CachedPublicAssignment(
        val experimentId: String,
        val experimentKey: String,
        val variantId: String,
        val variantKey: String,
        val payload: kotlinx.serialization.json.JsonElement,
        val valueType: String? = null,
        val assignedAt: String,
    ) {
        fun toPublic(): AppActorExperimentAssignment {
            return AppActorExperimentAssignment(
                experimentId = experimentId,
                experimentKey = experimentKey,
                variantId = variantId,
                variantKey = variantKey,
                payload = AppActorConfigValue(payload),
                valueType = AppActorConfigValueType.fromWireValue(valueType),
                assignedAt = assignedAt,
            )
        }
    }

    private fun AppActorExperimentAssignmentResponseDTO.toCached(): CachedPublicAssignment? {
        if (!inExperiment) return null
        val experimentValue = experiment ?: return null
        val variantValue = variant ?: return null
        val assignedAtValue = assignedAt ?: return null
        return CachedPublicAssignment(
            experimentId = experimentValue.id,
            experimentKey = experimentValue.key,
            variantId = variantValue.id,
            variantKey = variantValue.key,
            payload = variantValue.payload,
            valueType = variantValue.valueType,
            assignedAt = assignedAtValue,
        )
    }

    private companion object {
        const val CACHE_TTL_MILLIS: Long = 5 * 60 * 1_000
    }

    private sealed interface ExperimentRequestState {
        data class Cached(
            val assignment: AppActorExperimentAssignment?,
        ) : ExperimentRequestState

        data class Await(
            val deferred: CompletableDeferred<AppActorExperimentAssignment?>,
        ) : ExperimentRequestState

        data class Execute(
            val generation: Long,
        ) : ExperimentRequestState
    }
}
