package com.appactor.android.models

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

public class AppActorConfigValue internal constructor(
    internal val rawValue: JsonElement,
) {

    public val boolValue: Boolean?
        get() = (rawValue as? JsonPrimitive)?.booleanOrNull

    public val stringValue: String?
        get() = (rawValue as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content

    public val doubleValue: Double?
        get() = (rawValue as? JsonPrimitive)?.doubleOrNull
            ?: (rawValue as? JsonPrimitive)?.longOrNull?.toDouble()

    public val intValue: Int?
        get() = (rawValue as? JsonPrimitive)?.intOrNull
            ?: (rawValue as? JsonPrimitive)?.doubleOrNull
                ?.takeIf { it == it.toInt().toDouble() }
                ?.toInt()

    public val listValue: List<AppActorConfigValue>?
        get() = (rawValue as? JsonArray)?.map(::AppActorConfigValue)

    public val mapValue: Map<String, AppActorConfigValue>?
        get() = (rawValue as? JsonObject)?.mapValues { (_, value) -> AppActorConfigValue(value) }

    public val isNull: Boolean
        get() = rawValue is JsonNull

    public operator fun get(key: String): AppActorConfigValue? {
        return (rawValue as? JsonObject)?.get(key)?.let(::AppActorConfigValue)
    }

    override fun equals(other: Any?): Boolean {
        return other is AppActorConfigValue && rawValue == other.rawValue
    }

    override fun hashCode(): Int {
        return rawValue.hashCode()
    }

    override fun toString(): String {
        return rawValue.toString()
    }
}

public enum class AppActorConfigValueType {
    Boolean,
    Number,
    String,
    Json,
    Unknown;

    internal companion object {
        fun fromWireValue(value: String?): AppActorConfigValueType {
            return when (value?.lowercase()) {
                "boolean" -> Boolean
                "number" -> Number
                "string" -> String
                "json" -> Json
                else -> Unknown
            }
        }
    }
}
