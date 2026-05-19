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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal class SetAttributesRequest private constructor(
    private val attributes: Map<String, AppActorAttributeValue>,
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
            return SetEmailRequest(
                ReservedAttributeHelperParsing.value(
                    json = json,
                    method = method,
                    canonicalKey = "value",
                    legacyKey = "email",
                )
            )
        }
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
            return SetDisplayNameRequest(
                ReservedAttributeHelperParsing.value(
                    json = json,
                    method = method,
                    canonicalKey = "value",
                    legacyKey = "display_name",
                )
            )
        }
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
            return SetPhoneNumberRequest(
                ReservedAttributeHelperParsing.value(
                    json = json,
                    method = method,
                    canonicalKey = "value",
                    legacyKey = "phone_number",
                )
            )
        }
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
            return SetPushTokenRequest(
                ReservedAttributeHelperParsing.value(
                    json = json,
                    method = method,
                    canonicalKey = "value",
                    legacyKey = "push_token",
                )
            )
        }
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
    private val value: String?,
) : PluginRequest {

    override suspend fun execute(): PluginResult {
        AppActor.setIntegrationIdentifier(type, value)
        return PluginResult.successVoid
    }

    companion object : PluginRequestFactory {
        override val method: String = "set_integration_identifier"
        override fun create(json: String): PluginRequest {
            val root = PluginCoder.json.parseToJsonElement(json).jsonObject
            require(root.containsKey("value")) {
                "set_integration_identifier requires value; pass null to clear."
            }
            val p = PluginCoder.json.decodeFromJsonElement(Params.serializer(), root)
            return SetIntegrationIdentifierRequest(p.type, p.value)
        }

        @Serializable
        private data class Params(
            val type: String,
            val value: String?,
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
            val root = PluginCoder.json.parseToJsonElement(json).jsonObject
            val payload = root["attribution"]?.jsonObject ?: root
            val p = PluginCoder.json.decodeFromJsonElement(Params.serializer(), payload)
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

@Serializable
private data class AttributionHelperParams(val value: String)

private object ReservedAttributeHelperParsing {
    fun value(
        json: String,
        method: String,
        canonicalKey: String,
        legacyKey: String,
    ): String? {
        val root = PluginCoder.json.parseToJsonElement(json).jsonObject
        val element = when {
            root.containsKey(canonicalKey) -> root[canonicalKey]
            root.containsKey(legacyKey) -> root[legacyKey]
            else -> throw IllegalArgumentException("$method requires '$canonicalKey' or '$legacyKey'; pass null to clear.")
        }
        if (element == null || element is JsonNull) return null
        val primitive = element as? JsonPrimitive
            ?: throw IllegalArgumentException("$method value must be a string or null.")
        require(primitive.isString) {
            "$method value must be a string or null."
        }
        return primitive.content
    }
}

internal class SetMediaSourceRequest private constructor(
    private val value: String,
) : PluginRequest {

    override suspend fun execute(): PluginResult {
        AppActor.setMediaSource(value)
        return PluginResult.successVoid
    }

    companion object : PluginRequestFactory {
        override val method: String = "set_media_source"
        override fun create(json: String): PluginRequest {
            val p = PluginCoder.json.decodeFromString(AttributionHelperParams.serializer(), json)
            return SetMediaSourceRequest(p.value)
        }
    }
}

internal class SetCampaignRequest private constructor(
    private val value: String,
) : PluginRequest {

    override suspend fun execute(): PluginResult {
        AppActor.setCampaign(value)
        return PluginResult.successVoid
    }

    companion object : PluginRequestFactory {
        override val method: String = "set_campaign"
        override fun create(json: String): PluginRequest {
            val p = PluginCoder.json.decodeFromString(AttributionHelperParams.serializer(), json)
            return SetCampaignRequest(p.value)
        }
    }
}

internal class SetAdGroupRequest private constructor(
    private val value: String,
) : PluginRequest {

    override suspend fun execute(): PluginResult {
        AppActor.setAdGroup(value)
        return PluginResult.successVoid
    }

    companion object : PluginRequestFactory {
        override val method: String = "set_ad_group"
        override fun create(json: String): PluginRequest {
            val p = PluginCoder.json.decodeFromString(AttributionHelperParams.serializer(), json)
            return SetAdGroupRequest(p.value)
        }
    }
}

internal class SetAdRequest private constructor(
    private val value: String,
) : PluginRequest {

    override suspend fun execute(): PluginResult {
        AppActor.setAd(value)
        return PluginResult.successVoid
    }

    companion object : PluginRequestFactory {
        override val method: String = "set_ad"
        override fun create(json: String): PluginRequest {
            val p = PluginCoder.json.decodeFromString(AttributionHelperParams.serializer(), json)
            return SetAdRequest(p.value)
        }
    }
}

internal class SetKeywordRequest private constructor(
    private val value: String,
) : PluginRequest {

    override suspend fun execute(): PluginResult {
        AppActor.setKeyword(value)
        return PluginResult.successVoid
    }

    companion object : PluginRequestFactory {
        override val method: String = "set_keyword"
        override fun create(json: String): PluginRequest {
            val p = PluginCoder.json.decodeFromString(AttributionHelperParams.serializer(), json)
            return SetKeywordRequest(p.value)
        }
    }
}

internal class SetCreativeRequest private constructor(
    private val value: String,
) : PluginRequest {

    override suspend fun execute(): PluginResult {
        AppActor.setCreative(value)
        return PluginResult.successVoid
    }

    companion object : PluginRequestFactory {
        override val method: String = "set_creative"
        override fun create(json: String): PluginRequest {
            val p = PluginCoder.json.decodeFromString(AttributionHelperParams.serializer(), json)
            return SetCreativeRequest(p.value)
        }
    }
}

private object AttributePluginParsing {
    fun toAttributeMap(input: Map<String, JsonElement>): Map<String, AppActorAttributeValue> {
        return input.mapValues { (key, value) ->
            toAttributeValue(value)
                ?: throw IllegalArgumentException("Attribute '$key' value must not be null; use unset_attribute.")
        }
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
        if (element is JsonObject) {
            return objectValue(element)
        }
        throw IllegalArgumentException("Unsupported attribute value shape.")
    }

    fun parseIsoDate(value: String?): Date? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val normalized = normalizeIsoFraction(raw)
        return DATE_FORMATS.firstNotNullOfOrNull { format ->
            runCatching {
                synchronized(format) { format.parse(normalized) }
            }.getOrNull()
        }
    }

    private fun normalizeIsoFraction(value: String): String {
        val match = ISO_UTC_FRACTION_RE.matchEntire(value) ?: return value
        val fraction = match.groupValues[2].takeIf { it.isNotEmpty() } ?: return value
        val millis = fraction.take(3).padEnd(3, '0')
        return "${match.groupValues[1]}.$millis${match.groupValues[3]}"
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

    private fun objectValue(obj: JsonObject): AppActorAttributeValue {
        val rawType = obj["valueType"]?.jsonPrimitive?.contentOrNull
            ?: obj["value_type"]?.jsonPrimitive?.contentOrNull
        if (rawType == "date") {
            val rawValue = obj["value"]?.jsonPrimitive?.contentOrNull
                ?: throw IllegalArgumentException("Date attribute envelope requires a value.")
            val parsed = parseIsoDate(rawValue)
                ?: throw IllegalArgumentException("Date attribute envelope has an invalid ISO-8601 value.")
            return AppActorAttributeValue.date(parsed)
        }
        throw IllegalArgumentException("Unsupported attribute object envelope.")
    }

    private val DATE_FORMATS: List<SimpleDateFormat> = listOf(
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US),
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US),
    ).onEach { it.timeZone = TimeZone.getTimeZone("UTC") }

    private val ISO_UTC_FRACTION_RE = Regex("^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2})(?:\\.(\\d{1,9}))?(Z)$")
}
