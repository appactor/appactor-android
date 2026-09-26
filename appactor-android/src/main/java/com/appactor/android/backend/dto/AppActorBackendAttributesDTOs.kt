package com.appactor.android.backend.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonElement

@Serializable
internal data class AppActorAttributesPatchRequestDTO(
    val attributes: Map<String, JsonElement> = emptyMap(),
    @SerialName("unset_attributes")
    val unsetAttributes: List<String> = emptyList(),
    val source: String = "android_sdk",
    @SerialName("sdk_version")
    val sdkVersion: String? = null,
    @SerialName("observed_at")
    val observedAt: String? = null,
)

@Serializable
internal data class AppActorIntegrationIdentifierRequestDTO(
    val type: String,
    val value: String,
    val source: String = "android_sdk",
    @SerialName("sdk_version")
    val sdkVersion: String? = null,
    @SerialName("observed_at")
    val observedAt: String? = null,
)

@Serializable
internal data class AppActorAttributionRequestDTO(
    val provider: String,
    val status: String? = null,
    @SerialName("provider_name")
    val providerName: String? = null,
    @SerialName("campaign_id")
    val campaignId: String? = null,
    @SerialName("campaign_name")
    val campaignName: String? = null,
    @SerialName("ad_group_id")
    val adGroupId: String? = null,
    @SerialName("ad_group_name")
    val adGroupName: String? = null,
    @SerialName("ad_id")
    val adId: String? = null,
    @SerialName("ad_name")
    val adName: String? = null,
    @SerialName("creative_id")
    val creativeId: String? = null,
    @SerialName("creative_name")
    val creativeName: String? = null,
    @SerialName("keyword_id")
    val keywordId: String? = null,
    val network: String? = null,
    val campaign: String? = null,
    @SerialName("ad_group")
    val adGroup: String? = null,
    val ad: String? = null,
    val creative: String? = null,
    val keyword: String? = null,
    val source: String? = null,
    val medium: String? = null,
    @SerialName("click_id")
    val clickId: String? = null,
    val identifiers: Map<String, String> = emptyMap(),
    val metadata: Map<String, JsonElement> = emptyMap(),
    @SerialName("attributed_at")
    val attributedAt: String? = null,
    @SerialName("observed_at")
    val observedAt: String? = null,
    @SerialName("sdk_version")
    val sdkVersion: String? = null,
)

/**
 * The request with each field the backend reads trimmed and cut to the length it accepts
 * (identity-primitives.service.ts). The backend answers a longer value with a 400 rather than
 * cutting it, which loses the whole attribution, and a referrer's utm_source or utm_campaign is
 * outside the app's control. The backend takes a missing source from network, so network is
 * sent as the source too, cut to the source's limit.
 */
internal fun AppActorAttributionRequestDTO.clippedToServerLimits(): AppActorAttributionRequestDTO = copy(
    status = status.clippedTo(TOKEN_LIMIT),
    providerName = providerName.clippedTo(ID_LIMIT),
    campaignId = campaignId.clippedTo(ID_LIMIT),
    campaignName = campaignName.clippedTo(NAME_LIMIT),
    adGroupId = adGroupId.clippedTo(ID_LIMIT),
    adGroupName = adGroupName.clippedTo(NAME_LIMIT),
    adId = adId.clippedTo(ID_LIMIT),
    adName = adName.clippedTo(NAME_LIMIT),
    creativeId = creativeId.clippedTo(ID_LIMIT),
    creativeName = creativeName.clippedTo(NAME_LIMIT),
    keywordId = keywordId.clippedTo(ID_LIMIT),
    network = network.clippedTo(ID_LIMIT),
    campaign = campaign.clippedTo(NAME_LIMIT),
    adGroup = adGroup.clippedTo(NAME_LIMIT),
    ad = ad.clippedTo(NAME_LIMIT),
    creative = creative.clippedTo(NAME_LIMIT),
    keyword = keyword.clippedTo(NAME_LIMIT),
    source = (source?.takeIf { it.isNotBlank() } ?: network).clippedTo(TOKEN_LIMIT),
    clickId = clickId.clippedTo(NAME_LIMIT),
)

private const val TOKEN_LIMIT = 64
private const val ID_LIMIT = 120
private const val NAME_LIMIT = 255

// In UTF-16 units, as the backend counts, without splitting a surrogate pair.
private fun String?.clippedTo(maxLength: Int): String? {
    val trimmed = this?.trim()?.takeIf { it.isNotEmpty() } ?: return this
    if (trimmed.length <= maxLength) return trimmed
    val end = if (trimmed[maxLength - 1].isHighSurrogate()) maxLength - 1 else maxLength
    return trimmed.substring(0, end)
}

