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
        public fun dateArray(value: List<Date>): AppActorAttributeValue = DateArrayValue(value)
    }
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
        require(provider.isNotBlank()) { "Attribution provider must not be blank." }
        require(provider.length <= 64) { "Attribution provider must be at most 64 characters." }
        identifiers.keys.forEach(AppActorAttributesValidation::validateIntegrationIdentifierType)
    }
}

internal object AppActorAttributeReservedKeys {
    const val email = "\$email"
    const val displayName = "\$displayName"
    const val phoneNumber = "\$phoneNumber"
    const val fcmToken = "\$fcmToken"
    const val bundleId = "\$bundleId"
    const val locale = "\$locale"
    const val deviceModel = "\$deviceModel"
    const val osVersion = "\$osVersion"
    const val sdkVersion = "\$sdkVersion"
    const val appVersion = "\$appVersion"
    const val storefrontCountry = "\$storefrontCountry"
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
        return normalized
    }

    fun normalizeReservedKey(key: String): String {
        val normalized = key.trim()
        require(normalized.startsWith("$")) { "Reserved attribute keys must start with '$'." }
        require(normalized.length <= maxKeyLength) { "Attribute key must be at most $maxKeyLength characters." }
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
        require(!type.startsWith("$")) { "Integration identifier type must not start with '$'." }
        require(!type.startsWith("appactor.", ignoreCase = true)) {
            "Integration identifier type must not start with 'appactor.'."
        }
    }

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
            is AppActorAttributeValue.DateArrayValue -> validateArraySize(value.value.size)
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
