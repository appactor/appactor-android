package com.appactor.android.managers

import android.os.Build
import com.appactor.android.backend.client.AppActorBackendClient
import com.appactor.android.backend.dto.AppActorAttributionRequestDTO
import com.appactor.android.backend.dto.AppActorAttributesPatchRequestDTO
import com.appactor.android.backend.dto.AppActorIntegrationIdentifierRequestDTO
import com.appactor.android.internal.AppActorSDK
import com.appactor.android.internal.logging.AppActorLogger
import com.appactor.android.models.AppActorAttributeReservedKeys
import com.appactor.android.models.AppActorAttributeValue
import com.appactor.android.models.AppActorAttributesValidation
import com.appactor.android.models.AppActorAttribution
import com.appactor.android.models.AppActorIso8601
import com.appactor.android.models.AppActorPlatformInfo
import com.appactor.android.storage.AppActorAttributeQueueStore
import com.appactor.android.storage.AppActorIdentityStore
import com.appactor.android.storage.AppActorQueuedAttributeMutation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import java.util.TimeZone

internal class AppActorAttributesManager(
    private val backendClient: AppActorBackendClient,
    private val queueStore: AppActorAttributeQueueStore,
    private val identityStore: AppActorIdentityStore,
    private val packageName: String,
    private val appVersionProvider: () -> String?,
    private val countryProvider: () -> String?,
) {
    private val queueMutex = Mutex()
    private var customAttributionSnapshots: MutableMap<String, AppActorAttributionRequestDTO> = mutableMapOf()

    suspend fun setAttributes(
        appUserId: String,
        attributes: Map<String, AppActorAttributeValue?>,
        allowReservedKeys: Boolean = false,
    ) {
        val normalized = normalizeAttributes(attributes, allowReservedKeys)
        if (normalized.isEmpty()) return
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
        value: String,
    ) {
        val normalizedType = AppActorAttributesValidation.normalizeIntegrationIdentifierType(type)
        AppActorAttributesValidation.validateIntegrationIdentifierValue(value)
        enqueue(appUserId) { existing ->
            existing.copy(
                integrationIdentifiers = (existing.integrationIdentifiers + (normalizedType to value))
                    .takeLastBounded(MAX_PENDING_INTEGRATION_IDENTIFIERS),
            )
        }
        flushPending(appUserId)
    }

    suspend fun collectDeviceIdentifiers(appUserId: String) {
        val attributes = buildMap<String, AppActorAttributeValue> {
            put(AppActorAttributeReservedKeys.bundleId, AppActorAttributeValue.string(packageName))
            put(AppActorAttributeReservedKeys.locale, AppActorAttributeValue.string(Locale.getDefault().toLanguageTag()))
            put(AppActorAttributeReservedKeys.timezone, AppActorAttributeValue.string(TimeZone.getDefault().id))
            put(AppActorAttributeReservedKeys.platform, AppActorAttributeValue.string("android"))
            Build.MODEL?.takeIf { it.isNotBlank() }?.let {
                put(AppActorAttributeReservedKeys.deviceModel, AppActorAttributeValue.string(it))
            }
            Build.VERSION.RELEASE?.takeIf { it.isNotBlank() }?.let {
                put(AppActorAttributeReservedKeys.osVersion, AppActorAttributeValue.string(it))
            }
            put(AppActorAttributeReservedKeys.sdkVersion, AppActorAttributeValue.string(AppActorSDK.version))
            appVersionProvider()?.takeIf { it.isNotBlank() }?.let {
                put(AppActorAttributeReservedKeys.appVersion, AppActorAttributeValue.string(it))
            }
            countryProvider()?.takeIf { it.isNotBlank() }?.let {
                put(AppActorAttributeReservedKeys.storefrontCountry, AppActorAttributeValue.string(it))
            }
        }
        setAttributes(appUserId = appUserId, attributes = attributes, allowReservedKeys = true)
        setIntegrationIdentifier(
            appUserId = appUserId,
            type = "appactor_install_id",
            value = identityStore.installId,
        )
    }

    suspend fun updateAttribution(
        appUserId: String,
        attribution: AppActorAttribution,
    ) {
        val request = attribution.toRequestDTO()
        enqueue(appUserId) { existing ->
            existing.copy(attribution = request)
        }
        flushPending(appUserId)
    }

    suspend fun updateCustomAttribution(
        appUserId: String,
        patch: AppActorAttribution,
    ) {
        val patchRequest = patch.toRequestDTO()
        enqueue(appUserId) { existing ->
            val merged = mergeCustomAttribution(appUserId, existing.attribution, patchRequest)
            customAttributionSnapshots[appUserId] = merged
            existing.copy(attribution = merged)
        }
        flushPending(appUserId)
    }

    suspend fun postAttributionBestEffort(
        appUserId: String,
        request: AppActorAttributionRequestDTO,
    ): Boolean {
        return try {
            backendClient.postAttribution(appUserId, request)
            true
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            AppActorLogger.debug("Attribution update failed; payload was not logged.")
            false
        }
    }

    suspend fun flushPending(appUserId: String) {
        val pending = queueMutex.withLock { queueStore.load(appUserId) } ?: return
        if (pending.isEmpty()) {
            queueMutex.withLock { queueStore.save(appUserId, null) }
            return
        }

        try {
            if (pending.attributes.isNotEmpty()) {
                backendClient.patchUserAttributes(
                    appUserId = appUserId,
                    request = AppActorAttributesPatchRequestDTO(
                        attributes = pending.attributes,
                        sdkVersion = AppActorSDK.version,
                        observedAt = AppActorIso8601.format(java.util.Date()),
                    ),
                )
            }
            pending.unsetAttributes.forEach { key ->
                backendClient.deleteUserAttribute(appUserId = appUserId, key = key)
            }
            pending.integrationIdentifiers.forEach { (type, value) ->
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
            pending.attribution?.let { request ->
                backendClient.postAttribution(appUserId, request)
            }
            queueMutex.withLock {
                val current = queueStore.load(appUserId)
                queueStore.save(appUserId, current?.removeFlushed(pending))
            }
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            AppActorLogger.debug("Attribute flush failed; pending mutations remain queued.")
        }
    }

    fun clearQueue() {
        customAttributionSnapshots.clear()
        queueStore.clearAll()
    }

    private suspend fun enqueue(
        appUserId: String,
        transform: (AppActorQueuedAttributeMutation) -> AppActorQueuedAttributeMutation,
    ) {
        queueMutex.withLock {
            val existing = queueStore.load(appUserId) ?: AppActorQueuedAttributeMutation()
            val updated = transform(existing)
            queueStore.save(appUserId, updated.takeBounded())
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
            value?.also(AppActorAttributesValidation::validateValue)?.toJsonElement()
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
    ): AppActorAttributionRequestDTO {
        val existing = customAttributionSnapshots[appUserId] ?: queuedAttribution
        return AppActorAttributionRequestDTO(
            provider = patch.provider,
            status = patch.status ?: existing?.status,
            providerName = patch.providerName ?: existing?.providerName,
            campaignId = patch.campaignId ?: existing?.campaignId,
            campaignName = patch.campaignName ?: existing?.campaignName,
            adGroupId = patch.adGroupId ?: existing?.adGroupId,
            adGroupName = patch.adGroupName ?: existing?.adGroupName,
            adId = patch.adId ?: existing?.adId,
            adName = patch.adName ?: existing?.adName,
            creativeId = patch.creativeId ?: existing?.creativeId,
            creativeName = patch.creativeName ?: existing?.creativeName,
            keywordId = patch.keywordId ?: existing?.keywordId,
            network = patch.network ?: existing?.network,
            campaign = patch.campaign ?: existing?.campaign,
            adGroup = patch.adGroup ?: existing?.adGroup,
            ad = patch.ad ?: existing?.ad,
            creative = patch.creative ?: existing?.creative,
            keyword = patch.keyword ?: existing?.keyword,
            source = patch.source ?: existing?.source,
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

    private companion object {
        const val MAX_PENDING_ATTRIBUTES = 100
        const val MAX_PENDING_INTEGRATION_IDENTIFIERS = 50
    }
}
