package com.appactor.android.models

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

public sealed class AppActorAttributeValue {
    internal abstract fun toJsonElement(): JsonElement

    public data class StringValue(public val value: String) : AppActorAttributeValue() {
        override fun toJsonElement(): JsonElement = JsonPrimitive(value)
    }

    public data class NumberValue(public val value: Double) : AppActorAttributeValue() {
        init {
            require(value.isFinite()) { "Attribute number value must be finite." }
        }

        override fun toJsonElement(): JsonElement = JsonPrimitive(value)
    }

    public data class BooleanValue(public val value: Boolean) : AppActorAttributeValue() {
        override fun toJsonElement(): JsonElement = JsonPrimitive(value)
    }

    public data class DateValue(public val value: Date) : AppActorAttributeValue() {
        override fun toJsonElement(): JsonElement = JsonObject(
            mapOf(
                "value" to JsonPrimitive(AppActorIso8601.format(value)),
                "valueType" to JsonPrimitive("date"),
            ),
        )
    }

    public data class StringArrayValue(public val value: List<String>) : AppActorAttributeValue() {
        override fun toJsonElement(): JsonElement = JsonArray(value.map(::JsonPrimitive))
    }

    public data class NumberArrayValue(public val value: List<Double>) : AppActorAttributeValue() {
        init {
            require(value.all(Double::isFinite)) { "Attribute number array values must be finite." }
        }

        override fun toJsonElement(): JsonElement = JsonArray(value.map(::JsonPrimitive))
    }

    public data class BooleanArrayValue(public val value: List<Boolean>) : AppActorAttributeValue() {
        override fun toJsonElement(): JsonElement = JsonArray(value.map(::JsonPrimitive))
    }

    @Deprecated("Date arrays are not supported by the AppActor backend. Send individual date attributes instead.")
    public data class DateArrayValue(public val value: List<Date>) : AppActorAttributeValue() {
        override fun toJsonElement(): JsonElement =
            JsonArray(value.map { JsonPrimitive(AppActorIso8601.format(it)) })
    }

    public companion object {
        @JvmStatic
        public fun string(value: String): AppActorAttributeValue = StringValue(value)

        @JvmStatic
        public fun number(value: Double): AppActorAttributeValue = NumberValue(value)

        @JvmStatic
        public fun number(value: Int): AppActorAttributeValue = NumberValue(value.toDouble())

        @JvmStatic
        public fun bool(value: Boolean): AppActorAttributeValue = BooleanValue(value)

        @JvmStatic
        public fun date(value: Date): AppActorAttributeValue = DateValue(value)

        @JvmStatic
        public fun stringArray(value: List<String>): AppActorAttributeValue = StringArrayValue(value)

        @JvmStatic
        public fun numberArray(value: List<Double>): AppActorAttributeValue = NumberArrayValue(value)

        @JvmStatic
        public fun boolArray(value: List<Boolean>): AppActorAttributeValue = BooleanArrayValue(value)

        @JvmStatic
        @Suppress("DEPRECATION")
        public fun dateArray(value: List<Date>): AppActorAttributeValue = DateArrayValue(value)
    }
}

public enum class AppActorIntegrationIdentifier(public val wireValue: String) {
    AppsFlyerId("appsflyer_id"),
    AdjustId("adjust_adid"),
    BranchId("branch_id"),
    FirebaseAppInstanceId("firebase_app_instance_id"),
    AmplitudeUserId("amplitude_user_id"),
    AmplitudeDeviceId("amplitude_device_id"),
    MixpanelDistinctId("mixpanel_distinct_id"),
    FacebookAnonymousId("fb_anon_id"),
    OneSignalId("onesignal_id"),
}

