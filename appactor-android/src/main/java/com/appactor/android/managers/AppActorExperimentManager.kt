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
import com.appactor.android.internal.runtime.appActorBackgroundScope
import com.appactor.android.internal.runtime.launchSharedRequest
import com.appactor.android.internal.runtime.throwIfCancellation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.Serializable

internal class AppActorExperimentManager(
    private val backendClient: AppActorBackendClient,
    private val cacheStore: AppActorExperimentCacheStore,
    private val appVersionProvider: () -> String?,
    private val countryProvider: () -> String?,
    private val dateProviderMillis: () -> Long = { System.currentTimeMillis() },
    private val backgroundScope: CoroutineScope = appActorBackgroundScope(),
) {

    private val stateLock = ReentrantLock()
    private val cachedAssignments = linkedMapOf<String, CachedAssignment>()
    // Keyed by user too: a user adopted by restore or sync must not join the previous user's fetch.
    private val inFlight = linkedMapOf<Pair<String, String>, CompletableDeferred<AppActorExperimentAssignment?>>()
    @Volatile
    private var lastRequestId: String? = null
    @Volatile
    private var cacheGeneration: Long = 0
    // The user whose assignments memory holds. Memory holds one user's; see useAssignmentsOfLocked.
    @Volatile
    private var lastCacheUserId: String? = null

    suspend fun getAssignment(
        experimentKey: String,
        appUserId: String,
    ): AppActorExperimentAssignment? {
        val request = stateLock.withLock {
            val cached = cachedAssignments[experimentKey]
            if (cached != null && isFresh(cached.cachedAtMillis) && lastCacheUserId == appUserId) {
                return cached.assignment?.toPublic()
            }
            inFlight[appUserId to experimentKey] ?: startFetchLocked(experimentKey, appUserId)
        }
        return request.await()
    }

    /** Starts a fetch every caller of [experimentKey] for [appUserId] shares, as its inFlight request. Under stateLock. */
    private fun startFetchLocked(
        experimentKey: String,
        appUserId: String,
    ): CompletableDeferred<AppActorExperimentAssignment?> {
        val generation = cacheGeneration
        val key = appUserId to experimentKey
        return CompletableDeferred<AppActorExperimentAssignment?>().also { request ->
            inFlight[key] = request
            backgroundScope.launchSharedRequest(
                request = request,
                cleanup = {
                    stateLock.withLock {
                        if (inFlight[key] === request) inFlight.remove(key)
                    }
                },
                block = { fetchAssignment(experimentKey, appUserId, generation) },
            )
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
            lastCacheUserId = null
            current
        }
        cancelled.forEach { it.cancel(CancellationException("Experiment cache cleared.")) }
        appUserId?.let(cacheStore::clear)
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
            throwIfCancellation(throwable)
            if (!shouldFallbackToCache(throwable)) {
                throw throwable.toAppActorError("Failed to fetch experiment assignment.")
            }
            val cached = stateLock.withLock {
                ensureGenerationLocked(requestGeneration)
                useAssignmentsOfLocked(appUserId)
                cachedAssignments[experimentKey]
            } ?: throw throwable.toAppActorError("Failed to fetch experiment assignment.")
            ensureGeneration(requestGeneration)
            cached.assignment?.toPublic()
        }
    }

    /**
     * Points memory at [appUserId]'s assignments. A different user's are dropped, not relabelled:
     * after restore or sync adopts a merged user they are the previous user's, and the server may
     * answer the new user differently. The new user's disk file is loaded, since persisting memory
     * would otherwise overwrite what earlier sessions stored. Under stateLock.
     */
    private fun useAssignmentsOfLocked(appUserId: String) {
        if (lastCacheUserId == appUserId) return
        cachedAssignments.clear()
        diskAssignments(appUserId)?.let(cachedAssignments::putAll)
        lastCacheUserId = appUserId
    }

    private fun diskAssignments(appUserId: String): Map<String, CachedAssignment>? {
        val cachedValue = cacheStore.load(appUserId) ?: return null
        return runCatching {
            AppActorBackendJson.instance.decodeFromString(
                CachedAssignmentMap.serializer(),
                cachedValue.payload,
            ).entries
        }.getOrNull()
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
            useAssignmentsOfLocked(appUserId)
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
}
