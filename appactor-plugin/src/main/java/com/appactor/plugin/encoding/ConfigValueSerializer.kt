package com.appactor.plugin.encoding

import com.appactor.android.models.AppActorConfigValue
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonEncoder

/**
 * Serializes [AppActorConfigValue] by converting its string representation
 * back to a JsonElement and encoding it inline.
 *
 * Uses `toString()` which delegates to the internal `rawValue.toString()`.
 * This produces valid JSON that can be parsed back.
 */
internal object ConfigValueSerializer : KSerializer<AppActorConfigValue> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ConfigValue", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: AppActorConfigValue) {
        val jsonString = value.toString()
        val element = Json.parseToJsonElement(jsonString)
        (encoder as JsonEncoder).encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): AppActorConfigValue =
        error("ConfigValue deserialization not needed in plugin")
}
