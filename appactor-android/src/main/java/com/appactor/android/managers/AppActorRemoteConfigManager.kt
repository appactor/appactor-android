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
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CancellationException
import com.appactor.android.internal.runtime.launchSharedRequest
import com.appactor.android.internal.runtime.throwIfCancellation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

internal class AppActorRemoteConfigManager(
    private val backendClient: AppActorBackendClient,
    private val cacheStore: AppActorRemoteConfigsCacheStore,
    private val appVersionProvider: () -> String?,
    private val countryProvider: () -> String?,
    private val dateProviderMillis: () -> Long = { System.currentTimeMillis() },
    private val backgroundScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    private val stateLock = ReentrantLock()
    private val cachedConfigs = mutableMapOf<RemoteConfigContext, AppActorRemoteConfigs>()
    private val cachedAtMillis = mutableMapOf<RemoteConfigContext, Long>()
    private val inFlight = mutableMapOf<RemoteConfigContext, CompletableDeferred<AppActorRemoteConfigs>>()
    private val inFlightGenerations = mutableMapOf<RemoteConfigContext, Long>()
    private val requiresUserContextByContext = mutableMapOf<RemoteConfigContext, Boolean>()
    private val modeDecisions = mutableMapOf<ModeContext, ModeDecision>()

    @Volatile
    private var lastRequestId: String? = null
    private var nextInFlightGeneration: Long = 0
    // Bumped by clearCache(), under stateLock.
    private var clearGeneration: Long = 0
    @Volatile
    private var lastLoadSource: AppActorDiagnosticsDataSource? = null
    @Volatile
    private var lastCacheContext: RemoteConfigContext? = null

    suspend fun getRemoteConfigs(appUserId: String): AppActorRemoteConfigs {
        val appVersion = normalizeOptional(appVersionProvider())
        val country = normalizeOptional(countryProvider())?.uppercase(Locale.US)
        val userContext = RemoteConfigContext(
            appUserId = contextAppUserId(appUserId),
            appVersion = appVersion,
            country = country,
        )
        val publicContext = userContext.copy(appUserId = null)
        val modeContext = ModeContext(appVersion = appVersion, country = country)

        val preferredContext = stateLock.withLock {
            preferredContextLocked(
                userContext = userContext,
                publicContext = publicContext,
                modeContext = modeContext,
            )
        }
        if (preferredContext.appUserId != null) {
            val configs = resolveContext(preferredContext)
            val requiresUserContext = stateLock.withLock {
                requiresUserContextByContext[preferredContext]
            }
            updateModeDecision(modeContext, requiresUserContext)
            return configs
        }

        val probe = try {
            Result.success(resolveContext(preferredContext))
        } catch (throwable: Throwable) {
            throwIfCancellation(throwable)
            Result.failure(throwable)
        }
        val requiresUserContext = stateLock.withLock {
            requiresUserContextByContext[preferredContext]
        }
        return when {
            userContext.appUserId == null || requiresUserContext == false -> {
                updateModeDecision(modeContext, requiresUserContext)
                probe.getOrThrow()
            }

            requiresUserContext == true -> refetchWithUserContext(
                userContext = userContext,
                publicContext = preferredContext,
                modeContext = modeContext,
            )

            // The probe never reached the server (offline, or a 5xx), so which context the app
            // uses is unknown. A user-scoped app keeps only the user's entry on disk; a public one
            // only the public entry, which the probe has already fallen back to.
            else -> storedConfigs(userContext) ?: probe.getOrThrow()
        }
    }

    fun cached(): AppActorRemoteConfigs? = stateLock.withLock {
        lastCacheContext?.let(cachedConfigs::get)
    }

    fun requestId(): String? = lastRequestId

    fun lastLoadSource(): AppActorDiagnosticsDataSource? = lastLoadSource

    fun clearCache(appUserId: String? = null) {
        val normalized = contextAppUserId(appUserId)
        val appUserIdsToClear = appUserIdsToClear(normalized)
        val shouldClearAllRemoteConfigs = normalized == null
        val cancelled = stateLock.withLock {
            clearGeneration += 1
            val matching = inFlight
                .filterKeys { context -> shouldClearAllRemoteConfigs || context.appUserId in appUserIdsToClear }
                .values
                .toList()
            inFlight.keys.removeIf { context -> shouldClearAllRemoteConfigs || context.appUserId in appUserIdsToClear }
            inFlightGenerations.keys.removeIf { context -> shouldClearAllRemoteConfigs || context.appUserId in appUserIdsToClear }
            cachedConfigs.keys.removeIf { context -> shouldClearAllRemoteConfigs || context.appUserId in appUserIdsToClear }
            cachedAtMillis.keys.removeIf { context -> shouldClearAllRemoteConfigs || context.appUserId in appUserIdsToClear }
            requiresUserContextByContext.keys.removeIf { context -> shouldClearAllRemoteConfigs || context.appUserId in appUserIdsToClear }
            lastRequestId = null
            lastLoadSource = null
            if (shouldClearAllRemoteConfigs || lastCacheContext?.appUserId in appUserIdsToClear) {
                lastCacheContext = null
            }
            if (shouldClearAllRemoteConfigs) {
                modeDecisions.clear()
            }
            matching
        }
        cancelled.forEach { deferred ->
            deferred.cancel(CancellationException("Remote config cache cleared."))
        }
        if (shouldClearAllRemoteConfigs) {
            cacheStore.clearAll()
        } else {
            appUserIdsToClear.forEach(cacheStore::clear)
        }
    }

    private suspend fun resolveContext(context: RemoteConfigContext): AppActorRemoteConfigs {
        val request = CompletableDeferred<AppActorRemoteConfigs>()
        return when (val requestState = prepareRequest(context, request)) {
            is RemoteConfigRequestState.Cached -> requestState.configs
            is RemoteConfigRequestState.Await -> requestState.deferred.await()
            is RemoteConfigRequestState.Execute -> {
                backgroundScope.launchSharedRequest(
                    request = request,
                    cleanup = {
                        stateLock.withLock {
                            if (inFlight[context] === request) {
                                inFlight.remove(context)
                                inFlightGenerations.remove(context)
                            }
                        }
                    },
                    block = { fetchRemoteConfigs(context = context, requestGeneration = requestState.generation) },
                )
                request.await()
            }
        }
    }

    private fun prepareRequest(
        context: RemoteConfigContext,
        request: CompletableDeferred<AppActorRemoteConfigs>,
    ): RemoteConfigRequestState {
        return stateLock.withLock {
            val cached = cachedConfigs[context]
            if (cached != null && isMemoryCacheFreshLocked(context)) {
                lastLoadSource = AppActorDiagnosticsDataSource.Cache
                lastCacheContext = context
                return@withLock RemoteConfigRequestState.Cached(cached)
            }
            inFlight[context]?.let { existing ->
                return@withLock RemoteConfigRequestState.Await(existing)
            }
            nextInFlightGeneration += 1
            val generation = nextInFlightGeneration
            inFlight[context] = request
            inFlightGenerations[context] = generation
            RemoteConfigRequestState.Execute(generation)
        }
    }

    private suspend fun fetchRemoteConfigs(
        context: RemoteConfigContext,
        requestGeneration: Long,
    ): AppActorRemoteConfigs {
        return try {
            val response = backendClient.getRemoteConfigs(
                appUserId = context.appUserId,
                appVersion = context.appVersion,
                country = context.country,
                eTag = cacheStore.eTag(
                    appUserId = context.appUserId,
                    appVersion = context.appVersion,
                    country = context.country,
                    forceRefresh = false,
                ),
            )
            ensureGeneration(context, requestGeneration)
            // A response without the header counts as requiring the user, as it always has; so a
            // context with no flag is one whose server answer this process hasn't had.
            recordRequiresUserContext(context, response.remoteConfigRequiresUserContext ?: true)
            when {
                response.isNotModified -> {
                    val cached = stateLock.withLock {
                        ensureGenerationLocked(context, requestGeneration)
                        cachedConfigs[context]?.also {
                            cachedAtMillis[context] = dateProviderMillis()
                            lastRequestId = response.requestId
                            lastLoadSource = AppActorDiagnosticsDataSource.Cache
                            lastCacheContext = context
                        }
                    }
                    cached ?: run {
                        val cachedValue = cacheStore.handleNotModified(
                            appUserId = context.appUserId,
                            appVersion = context.appVersion,
                            country = context.country,
                            rotatedETag = response.eTag,
                        ) ?: cacheStore.load(
                            appUserId = context.appUserId,
                            appVersion = context.appVersion,
                            country = context.country,
                        ) ?: throw IllegalStateException("Remote config cache missing for 304 response.")
                        decodeCached(
                            payload = cachedValue.payload,
                            cachedAtMillis = cachedValue.cachedAtMillis,
                            requestId = response.requestId,
                            requestGeneration = requestGeneration,
                            context = context,
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
                        context = context,
                        eTag = response.eTag,
                        verified = response.signatureVerified,
                    )
                }
            }
        } catch (throwable: Throwable) {
            ensureGeneration(context, requestGeneration)
            // A cancelled fetch is not answered from the cache.
            throwIfCancellation(throwable)
            val cachedValue = cacheStore.load(
                appUserId = context.appUserId,
                appVersion = context.appVersion,
                country = context.country,
            )
            if (cachedValue != null && shouldFallbackToCache(throwable)) {
                lastLoadSource = AppActorDiagnosticsDataSource.Cache
                decodeCached(
                    payload = cachedValue.payload,
                    cachedAtMillis = cachedValue.cachedAtMillis,
                    requestId = null,
                    requestGeneration = requestGeneration,
                    context = context,
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
        context: RemoteConfigContext,
    ): AppActorRemoteConfigs {
        val decoded = AppActorBackendJson.instance.decodeFromString<AppActorRemoteConfigsEnvelopeDTO>(payload)
        val configs = decoded.toModel()
        return stateLock.withLock {
            ensureGenerationLocked(context, requestGeneration)
            cachedConfigs[context] = configs
            this.cachedAtMillis[context] = cachedAtMillis
            this.lastRequestId = requestId ?: decoded.requestId
            lastLoadSource = AppActorDiagnosticsDataSource.Cache
            lastCacheContext = context
            configs
        }
    }

    private suspend fun persistDecoded(
        payload: String,
        cachedAtMillis: Long,
        requestId: String?,
        requestGeneration: Long,
        context: RemoteConfigContext,
        eTag: String?,
        verified: Boolean,
    ): AppActorRemoteConfigs {
        val decoded = AppActorBackendJson.instance.decodeFromString<AppActorRemoteConfigsEnvelopeDTO>(payload)
        val configs = decoded.toModel()
        return stateLock.withLock {
            ensureGenerationLocked(context, requestGeneration)
            cacheStore.save(
                appUserId = context.appUserId,
                appVersion = context.appVersion,
                country = context.country,
                payload = payload,
                eTag = eTag,
                verified = verified,
            )
            ensureGenerationLocked(context, requestGeneration)
            cachedConfigs[context] = configs
            this.cachedAtMillis[context] = cachedAtMillis
            this.lastRequestId = requestId ?: decoded.requestId
            lastLoadSource = AppActorDiagnosticsDataSource.Network
            lastCacheContext = context
            configs
        }
    }

    private fun preferredContextLocked(
        userContext: RemoteConfigContext,
        publicContext: RemoteConfigContext,
        modeContext: ModeContext,
    ): RemoteConfigContext {
        val decision = freshModeDecisionLocked(modeContext)
        return if (userContext.appUserId != null && decision == FetchMode.RequiresUser) {
            userContext
        } else {
            publicContext
        }
    }

    private fun freshModeDecisionLocked(context: ModeContext): FetchMode? {
        val decision = modeDecisions[context] ?: return null
        if (dateProviderMillis() - decision.decidedAtMillis >= CACHE_TTL_MILLIS) {
            modeDecisions.remove(context)
            return null
        }
        return decision.mode
    }

    private fun updateModeDecision(
        context: ModeContext,
        requiresUserContext: Boolean?,
    ) {
        if (requiresUserContext == null) return
        stateLock.withLock {
            modeDecisions[context] = ModeDecision(
                mode = if (requiresUserContext) FetchMode.RequiresUser else FetchMode.PublicOnly,
                decidedAtMillis = dateProviderMillis(),
            )
        }
    }

    /** The context's disk entry, made the current one; null when there is none. */
    private fun storedConfigs(context: RemoteConfigContext): AppActorRemoteConfigs? {
        val generationAtRead = stateLock.withLock { clearGeneration }
        val cachedValue = cacheStore.load(
            appUserId = context.appUserId,
            appVersion = context.appVersion,
            country = context.country,
        ) ?: return null
        val decoded = runCatching {
            AppActorBackendJson.instance.decodeFromString<AppActorRemoteConfigsEnvelopeDTO>(cachedValue.payload)
        }.getOrNull() ?: return null
        val configs = decoded.toModel()
        return stateLock.withLock {
            // A clearCache() since the read deleted this entry.
            if (clearGeneration != generationAtRead) return@withLock null
            cachedConfigs[context] = configs
            cachedAtMillis[context] = cachedValue.cachedAtMillis
            lastRequestId = decoded.requestId
            lastLoadSource = AppActorDiagnosticsDataSource.Cache
            lastCacheContext = context
            configs
        }
    }

    private suspend fun refetchWithUserContext(
        userContext: RemoteConfigContext,
        publicContext: RemoteConfigContext,
        modeContext: ModeContext,
    ): AppActorRemoteConfigs {
        updateModeDecision(modeContext, true)
        discardPublicProbeCache(publicContext)
        return resolveContext(userContext)
    }

    private fun recordRequiresUserContext(
        context: RemoteConfigContext,
        requiresUserContext: Boolean,
    ) {
        stateLock.withLock {
            requiresUserContextByContext[context] = requiresUserContext
        }
    }

    private fun discardPublicProbeCache(context: RemoteConfigContext) {
        stateLock.withLock {
            cachedConfigs.remove(context)
            cachedAtMillis.remove(context)
            if (lastCacheContext == context) {
                lastCacheContext = null
            }
        }
        cacheStore.clearContext(
            appUserId = context.appUserId,
            appVersion = context.appVersion,
            country = context.country,
        )
    }

    private fun isMemoryCacheFreshLocked(context: RemoteConfigContext): Boolean {
        val cachedAt = cachedAtMillis[context] ?: return false
        return dateProviderMillis() - cachedAt < CACHE_TTL_MILLIS
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

    private fun ensureGeneration(
        context: RemoteConfigContext,
        expected: Long,
    ) {
        stateLock.withLock {
            ensureGenerationLocked(context, expected)
        }
    }

    private fun ensureGenerationLocked(
        context: RemoteConfigContext,
        expected: Long,
    ) {
        if (inFlightGenerations[context] != expected) {
            throw CancellationException("Remote config request invalidated by cache clear.")
        }
    }

    private fun normalizeOptional(value: String?): String? {
        return value?.trim()?.takeIf { it.isNotEmpty() }
    }

    // Not trimmed: the backend looks the id up verbatim, as the identity store keeps it.
    private fun contextAppUserId(value: String?): String? = value?.takeIf { it.isNotBlank() }

    private fun appUserIdsToClear(appUserId: String?): Set<String?> {
        return if (appUserId == null) {
            setOf(null)
        } else {
            setOf(appUserId, null)
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

    private data class RemoteConfigContext(
        val appUserId: String?,
        val appVersion: String?,
        val country: String?,
    )

    private data class ModeContext(
        val appVersion: String?,
        val country: String?,
    )

    private enum class FetchMode {
        PublicOnly,
        RequiresUser,
    }

    private data class ModeDecision(
        val mode: FetchMode,
        val decidedAtMillis: Long,
    )

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
