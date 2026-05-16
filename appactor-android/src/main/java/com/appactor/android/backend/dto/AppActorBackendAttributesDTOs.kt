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
