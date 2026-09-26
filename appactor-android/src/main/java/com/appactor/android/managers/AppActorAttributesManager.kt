package com.appactor.android.managers

import android.os.Build
import com.appactor.android.backend.client.AppActorBackendClient
import com.appactor.android.backend.client.AppActorBackendException
import com.appactor.android.backend.client.toAppActorError
import com.appactor.android.backend.dto.AppActorAttributionRequestDTO
import com.appactor.android.backend.dto.clippedToServerLimits
import com.appactor.android.backend.dto.AppActorAttributesPatchRequestDTO
import com.appactor.android.backend.dto.AppActorIntegrationIdentifierRequestDTO
import com.appactor.android.internal.AppActorSDK
import com.appactor.android.internal.runtime.throwIfCancellation
import com.appactor.android.internal.logging.AppActorLogger
import com.appactor.android.models.AppActorAttributeReservedKeys
import com.appactor.android.models.AppActorAttributeValue
import com.appactor.android.models.AppActorAttributesValidation
import com.appactor.android.models.AppActorAttribution
import com.appactor.android.models.AppActorError
import com.appactor.android.models.AppActorIso8601
import com.appactor.android.models.AppActorPlatformInfo
import com.appactor.android.storage.AppActorAttributeQueueStore
import com.appactor.android.storage.AppActorIdentityStore
import com.appactor.android.storage.AppActorQueuedAttributeMutation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import java.security.MessageDigest
import java.util.Locale
import java.util.TimeZone