public data class AppActorAttribution(
    public val provider: String,
    public val network: String? = null,
    public val campaign: String? = null,
    public val adGroup: String? = null,
    public val ad: String? = null,
    public val creative: String? = null,
    public val keyword: String? = null,
    public val source: String? = null,
    public val medium: String? = null,
    public val clickId: String? = null,
    public val identifiers: Map<String, String> = emptyMap(),
    public val metadata: Map<String, AppActorAttributeValue> = emptyMap(),
    public val observedAt: Date? = null,
    public val status: String? = null,
    public val providerName: String? = null,
    public val campaignId: String? = null,
    public val campaignName: String? = null,
    public val adGroupId: String? = null,
    public val adGroupName: String? = null,
    public val adId: String? = null,
    public val adName: String? = null,
    public val creativeId: String? = null,
    public val creativeName: String? = null,
    public val keywordId: String? = null,
    public val attributedAt: Date? = null,
) {
    init {
        AppActorAttributesValidation.validateAttributionProvider(provider)
        listOf(
            "network" to network,
            "campaign" to campaign,
            "ad_group" to adGroup,
            "ad" to ad,
            "creative" to creative,
            "keyword" to keyword,
            "source" to source,
            "medium" to medium,
            "click_id" to clickId,
            "status" to status,
            "provider_name" to providerName,
            "campaign_id" to campaignId,
            "campaign_name" to campaignName,
            "ad_group_id" to adGroupId,
            "ad_group_name" to adGroupName,
            "ad_id" to adId,
            "ad_name" to adName,
            "creative_id" to creativeId,
            "creative_name" to creativeName,
            "keyword_id" to keywordId,
        ).forEach { (field, value) ->
            AppActorAttributesValidation.validateAttributionString(field = field, value = value)
        }
        identifiers.forEach { (key, value) ->
            AppActorAttributesValidation.validateIntegrationIdentifierType(key)
            AppActorAttributesValidation.validateIntegrationIdentifierValue(value)
        }
        metadata.forEach { (key, value) ->
            AppActorAttributesValidation.normalizeCustomKey(key)
            AppActorAttributesValidation.validateValue(value)
        }
    }
}

internal object AppActorAttributeReservedKeys {
    const val email = "\$email"
    const val displayName = "\$displayName"
    const val phoneNumber = "\$phoneNumber"
    const val apnsToken = "\$apnsToken"
    const val fcmToken = "\$fcmToken"
    const val idfv = "\$idfv"
    const val idfa = "\$idfa"
    const val bundleId = "\$bundleId"
    const val locale = "\$locale"
    const val timezone = "\$timezone"
    const val platform = "\$platform"
    const val platformFlavor = "\$platformFlavor"
    const val platformVersion = "\$platformVersion"
    const val deviceModel = "\$deviceModel"
    const val osVersion = "\$osVersion"
    const val sdkVersion = "\$sdkVersion"
    const val appVersion = "\$appVersion"
    const val appBuild = "\$appBuild"
    const val storefrontCountry = "\$storefrontCountry"
    const val ipCountry = "\$ipCountry"
    const val localeCountry = "\$localeCountry"
    const val attConsentStatus = "\$attConsentStatus"
    const val appsflyerId = "\$appsflyerId"
    const val adjustId = "\$adjustId"
    const val branchId = "\$branchId"
    const val firebaseAppInstanceId = "\$firebaseAppInstanceId"
    const val onesignalId = "\$onesignalId"
    const val airshipChannelId = "\$airshipChannelId"
    const val amplitudeId = "\$amplitudeId"
    const val mixpanelDistinctId = "\$mixpanelDistinctId"
    const val posthogDistinctId = "\$posthogDistinctId"
    const val customerioId = "\$customerioId"

    val all: Set<String> = setOf(
        email,
        displayName,
        phoneNumber,
        apnsToken,
        fcmToken,
        idfv,
        idfa,
        bundleId,
        locale,
        timezone,
        platform,
        platformFlavor,
        platformVersion,
        deviceModel,
        osVersion,
        sdkVersion,
        appVersion,
        appBuild,
        storefrontCountry,
        ipCountry,
        localeCountry,
        attConsentStatus,
        appsflyerId,
        adjustId,
        branchId,
        firebaseAppInstanceId,
        onesignalId,
        airshipChannelId,
        amplitudeId,
        mixpanelDistinctId,
        posthogDistinctId,
        customerioId,
    )
}

internal object AppActorAttributesValidation {
    private const val maxKeyLength = 64
    private const val maxStringLength = 1_024
    private const val maxArrayLength = 20
    private val keyRegex = Regex("^[A-Za-z0-9_.:-]+$")

    fun normalizeCustomKey(key: String): String {
        val normalized = key.trim()
        require(normalized.isNotEmpty()) { "Attribute key must not be blank." }
        require(normalized.length <= maxKeyLength) { "Attribute key must be at most $maxKeyLength characters." }
        require(keyRegex.matches(normalized)) {
            "Attribute key may only contain letters, numbers, underscore, dot, colon, or dash."
        }
        require(!normalized.startsWith("$")) {
            "Custom attribute keys must not start with '$'. Use the reserved helper APIs instead."
        }
        require(!normalized.startsWith("appactor.", ignoreCase = true)) {
            "Custom attribute keys must not start with 'appactor.'."
        }
        require(!normalized.startsWith("integration.", ignoreCase = true)) {
            "Custom attribute keys must not start with 'integration.'. Use setIntegrationIdentifier() instead."
        }
        return normalized
    }

