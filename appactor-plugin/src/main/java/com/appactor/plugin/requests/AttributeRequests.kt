package com.appactor.plugin.requests

import com.appactor.android.api.AppActor
import com.appactor.android.models.AppActorAttributeValue
import com.appactor.android.models.AppActorAttribution
import com.appactor.plugin.infrastructure.PluginCoder
import com.appactor.plugin.infrastructure.PluginRequest
import com.appactor.plugin.infrastructure.PluginRequestFactory
import com.appactor.plugin.infrastructure.PluginResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal class SetAttributesRequest private constructor(
    private val attributes: Map<String, AppActorAttributeValue?>,
) : PluginRequest {

    override suspend fun execute(): PluginResult {
        AppActor.setAttributes(attributes)
        return PluginResult.successVoid
    }

    companion object : PluginRequestFactory {
        override val method: String = "set_attributes"
        override fun create(json: String): PluginRequest {
            val p = PluginCoder.json.decodeFromString(Params.serializer(), json)
            return SetAttributesRequest(AttributePluginParsing.toAttributeMap(p.attributes))
        }

        @Serializable
        private data class Params(val attributes: Map<String, JsonElement>)
    }
}

internal class SetAttributeRequest private constructor(
    private val key: String,
    private val value: AppActorAttributeValue,
) : PluginRequest {

    override suspend fun execute(): PluginResult {
        AppActor.setAttribute(key, value)
        return PluginResult.successVoid
    }

    companion object : PluginRequestFactory {
        override val method: String = "set_attribute"
        override fun create(json: String): PluginRequest {
            val p = PluginCoder.json.decodeFromString(Params.serializer(), json)
            val value = AttributePluginParsing.toAttributeValue(p.value)
                ?: throw IllegalArgumentException("set_attribute value must not be null; use unset_attribute.")
            return SetAttributeRequest(p.key, value)
        }

        @Serializable
        private data class Params(
            val key: String,
            val value: JsonElement,
        )
    }
}

internal class UnsetAttributeRequest private constructor(
    private val key: String,
) : PluginRequest {

    override suspend fun execute(): PluginResult {
        AppActor.unsetAttribute(key)
        return PluginResult.successVoid
    }

    companion object : PluginRequestFactory {
        override val method: String = "unset_attribute"
        override fun create(json: String): PluginRequest {
            val p = PluginCoder.json.decodeFromString(Params.serializer(), json)
            return UnsetAttributeRequest(p.key)
        }

        @Serializable
        private data class Params(val key: String)
    }
}

internal class SetEmailRequest private constructor(
    private val value: String?,
) : PluginRequest {

    override suspend fun execute(): PluginResult {
        AppActor.setEmail(value)
        return PluginResult.successVoid
    }

    companion object : PluginRequestFactory {
        override val method: String = "set_email"
        override fun create(json: String): PluginRequest {
            val p = PluginCoder.json.decodeFromString(SetEmailParams.serializer(), json)
            return SetEmailRequest(p.value ?: p.email)
        }

        @Serializable
        private data class SetEmailParams(
            val value: String? = null,
            val email: String? = null,
        )
    }
}

internal class SetDisplayNameRequest private constructor(
    private val value: String?,
) : PluginRequest {

    override suspend fun execute(): PluginResult {
        AppActor.setDisplayName(value)
        return PluginResult.successVoid
    }

    companion object : PluginRequestFactory {
        override val method: String = "set_display_name"
        override fun create(json: String): PluginRequest {
            val p = PluginCoder.json.decodeFromString(SetDisplayNameParams.serializer(), json)
            return SetDisplayNameRequest(p.value ?: p.displayName)
        }

        @Serializable
        private data class SetDisplayNameParams(
            val value: String? = null,
            @SerialName("display_name") val displayName: String? = null,
        )
    }
}

internal class SetPhoneNumberRequest private constructor(
    private val value: String?,
) : PluginRequest {

    override suspend fun execute(): PluginResult {
        AppActor.setPhoneNumber(value)
        return PluginResult.successVoid
    }

    companion object : PluginRequestFactory {
        override val method: String = "set_phone_number"
        override fun create(json: String): PluginRequest {
            val p = PluginCoder.json.decodeFromString(SetPhoneNumberParams.serializer(), json)
            return SetPhoneNumberRequest(p.value ?: p.phoneNumber)
        }

        @Serializable
        private data class SetPhoneNumberParams(
            val value: String? = null,
            @SerialName("phone_number") val phoneNumber: String? = null,
        )
    }
}

internal class SetPushTokenRequest private constructor(
    private val value: String?,
) : PluginRequest {

    override suspend fun execute(): PluginResult {
        AppActor.setPushToken(value)
        return PluginResult.successVoid
    }

    companion object : PluginRequestFactory {
        override val method: String = "set_push_token"
        override fun create(json: String): PluginRequest {
            val p = PluginCoder.json.decodeFromString(SetPushTokenParams.serializer(), json)
            return SetPushTokenRequest(p.value ?: p.pushToken)
        }

        @Serializable
        private data class SetPushTokenParams(
            val value: String? = null,
            @SerialName("push_token") val pushToken: String? = null,
        )
    }
}

internal class CollectDeviceIdentifiersRequest : PluginRequest {

    override suspend fun execute(): PluginResult {
        AppActor.collectDeviceIdentifiers()
        return PluginResult.successVoid
    }

    companion object : PluginRequestFactory {
        override val method: String = "collect_device_identifiers"
        override fun create(json: String): PluginRequest = CollectDeviceIdentifiersRequest()
    }
}

