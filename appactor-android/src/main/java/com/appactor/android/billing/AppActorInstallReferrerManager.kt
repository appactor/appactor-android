package com.appactor.android.billing

import android.content.Context
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import com.appactor.android.backend.dto.AppActorAttributionRequestDTO
import com.appactor.android.internal.logging.AppActorLogger
import com.appactor.android.internal.AppActorSDK
import com.appactor.android.managers.AppActorAttributesManager
import com.appactor.android.models.AppActorIso8601
import com.appactor.android.storage.AppActorIdentityStore
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Date
import kotlin.coroutines.resume

internal class AppActorInstallReferrerManager(
    private val context: Context,
    private val identityStore: AppActorIdentityStore,
    private val attributesManager: AppActorAttributesManager? = null,
) {

    suspend fun fetchReferrerOnce(): String? {
        if (!identityStore.installReferrer.isNullOrBlank()) {
            return identityStore.installReferrer
        }

        val appUserId = identityStore.currentAppUserId ?: identityStore.ensureAppUserId()
        val details = connectAndFetchReferrer() ?: return null
        return persistAndSendReferrer(
            appUserId = appUserId,
            details = details,
        )
    }

    internal suspend fun persistAndSendReferrer(
        appUserId: String,
        details: AppActorInstallReferrerDetails,
    ): String? {
        val rawReferrer = details.installReferrer.takeIf { it.isNotBlank() } ?: return null
        val referrerHash = sha256(rawReferrer)
        val persistedValue = "sha256:$referrerHash"
        val delivered = attributesManager?.postAttributionBestEffort(
            appUserId = appUserId,
            request = details.toAttributionRequest(referrerHash),
        ) ?: true
        if (!delivered) return null
        identityStore.setInstallReferrer(persistedValue)
        return persistedValue
    }

    private suspend fun connectAndFetchReferrer(): AppActorInstallReferrerDetails? {
        return suspendCancellableCoroutine { continuation ->
            val client = InstallReferrerClient.newBuilder(context).build()
            continuation.invokeOnCancellation {
                runCatching { client.endConnection() }
            }
            client.startConnection(object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(responseCode: Int) {
                    val result = when (responseCode) {
                        InstallReferrerClient.InstallReferrerResponse.OK -> {
                            runCatching {
                                client.installReferrer?.let { response ->
                                    AppActorInstallReferrerDetails(
                                        installReferrer = response.installReferrer.orEmpty(),
                                        referrerClickTimestampSeconds = response.referrerClickTimestampSeconds,
                                        installBeginTimestampSeconds = response.installBeginTimestampSeconds,
                                        googlePlayInstant = response.googlePlayInstantParam,
                                    )
                                }
                            }.getOrNull()
                        }
                        else -> {
                            AppActorLogger.debug(
                                "Install referrer setup failed with code: $responseCode"
                            )
                            null
                        }
                    }
                    runCatching { client.endConnection() }
                    if (continuation.isActive) {
                        continuation.resume(result)
                    }
                }

                override fun onInstallReferrerServiceDisconnected() {
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }
            })
        }
    }

    private fun AppActorInstallReferrerDetails.toAttributionRequest(
        referrerHash: String,
    ): AppActorAttributionRequestDTO {
        val parsed = parseReferrerParams(installReferrer)
        val metadata = linkedMapOf<String, JsonElement>(
            "installReferrerHash" to JsonPrimitive(referrerHash),
            "sdkVersion" to JsonPrimitive(AppActorSDK.version),
        )
        referrerClickTimestampSeconds?.takeIf { it > 0 }?.let {
            metadata["referrerClickTimestampSeconds"] = JsonPrimitive(it)
        }
        installBeginTimestampSeconds?.takeIf { it > 0 }?.let {
            metadata["installBeginTimestampSeconds"] = JsonPrimitive(it)
        }
        googlePlayInstant?.let {
            metadata["googlePlayInstant"] = JsonPrimitive(it)
        }
        REFERRER_METADATA_KEYS.forEach { key ->
            parsed[key]?.takeIf { it.isNotBlank() }?.let { value ->
                metadata[key] = JsonPrimitive(value.take(MAX_REFERRER_METADATA_LENGTH))
            }
        }

        val identifiers = REFERRER_IDENTIFIER_KEYS.mapNotNull { key ->
            parsed[key]?.takeIf { it.isNotBlank() }?.let { value -> "${key}Sha256" to sha256(value) }
        }.toMap()

        return AppActorAttributionRequestDTO(
            provider = "google_play_install_referrer",
            source = parsed["utm_source"]?.takeIf { it.isNotBlank() },
            medium = parsed["utm_medium"]?.takeIf { it.isNotBlank() },
            campaign = parsed["utm_campaign"]?.takeIf { it.isNotBlank() },
            identifiers = identifiers,
            metadata = metadata,
            observedAt = AppActorIso8601.format(Date()),
            sdkVersion = AppActorSDK.version,
        )
    }

    private fun parseReferrerParams(raw: String): Map<String, String> {
        return raw.split("&")
            .mapNotNull { pair ->
                val index = pair.indexOf("=")
                if (index <= 0) return@mapNotNull null
                val key = decode(pair.substring(0, index)).trim()
                val value = decode(pair.substring(index + 1)).trim()
                if (key.isEmpty() || value.isEmpty()) null else key to value
            }
            .toMap()
    }

    private fun decode(value: String): String {
        return runCatching { URLDecoder.decode(value, Charsets.UTF_8.name()) }
            .getOrElse { value }
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MAX_REFERRER_METADATA_LENGTH = 256
        val REFERRER_METADATA_KEYS = setOf(
            "utm_source",
            "utm_medium",
            "utm_campaign",
            "utm_term",
            "utm_content",
        )
        val REFERRER_IDENTIFIER_KEYS = setOf(
            "gclid",
            "gbraid",
            "wbraid",
        )
    }
}

internal data class AppActorInstallReferrerDetails(
    val installReferrer: String,
    val referrerClickTimestampSeconds: Long? = null,
    val installBeginTimestampSeconds: Long? = null,
    val googlePlayInstant: Boolean? = null,
)