    fun normalizeReservedKey(key: String): String {
        val normalized = key.trim()
        require(normalized.startsWith("$")) { "Reserved attribute keys must start with '$'." }
        require(normalized.length <= maxKeyLength) { "Attribute key must be at most $maxKeyLength characters." }
        require(AppActorAttributeReservedKeys.all.contains(normalized)) {
            "Unknown reserved attribute key '$normalized'."
        }
        return normalized
    }

    fun normalizeIntegrationIdentifierType(type: String): String {
        val normalized = type.trim()
        validateIntegrationIdentifierType(normalized)
        return normalized
    }

    fun validateIntegrationIdentifierType(type: String) {
        require(type.isNotBlank()) { "Integration identifier type must not be blank." }
        require(type.length <= maxKeyLength) { "Integration identifier type must be at most $maxKeyLength characters." }
        require(keyRegex.matches(type)) {
            "Integration identifier type may only contain letters, numbers, underscore, dot, colon, or dash."
        }
        require(!type.startsWith("$")) { "Integration identifier type must not start with '$'." }
        require(!type.startsWith("appactor.", ignoreCase = true)) {
            "Integration identifier type must not start with 'appactor.'."
        }
    }

    fun validateIntegrationIdentifierValue(value: String) {
        require(value.trim() == value && value.isNotEmpty()) {
            "Integration identifier value must not be empty or padded with whitespace."
        }
        require(value.toByteArray(Charsets.UTF_8).size <= maxStringLength) {
            "Integration identifier value must be at most $maxStringLength bytes."
        }
    }

    fun validateAttributionProvider(value: String) {
        validateAttributionString(field = "provider", value = value, maxBytes = maxKeyLength)
    }

    fun validateAttributionString(
        field: String,
        value: String?,
        maxBytes: Int = maxStringLength,
    ) {
        if (value == null) return
        require(value.trim() == value && value.isNotEmpty()) {
            "Attribution field '$field' must not be empty or padded with whitespace."
        }
        require(value.toByteArray(Charsets.UTF_8).size <= maxBytes) {
            "Attribution field '$field' must be at most $maxBytes bytes."
        }
    }

    @Suppress("DEPRECATION")
    fun validateValue(value: AppActorAttributeValue) {
        when (value) {
            is AppActorAttributeValue.StringValue -> validateString(value.value)
            is AppActorAttributeValue.NumberValue -> Unit
            is AppActorAttributeValue.BooleanValue -> Unit
            is AppActorAttributeValue.DateValue -> Unit
            is AppActorAttributeValue.StringArrayValue -> {
                validateArraySize(value.value.size)
                value.value.forEach(::validateString)
            }
            is AppActorAttributeValue.NumberArrayValue -> validateArraySize(value.value.size)
            is AppActorAttributeValue.BooleanArrayValue -> validateArraySize(value.value.size)
            is AppActorAttributeValue.DateArrayValue -> require(false) {
                "Date arrays are not supported. Send individual date attributes instead."
            }
        }
    }

    fun validateEmail(value: String) {
        val normalized = value.trim()
        require(normalized == value && value.isNotEmpty()) {
            "Email must not be empty or padded with whitespace."
        }
        require(value.toByteArray(Charsets.UTF_8).size <= 320) {
            "Email must be at most 320 bytes."
        }
        require(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(value)) {
            "Email must be a valid email address."
        }
    }

    fun validatePhoneNumber(value: String) {
        val normalized = value.trim()
        require(normalized == value && value.isNotEmpty()) {
            "Phone number must not be empty or padded with whitespace."
        }
        require(value.toByteArray(Charsets.UTF_8).size <= 64) {
            "Phone number must be at most 64 bytes."
        }
        require(value.count(Char::isDigit) >= 3) {
            "Phone number must contain at least 3 digits."
        }
        require(Regex("^[+0-9().\\-\\s]+$").matches(value)) {
            "Phone number contains unsupported characters."
        }
    }

    private fun validateString(value: String) {
        require(value.toByteArray(Charsets.UTF_8).size <= maxStringLength) {
            "Attribute string values must be at most $maxStringLength bytes."
        }
    }

    private fun validateArraySize(size: Int) {
        require(size <= maxArrayLength) { "Attribute arrays must contain at most $maxArrayLength values." }
    }
}

internal object AppActorIso8601 {
    private val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun format(date: Date): String = synchronized(formatter) {
        formatter.format(date)
    }
}