internal class SetIntegrationIdentifierRequest private constructor(
    private val type: String,
    private val value: String,
) : PluginRequest {

    override suspend fun execute(): PluginResult {
        AppActor.setIntegrationIdentifier(type, value)
        return PluginResult.successVoid
    }

    companion object : PluginRequestFactory {
        override val method: String = "set_integration_identifier"
        override fun create(json: String): PluginRequest {
            val p = PluginCoder.json.decodeFromString(Params.serializer(), json)
            return SetIntegrationIdentifierRequest(p.type, p.value)
        }

        @Serializable
        private data class Params(
            val type: String,
            val value: String,
        )
    }
}

internal class UpdateAttributionRequest private constructor(
    private val attribution: AppActorAttribution,
) : PluginRequest {

    override suspend fun execute(): PluginResult {
        AppActor.updateAttribution(attribution)
        return PluginResult.successVoid
    }

    companion object : PluginRequestFactory {
        override val method: String = "update_attribution"
        override fun create(json: String): PluginRequest {
            val p = PluginCoder.json.decodeFromString(Params.serializer(), json)
            return UpdateAttributionRequest(
                AppActorAttribution(
                    provider = p.provider,
                    status = p.status,
                    providerName = p.providerName,
                    campaignId = p.campaignId,
                    campaignName = p.campaignName,
                    adGroupId = p.adGroupId,
                    adGroupName = p.adGroupName,
                    adId = p.adId,
                    adName = p.adName,
                    creativeId = p.creativeId,
                    creativeName = p.creativeName,
                    keywordId = p.keywordId,
                    network = p.network,
                    campaign = p.campaign ?: p.campaignName,
                    adGroup = p.adGroup ?: p.adGroupName,
                    ad = p.ad ?: p.adName,
                    creative = p.creative ?: p.creativeName,
                    keyword = p.keyword,
                    source = p.source,
                    medium = p.medium,
                    clickId = p.clickId,
                    identifiers = p.identifiers,
                    metadata = AttributePluginParsing.toNonNullAttributeMap(p.metadata),
                    attributedAt = AttributePluginParsing.parseIsoDate(p.attributedAt),
                    observedAt = AttributePluginParsing.parseIsoDate(p.observedAt),
                )
            )
        }

        @Serializable
        private data class Params(
            val provider: String,
            val status: String? = null,
            @SerialName("provider_name") val providerName: String? = null,
            @SerialName("campaign_id") val campaignId: String? = null,
            @SerialName("campaign_name") val campaignName: String? = null,
            @SerialName("ad_group_id") val adGroupId: String? = null,
            @SerialName("ad_group_name") val adGroupName: String? = null,
            @SerialName("ad_id") val adId: String? = null,
            @SerialName("ad_name") val adName: String? = null,
            @SerialName("creative_id") val creativeId: String? = null,
            @SerialName("creative_name") val creativeName: String? = null,
            @SerialName("keyword_id") val keywordId: String? = null,
            val network: String? = null,
            val campaign: String? = null,
            @SerialName("ad_group") val adGroup: String? = null,
            val ad: String? = null,
            val creative: String? = null,
            val keyword: String? = null,
            val source: String? = null,
            val medium: String? = null,
            @SerialName("click_id") val clickId: String? = null,
            val identifiers: Map<String, String> = emptyMap(),
            val metadata: Map<String, JsonElement> = emptyMap(),
            @SerialName("attributed_at") val attributedAt: String? = null,
            @SerialName("observed_at") val observedAt: String? = null,
        )
    }
}

private object AttributePluginParsing {
    fun toAttributeMap(input: Map<String, JsonElement>): Map<String, AppActorAttributeValue?> {
        return input.mapValues { (_, value) -> toAttributeValue(value) }
    }

    fun toNonNullAttributeMap(input: Map<String, JsonElement>): Map<String, AppActorAttributeValue> {
        return input.mapValues { (_, value) ->
            toAttributeValue(value)
                ?: throw IllegalArgumentException("Attribution metadata values must not be null.")
        }
    }

    fun toAttributeValue(element: JsonElement): AppActorAttributeValue? {
        if (element is JsonNull) return null
        if (element is JsonPrimitive) {
            if (element.isString) return AppActorAttributeValue.string(element.content)
            element.booleanOrNull?.let { return AppActorAttributeValue.bool(it) }
            element.doubleOrNull?.let { return AppActorAttributeValue.number(it) }
        }
        if (element is JsonArray) {
            return arrayValue(element)
        }
        throw IllegalArgumentException("Unsupported attribute value shape.")
    }

    fun parseIsoDate(value: String?): Date? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return DATE_FORMATS.firstNotNullOfOrNull { format ->
            runCatching {
                synchronized(format) { format.parse(raw) }
            }.getOrNull()
        }
    }

    private fun arrayValue(array: JsonArray): AppActorAttributeValue {
        if (array.isEmpty()) return AppActorAttributeValue.stringArray(emptyList())
        val primitives = array.jsonArray.map { it.jsonPrimitive }
        if (primitives.all { it.isString }) {
            return AppActorAttributeValue.stringArray(primitives.map { it.content })
        }
        val booleans = primitives.map { it.booleanOrNull }
        if (booleans.all { it != null }) {
            return AppActorAttributeValue.boolArray(booleans.filterNotNull())
        }
        val numbers = primitives.map { it.doubleOrNull }
        if (numbers.all { it != null }) {
            return AppActorAttributeValue.numberArray(numbers.filterNotNull())
        }
        throw IllegalArgumentException("Attribute arrays must contain only strings, numbers, or booleans.")
    }

    private val DATE_FORMATS: List<SimpleDateFormat> = listOf(
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US),
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US),
    ).onEach { it.timeZone = TimeZone.getTimeZone("UTC") }
}