internal class AppActorAttributesManager(
    private val backendClient: AppActorBackendClient,
    private val queueStore: AppActorAttributeQueueStore,
    private val identityStore: AppActorIdentityStore,
    private val packageName: String,
    private val appVersionProvider: () -> String?,
    private val platformInfoProvider: () -> AppActorPlatformInfo? = { null },
    private val countryProvider: () -> String?,
) {
    private val queueMutex = Mutex()
    // One flush per user at a time: concurrent flushes of a queue would send it twice, and a
    // DELETE could overtake the PATCH that creates the user on the server and find no user.
    private val flushMutexes = mutableMapOf<String, Mutex>()
    // Set by reset() through clearQueue(). A write or flush still running for the old session
    // must not put anything back into the store the next session uses.
    private var isCleared = false

    suspend fun setAttributes(
        appUserId: String,
        attributes: Map<String, AppActorAttributeValue?>,
        allowReservedKeys: Boolean = false,
    ) {
        val normalized = normalizeAttributes(attributes, allowReservedKeys)
        if (normalized.isEmpty()) return
        enqueueNormalizedAttributes(appUserId, normalized)
        flushPending(appUserId)
    }

    suspend fun setAttribute(
        appUserId: String,
        key: String,
        value: AppActorAttributeValue,
    ) {
        setAttributes(appUserId, mapOf(key to value))
    }

    suspend fun unsetAttribute(
        appUserId: String,
        key: String,
        allowReservedKey: Boolean = false,
    ) {
        val normalized = if (allowReservedKey) {
            AppActorAttributesValidation.normalizeReservedKey(key)
        } else {
            AppActorAttributesValidation.normalizeCustomKey(key)
        }
        enqueue(appUserId) { existing ->
            existing.copy(
                attributes = existing.attributes - normalized,
                unsetAttributes = (existing.unsetAttributes + normalized)
                    .distinct()
                    .takeLastBounded(MAX_PENDING_ATTRIBUTES),
            )
        }
        flushPending(appUserId)
    }

    suspend fun setReservedString(
        appUserId: String,
        key: String,
        value: String?,
    ) {
        if (value.isNullOrBlank()) {
            unsetAttribute(appUserId = appUserId, key = key, allowReservedKey = true)
        } else {
            setAttributes(
                appUserId = appUserId,
                attributes = mapOf(key to AppActorAttributeValue.string(value)),
                allowReservedKeys = true,
            )
        }
    }

    suspend fun setIntegrationIdentifier(
        appUserId: String,
        type: String,
        value: String?,
    ) {
        val normalizedType = AppActorAttributesValidation.normalizeIntegrationIdentifierType(type)
        if (value == null || value.isEmpty()) {
            unsetIntegrationIdentifier(appUserId = appUserId, type = normalizedType)
            return
        }
        AppActorAttributesValidation.validateIntegrationIdentifierValue(value)
        enqueue(appUserId) { existing ->
            existing.copy(
                integrationIdentifiers = (existing.integrationIdentifiers + (normalizedType to value))
                    .takeLastBounded(MAX_PENDING_INTEGRATION_IDENTIFIERS),
                unsetIntegrationIdentifiers = existing.unsetIntegrationIdentifiers - normalizedType,
            )
        }
        flushPending(appUserId)
    }

    suspend fun unsetIntegrationIdentifier(
        appUserId: String,
        type: String,
    ) {
        val normalizedType = AppActorAttributesValidation.normalizeIntegrationIdentifierType(type)
        enqueue(appUserId) { existing ->
            existing.copy(
                integrationIdentifiers = existing.integrationIdentifiers - normalizedType,
                unsetIntegrationIdentifiers = (existing.unsetIntegrationIdentifiers + normalizedType)
                    .distinct()
                    .takeLastBounded(MAX_PENDING_INTEGRATION_IDENTIFIERS),
            )
        }
        flushPending(appUserId)
    }

    suspend fun collectAutomaticProfileContext(appUserId: String) {
        val normalized = normalizeAttributes(buildAutomaticProfileContextAttributes(), allowReservedKeys = true)
        if (normalized.isEmpty()) return
        // Change-detection: skip the redundant per-launch PATCH when the device context is
        // identical to what was last confirmed delivered for this user.
        val fingerprint = profileContextFingerprint(normalized)
        if (queueStore.loadProfileContextFingerprint(appUserId) == fingerprint) {
            AppActorLogger.debug("Automatic profile context unchanged since last delivery; skipping attribute write.")
            return
        }
        enqueueNormalizedAttributes(appUserId, normalized)
        try {
            // Persist the fingerprint only once nothing is left to send, so a transient failure
            // re-sends. A context key the server rejected was dropped and would only fail again.
            if (flushPending(appUserId)) {
                withOpenQueue { queueStore.saveProfileContextFingerprint(appUserId, fingerprint) }
            }
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            dropQueuedAttributeValues(
                appUserId = appUserId,
                attributes = normalized.mapNotNull { (key, value) ->
                    value?.let { key to it }
                }.toMap(),
            )
            AppActorLogger.debug("Automatic profile context failed permanently; pending context was dropped.")
        }
    }

    suspend fun collectDeviceIdentifiers(appUserId: String) {
        collectAutomaticProfileContext(appUserId)
        setIntegrationIdentifier(
            appUserId = appUserId,
            type = "appactor_install_id",
            value = identityStore.installId,
        )
    }

    private fun buildAutomaticProfileContextAttributes(): Map<String, AppActorAttributeValue> {
        val attributes = buildMap<String, AppActorAttributeValue> {
            putProfileString(AppActorAttributeReservedKeys.bundleId, packageName, MAX_BUNDLE_ID_LENGTH)
            putProfileString(
                AppActorAttributeReservedKeys.locale,
                Locale.getDefault().toLanguageTag(),
                MAX_LOCALE_LENGTH,
            )
            putProfileString(AppActorAttributeReservedKeys.timezone, TimeZone.getDefault().id, MAX_TIMEZONE_LENGTH)
            putProfileString(AppActorAttributeReservedKeys.platform, "android", MAX_PLATFORM_LENGTH)
            platformInfoProvider()?.let { platformInfo ->
                putProfileString(
                    AppActorAttributeReservedKeys.platformFlavor,
                    platformInfo.flavor,
                    MAX_PLATFORM_INFO_LENGTH,
                )
                putProfileString(
                    AppActorAttributeReservedKeys.platformVersion,
                    platformInfo.version,
                    MAX_PLATFORM_INFO_LENGTH,
                )
            }
            putProfileString(AppActorAttributeReservedKeys.deviceModel, Build.MODEL, MAX_DEVICE_MODEL_LENGTH)
            putProfileString(AppActorAttributeReservedKeys.osVersion, Build.VERSION.RELEASE, MAX_VERSION_LENGTH)
            putProfileString(AppActorAttributeReservedKeys.sdkVersion, AppActorSDK.version, MAX_VERSION_LENGTH)
            putProfileString(AppActorAttributeReservedKeys.appVersion, appVersionProvider(), MAX_VERSION_LENGTH)
            normalizeAlpha2Country(countryProvider())?.let {
                put(AppActorAttributeReservedKeys.localeCountry, AppActorAttributeValue.string(it))
            }
        }
        return attributes
    }

    /**
     * Stable fingerprint of an automatic device-attribute bucket. Deterministic across
     * launches (sorted-key canonical form + SHA-256), used to skip the redundant
     * per-launch attribute write when the device context hasn't changed.
     */
    private fun profileContextFingerprint(normalized: Map<String, JsonElement?>): String {
        val canonical = normalized.entries
            .sortedBy { it.key }
            .joinToString("\n") { (key, value) -> "$key=${value?.toString() ?: " "}" }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun MutableMap<String, AppActorAttributeValue>.putProfileString(
        key: String,
        raw: String?,
        maxLength: Int,
    ) {
        val normalized = raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= maxLength } ?: return
        put(key, AppActorAttributeValue.string(normalized))
    }

    suspend fun updateAttribution(
        appUserId: String,
        attribution: AppActorAttribution,
    ) {
        enqueueAttributionRequest(appUserId, attribution.toRequestDTO())
    }

    suspend fun updateCustomAttribution(
        appUserId: String,
        patch: AppActorAttribution,
        clearFields: Set<AppActorCustomAttributionField> = emptySet(),
    ) {
        val patchRequest = patch.toRequestDTO()
        enqueue(appUserId) { existing ->
            existing.copy(attribution = mergeCustomAttribution(appUserId, existing.attribution, patchRequest, clearFields))
        }
        flushPending(appUserId)
    }

    suspend fun enqueueAttributionRequest(
        appUserId: String,
        request: AppActorAttributionRequestDTO,
    ) {
        enqueue(appUserId) { existing -> existing.copy(attribution = request) }
        flushPending(appUserId)
    }

    /**
     * Flushes queued mutations for [appUserId]. Whatever was delivered or dropped leaves the queue
     * when the flush ends, even when a later step failed.
     *
     * A mutation the server rejects for good (400, 409, 413, 422) is logged and dropped, so one
     * bad value can't hold back everything queued behind it or fail every later write, logIn and
     * logOut. The server rejects a whole PATCH for one bad key, so a rejected PATCH is split in
     * halves until the bad keys are isolated.
     *
     * @param waitForRunningFlush `false` returns `false` at once when a flush of this user is
     *   already running, instead of waiting behind it (a 429 backoff can take minutes). That flush
     *   sends the queue, and whatever it misses stays queued.
     * @return `true` when nothing is left to send (everything was delivered or dropped), `false`
     *   when mutations stay queued for a later flush. Throws on any other permanent failure, such
     *   as an invalid API key.
     */
    suspend fun flushPending(
        appUserId: String,
        waitForRunningFlush: Boolean = true,
    ): Boolean {
        val mutex = flushMutex(appUserId)
        if (waitForRunningFlush) {
            mutex.lock()
        } else if (!mutex.tryLock()) {
            return false
        }
        try {
            return flushLocked(appUserId)
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun flushLocked(appUserId: String): Boolean {
        // A manager from before a reset() must not send what the next session queued.
        val pending = withOpenQueue { queueStore.load(appUserId) } ?: return true
        if (pending.isEmpty()) {
            queueMutex.withLock { queueStore.save(appUserId, null) }
            return true
        }

        // What was delivered or dropped so far, removed from the queue in one write at the end.
        var flushed = AppActorQueuedAttributeMutation()
        var attributionDelivered = false
        return try {
            if (pending.attributes.isNotEmpty()) {
                patchIsolatingRejections(appUserId, pending.attributes) { done ->
                    flushed = flushed.copy(attributes = flushed.attributes + done)
                }
            }
            pending.unsetAttributes.forEach { key ->
                deliver {
                    try {
                        backendClient.deleteUserAttribute(appUserId = appUserId, key = key)
                    } catch (throwable: AppActorBackendException.Http) {
                        // The server has no such user yet, so there is nothing to unset.
                        if (throwable.statusCode != 404) throw throwable
                    }
                }
                flushed = flushed.copy(unsetAttributes = flushed.unsetAttributes + key)
            }
            pending.integrationIdentifiers.forEach { (type, value) ->
                deliver {
                    backendClient.postIntegrationIdentifier(
                        appUserId = appUserId,
                        request = AppActorIntegrationIdentifierRequestDTO(
                            type = type,
                            value = value,
                            sdkVersion = AppActorSDK.version,
                            observedAt = AppActorIso8601.format(java.util.Date()),
                        ),
                    )
                }
                flushed = flushed.copy(integrationIdentifiers = flushed.integrationIdentifiers + (type to value))
            }
            pending.unsetIntegrationIdentifiers.forEach { type ->
                deliver { backendClient.deleteIntegrationIdentifier(appUserId = appUserId, type = type) }
                flushed = flushed.copy(unsetIntegrationIdentifiers = flushed.unsetIntegrationIdentifiers + type)
            }
            pending.attribution?.let { request ->
                attributionDelivered = deliver { backendClient.postAttribution(appUserId, request.clippedToServerLimits()) }
                flushed = flushed.copy(attribution = request)
            }
            true
        } catch (throwable: Throwable) {
            throwIfCancellation(throwable)
            val error = throwable.toAppActorError(defaultMessage = "Attribute flush failed.")
            if (!error.isTransient) throw error
            AppActorLogger.debug("Attribute flush failed; pending mutations remain queued.")
            false
        } finally {
            withContext(NonCancellable) {
                removeFlushed(appUserId, flushed, attributionDelivered)
            }
        }
    }

    private fun flushMutex(appUserId: String): Mutex =
        synchronized(flushMutexes) { flushMutexes.getOrPut(appUserId) { Mutex() } }

    /** Sends one request. Returns `false` when the server rejected its payload for good. */
    private suspend fun deliver(send: suspend () -> Unit): Boolean {
        var rejection = rejectionOf(send) ?: return true
        // The server also answers 409 to a replayed signing nonce, which OkHttp sends when it
        // silently retries a request after a connection failure (audit D11). A 409 is final only
        // once a fresh request, with a new nonce, gets it too.
        if (rejection.httpStatusCode == 409) rejection = rejectionOf(send) ?: return true
        AppActorLogger.warn("Customer attribute mutation rejected by the server; dropping it: ${rejection.message}")
        return false
    }

    /** Sends one request. Returns the failure if the server rejected its payload for good. */
    private suspend fun rejectionOf(send: suspend () -> Unit): Throwable? {
        return try {
            send()
            null
        } catch (throwable: Throwable) {
            if (!throwable.isRejectedPayload) throw throwable
            throwable
        }
    }

    /**
     * PATCHes [attributes] and reports them to [onDone] once delivered or dropped. If the server
     * rejects them, the keys are split in halves and each half is sent again, so a single bad key
     * among n costs about 2*log2(n) requests and only the keys the server rejects on their own
     * are dropped.
     */
    private suspend fun patchIsolatingRejections(
        appUserId: String,
        attributes: Map<String, JsonElement>,
        onDone: (Map<String, JsonElement>) -> Unit,
    ) {
        val send: suspend () -> Unit = {
            backendClient.patchUserAttributes(
                appUserId = appUserId,
                request = AppActorAttributesPatchRequestDTO(
                    attributes = attributes,
                    sdkVersion = AppActorSDK.version,
                    observedAt = AppActorIso8601.format(java.util.Date()),
                ),
            )
        }
        if (attributes.size == 1) {
            deliver(send)
        } else if (rejectionOf(send) != null) {
            val keys = attributes.keys.sorted()
            keys.chunked((keys.size + 1) / 2).forEach { half ->
                patchIsolatingRejections(appUserId, attributes.filterKeys { it in half }, onDone)
            }
            return
        }
        onDone(attributes)
    }

    private suspend fun removeFlushed(
        appUserId: String,
        flushed: AppActorQueuedAttributeMutation,
        attributionDelivered: Boolean,
    ) {
        if (flushed.isEmpty()) return
        withOpenQueue {
            queueStore.save(appUserId, queueStore.load(appUserId)?.removeFlushed(flushed))
            // The server replaces the whole attribution on every post, so the helpers merge over
            // the last one it accepted. A rejected one must not stay that base; an install from
            // before this change may have saved it there when it was queued.
            val attribution = flushed.attribution ?: return@withOpenQueue
            if (attributionDelivered) {
                queueStore.saveAttributionSnapshot(appUserId, attribution)
            } else if (queueStore.loadAttributionSnapshot(appUserId) == attribution) {
                queueStore.saveAttributionSnapshot(appUserId, null)
            }
        }
    }

    /**
     * Flushes every user's queue. One user's failure doesn't hold back the others, and the first
     * one is rethrown once all were tried. An auth failure (401, 403) would fail every user alike,
     * so it is rethrown at once.
     */
    suspend fun flushPendingForAllUsers() {
        var firstFailure: Throwable? = null
        queueMutex.withLock { queueStore.pendingAppUserIds() }.forEach { appUserId ->
            try {
                flushPending(appUserId)
            } catch (throwable: Throwable) {
                throwIfCancellation(throwable)
                if (throwable.httpStatusCode in AUTH_FAILURE_STATUS_CODES) throw throwable
                if (firstFailure == null) firstFailure = throwable
            }
        }
        firstFailure?.let { throw it }
    }

    /** Runs [block] under the queue lock, unless reset() cleared this manager: then it does nothing. */
    private suspend inline fun <T> withOpenQueue(block: () -> T): T? =
        queueMutex.withLock { if (isCleared) null else block() }

    suspend fun clearQueue() {
        queueMutex.withLock {
            isCleared = true
            queueStore.clearAll()
        }
    }

    private suspend fun enqueue(
        appUserId: String,
        transform: (AppActorQueuedAttributeMutation) -> AppActorQueuedAttributeMutation,
    ) {
        withOpenQueue {
            val existing = queueStore.load(appUserId) ?: AppActorQueuedAttributeMutation()
            val updated = transform(existing)
            queueStore.save(appUserId, updated.takeBounded())
        }
    }

    private suspend fun enqueueNormalizedAttributes(
        appUserId: String,
        normalized: Map<String, JsonElement?>,
    ) {
        enqueue(appUserId) { existing ->
            val nextAttributes = existing.attributes.toMutableMap()
            val nextUnset = existing.unsetAttributes.toMutableSet()
            normalized.forEach { (key, value) ->
                if (value == null) {
                    nextAttributes.remove(key)
                    nextUnset += key
                } else {
                    nextAttributes[key] = value
                    nextUnset -= key
                }
            }
            existing.copy(
                attributes = nextAttributes.takeLastBounded(MAX_PENDING_ATTRIBUTES),
                unsetAttributes = nextUnset.takeLastBounded(MAX_PENDING_ATTRIBUTES),
            )
        }
    }

    private suspend fun dropQueuedAttributeValues(
        appUserId: String,
        attributes: Map<String, JsonElement>,
    ) {
        if (attributes.isEmpty()) return
        withOpenQueue {
            val current = queueStore.load(appUserId) ?: return@withOpenQueue
            queueStore.save(
                appUserId,
                current.copy(
                    attributes = current.attributes.filterNot { (key, value) ->
                        attributes[key] == value
                    },
                ),
            )
        }
    }

    private fun normalizeAttributes(
        attributes: Map<String, AppActorAttributeValue?>,
        allowReservedKeys: Boolean,
    ): Map<String, kotlinx.serialization.json.JsonElement?> {
        return attributes.mapKeys { (key, _) ->
            if (allowReservedKeys) {
                AppActorAttributesValidation.normalizeReservedKey(key)
            } else {
                AppActorAttributesValidation.normalizeCustomKey(key)
            }
        }.mapValues { (_, value) ->
            value?.also(AppActorAttributesValidation::validateValue)?.toAttributePatchElement()
        }
    }

    private fun AppActorAttribution.toRequestDTO(): AppActorAttributionRequestDTO {
        return AppActorAttributionRequestDTO(
            provider = provider.trim(),
            status = status?.trim()?.takeIf { it.isNotEmpty() },
            providerName = providerName?.trim()?.takeIf { it.isNotEmpty() },
            campaignId = campaignId?.trim()?.takeIf { it.isNotEmpty() },
            campaignName = campaignName?.trim()?.takeIf { it.isNotEmpty() },
            adGroupId = adGroupId?.trim()?.takeIf { it.isNotEmpty() },
            adGroupName = adGroupName?.trim()?.takeIf { it.isNotEmpty() },
            adId = adId?.trim()?.takeIf { it.isNotEmpty() },
            adName = adName?.trim()?.takeIf { it.isNotEmpty() },
            creativeId = creativeId?.trim()?.takeIf { it.isNotEmpty() },
            creativeName = creativeName?.trim()?.takeIf { it.isNotEmpty() },
            keywordId = keywordId?.trim()?.takeIf { it.isNotEmpty() },
            network = network?.trim()?.takeIf { it.isNotEmpty() },
            campaign = campaign?.trim()?.takeIf { it.isNotEmpty() } ?: campaignName?.trim()?.takeIf { it.isNotEmpty() },
            adGroup = adGroup?.trim()?.takeIf { it.isNotEmpty() } ?: adGroupName?.trim()?.takeIf { it.isNotEmpty() },
            ad = ad?.trim()?.takeIf { it.isNotEmpty() } ?: adName?.trim()?.takeIf { it.isNotEmpty() },
            creative = creative?.trim()?.takeIf { it.isNotEmpty() } ?: creativeName?.trim()?.takeIf { it.isNotEmpty() },
            keyword = keyword?.trim()?.takeIf { it.isNotEmpty() },
            source = source?.trim()?.takeIf { it.isNotEmpty() },
            medium = medium?.trim()?.takeIf { it.isNotEmpty() },
            clickId = clickId?.trim()?.takeIf { it.isNotEmpty() },
            identifiers = identifiers.mapKeys { (key, _) ->
                AppActorAttributesValidation.normalizeIntegrationIdentifierType(key)
            }.filterValues { it.isNotBlank() },
            metadata = metadata.mapKeys { (key, _) ->
                AppActorAttributesValidation.normalizeCustomKey(key)
            }.mapValues { (_, value) ->
                AppActorAttributesValidation.validateValue(value)
                value.toJsonElement()
            },
            attributedAt = attributedAt?.let(AppActorIso8601::format),
            observedAt = observedAt?.let(AppActorIso8601::format),
            sdkVersion = AppActorSDK.version,
        )
    }

    private fun AppActorQueuedAttributeMutation.takeBounded(): AppActorQueuedAttributeMutation {
        return copy(
            attributes = attributes.takeLastBounded(MAX_PENDING_ATTRIBUTES),
            unsetAttributes = unsetAttributes.takeLastBounded(MAX_PENDING_ATTRIBUTES),
            integrationIdentifiers = integrationIdentifiers.takeLastBounded(MAX_PENDING_INTEGRATION_IDENTIFIERS),
            unsetIntegrationIdentifiers = unsetIntegrationIdentifiers.takeLastBounded(MAX_PENDING_INTEGRATION_IDENTIFIERS),
            attribution = attribution,
        )
    }

    private fun AppActorQueuedAttributeMutation.removeFlushed(
        flushed: AppActorQueuedAttributeMutation,
    ): AppActorQueuedAttributeMutation =
        copy(
            attributes = attributes.filterNot { (key, value) -> flushed.attributes[key] == value },
            unsetAttributes = unsetAttributes.filterNot { key -> key in flushed.unsetAttributes },
            integrationIdentifiers = integrationIdentifiers.filterNot { (type, value) ->
                flushed.integrationIdentifiers[type] == value
            },
            unsetIntegrationIdentifiers = unsetIntegrationIdentifiers.filterNot { type ->
                type in flushed.unsetIntegrationIdentifiers
            },
            attribution = attribution.takeUnless { attribution == flushed.attribution },
        ).takeBounded()

    private fun <K, V> Map<K, V>.takeLastBounded(max: Int): Map<K, V> {
        if (size <= max) return this
        return entries.toList().takeLast(max).associate { it.key to it.value }
    }

    private fun mergeCustomAttribution(
        appUserId: String,
        queuedAttribution: AppActorAttributionRequestDTO?,
        patch: AppActorAttributionRequestDTO,
        clearFields: Set<AppActorCustomAttributionField>,
    ): AppActorAttributionRequestDTO {
        // Merge over the attribution still waiting to be sent, else over the last one delivered.
        val existing = queuedAttribution ?: queueStore.loadAttributionSnapshot(appUserId)
        return AppActorAttributionRequestDTO(
            provider = patch.provider,
            status = patch.status ?: existing?.status,
            providerName = if (AppActorCustomAttributionField.MediaSource in clearFields) null else patch.providerName ?: existing?.providerName,
            campaignId = patch.campaignId ?: existing?.campaignId,
            campaignName = if (AppActorCustomAttributionField.Campaign in clearFields) null else patch.campaignName ?: existing?.campaignName,
            adGroupId = patch.adGroupId ?: existing?.adGroupId,
            adGroupName = if (AppActorCustomAttributionField.AdGroup in clearFields) null else patch.adGroupName ?: existing?.adGroupName,
            adId = patch.adId ?: existing?.adId,
            adName = if (AppActorCustomAttributionField.Ad in clearFields) null else patch.adName ?: existing?.adName,
            creativeId = patch.creativeId ?: existing?.creativeId,
            creativeName = if (AppActorCustomAttributionField.Creative in clearFields) null else patch.creativeName ?: existing?.creativeName,
            keywordId = patch.keywordId ?: existing?.keywordId,
            network = if (AppActorCustomAttributionField.MediaSource in clearFields) null else patch.network ?: existing?.network,
            campaign = if (AppActorCustomAttributionField.Campaign in clearFields) null else patch.campaign ?: existing?.campaign,
            adGroup = if (AppActorCustomAttributionField.AdGroup in clearFields) null else patch.adGroup ?: existing?.adGroup,
            ad = if (AppActorCustomAttributionField.Ad in clearFields) null else patch.ad ?: existing?.ad,
            creative = if (AppActorCustomAttributionField.Creative in clearFields) null else patch.creative ?: existing?.creative,
            keyword = if (AppActorCustomAttributionField.Keyword in clearFields) null else patch.keyword ?: existing?.keyword,
            source = if (AppActorCustomAttributionField.MediaSource in clearFields) null else patch.source ?: existing?.source,
            medium = patch.medium ?: existing?.medium,
            clickId = patch.clickId ?: existing?.clickId,
            identifiers = (existing?.identifiers ?: emptyMap()) + patch.identifiers,
            metadata = (existing?.metadata ?: emptyMap()) + patch.metadata,
            attributedAt = patch.attributedAt ?: existing?.attributedAt,
            observedAt = patch.observedAt ?: existing?.observedAt,
            sdkVersion = patch.sdkVersion ?: existing?.sdkVersion,
        )
    }

    private fun <T> Iterable<T>.takeLastBounded(max: Int): List<T> {
        val list = toList()
        if (list.size <= max) return list
        return list.takeLast(max)
    }

    private val Throwable.isRejectedPayload: Boolean
        get() = httpStatusCode in REJECTED_PAYLOAD_STATUS_CODES

    /** The HTTP status behind this failure, also when flushPending wrapped it in an AppActorError. */
    private val Throwable.httpStatusCode: Int?
        get() = ((this as? AppActorError)?.cause ?: this).let { (it as? AppActorBackendException.Http)?.statusCode }

    private companion object {
        /** Statuses with which the server rejects a payload for good; resending can't succeed. */
        val REJECTED_PAYLOAD_STATUS_CODES = setOf(400, 409, 413, 422)
        val AUTH_FAILURE_STATUS_CODES = setOf(401, 403)
        const val MAX_PENDING_ATTRIBUTES = 100
        const val MAX_PENDING_INTEGRATION_IDENTIFIERS = 50
        private const val MAX_PLATFORM_LENGTH = 24
        private const val MAX_PLATFORM_INFO_LENGTH = 50
        private const val MAX_VERSION_LENGTH = 64
        private const val MAX_DEVICE_MODEL_LENGTH = 120
        private const val MAX_BUNDLE_ID_LENGTH = 255
        private const val MAX_LOCALE_LENGTH = 32
        private const val MAX_TIMEZONE_LENGTH = 80
    }
}

private val ALPHA_2_COUNTRY = Regex("^[A-Z]{2}$")

/**
 * The ISO 3166-1 alpha-2 code in [raw], or null. Locale can report a UN M.49 region such as
 * "419", which the backend rejects.
 */
internal fun normalizeAlpha2Country(raw: String?): String? {
    val normalized = raw?.trim()?.uppercase(Locale.US).orEmpty()
    return normalized.takeIf { ALPHA_2_COUNTRY.matches(it) }
}

internal enum class AppActorCustomAttributionField {
    MediaSource,
    Campaign,
    AdGroup,
    Ad,
    Keyword,
    Creative,
}
