package com.appactor.android.backend.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

@Serializable
internal data class AppActorIdentifyRequestDTO(
    val appUserId: String? = null,
    val platform: String = "android",
    val appVersion: String? = null,
    val sdkVersion: String? = null,
    val deviceLocale: String? = null,
    val deviceModel: String? = null,
    val osVersion: String? = null,
)

@Serializable
internal data class AppActorLoginRequestDTO(
    val currentAppUserId: String,
    val newAppUserId: String,
)

@Serializable
internal data class AppActorLogoutRequestDTO(
    val appUserId: String,
)

@Serializable(with = AppActorLoginResponseDTOSerializer::class)
internal data class AppActorLoginResponseDTO(
    val requestDate: String? = null,
    val requestDateMs: Long? = null,
    override val requestId: String? = null,
    val appUserId: String,
    val serverUserId: String? = null,
    val customer: AppActorCustomerDTO,
) : AppActorRequestIdCarrier

internal object AppActorLoginResponseDTOSerializer : KSerializer<AppActorLoginResponseDTO> {

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("AppActorLoginResponseDTO")

    override fun deserialize(decoder: Decoder): AppActorLoginResponseDTO {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("AppActorLoginResponseDTO only supports JSON decoding.")
        val objectValue = jsonDecoder.decodeJsonElement().jsonObject

        val requestDate = objectValue["requestDate"]?.jsonPrimitive?.contentOrNull
        val requestDateMs = objectValue["requestDateMs"]?.jsonPrimitive?.longOrNull
        val requestId = objectValue["requestId"]?.jsonPrimitive?.contentOrNull

        val flatAppUserId = objectValue["appUserId"]?.jsonPrimitive?.contentOrNull
        val flatCustomer = objectValue["customer"]
        if (flatAppUserId != null && flatCustomer != null) {
            return AppActorLoginResponseDTO(
                requestDate = requestDate,
                requestDateMs = requestDateMs,
                requestId = requestId,
                appUserId = flatAppUserId,
                serverUserId = objectValue["serverUserId"]?.jsonPrimitive?.contentOrNull,
                customer = jsonDecoder.json.decodeFromJsonElement(AppActorCustomerDTO.serializer(), flatCustomer),
            )
        }

        val dataValue = objectValue["data"]?.jsonObject
            ?: throw SerializationException("Expected either a flat login response or a nested 'data' object.")

        val dataAppUserId = dataValue["appUserId"]?.jsonPrimitive?.contentOrNull
        val dataCustomer = dataValue["customer"]
        if (dataAppUserId != null && dataCustomer != null) {
            return AppActorLoginResponseDTO(
                requestDate = requestDate ?: dataValue["requestDate"]?.jsonPrimitive?.contentOrNull,
                requestDateMs = requestDateMs ?: dataValue["requestDateMs"]?.jsonPrimitive?.longOrNull,
                requestId = requestId ?: dataValue["requestId"]?.jsonPrimitive?.contentOrNull,
                appUserId = dataAppUserId,
                serverUserId = dataValue["serverUserId"]?.jsonPrimitive?.contentOrNull,
                customer = jsonDecoder.json.decodeFromJsonElement(AppActorCustomerDTO.serializer(), dataCustomer),
            )
        }

        val userObject = dataValue["user"]?.jsonObject
            ?: throw SerializationException("Expected either 'customer' or 'user' in login response.")
        val userAppUserId = userObject["appUserId"]?.jsonPrimitive?.contentOrNull
            ?: throw SerializationException("Login response 'user' object is missing appUserId.")
        return AppActorLoginResponseDTO(
            requestDate = requestDate ?: dataValue["requestDate"]?.jsonPrimitive?.contentOrNull,
            requestDateMs = requestDateMs ?: dataValue["requestDateMs"]?.jsonPrimitive?.longOrNull,
            requestId = requestId ?: dataValue["requestId"]?.jsonPrimitive?.contentOrNull,
            appUserId = userAppUserId,
            serverUserId = dataValue["serverUserId"]?.jsonPrimitive?.contentOrNull,
            customer = AppActorCustomerDTO(
                managementUrl = userObject["managementUrl"]?.jsonPrimitive?.contentOrNull,
                tokenBalance = userObject["tokenBalance"]?.let {
                    jsonDecoder.json.decodeFromJsonElement(AppActorTokenBalanceDTO.serializer(), it)
                },
                firstSeen = userObject["firstSeen"]?.jsonPrimitive?.contentOrNull
                    ?: userObject["firstSeenAt"]?.jsonPrimitive?.contentOrNull,
                lastSeen = userObject["lastSeen"]?.jsonPrimitive?.contentOrNull
                    ?: userObject["lastSeenAt"]?.jsonPrimitive?.contentOrNull,
                entitlements = userObject["entitlements"]?.let {
                    jsonDecoder.json.decodeFromJsonElement(
                        MapSerializer(String.serializer(), AppActorEntitlementDTO.serializer()),
                        it,
                    )
                } ?: emptyMap(),
                subscriptions = userObject["subscriptions"]?.let {
                    jsonDecoder.json.decodeFromJsonElement(
                        MapSerializer(String.serializer(), AppActorSubscriptionDTO.serializer()),
                        it,
                    )
                } ?: emptyMap(),
                nonSubscriptions = userObject["nonSubscriptions"]?.let {
                    jsonDecoder.json.decodeFromJsonElement(
                        MapSerializer(
                            String.serializer(),
                            ListSerializer(AppActorNonSubscriptionDTO.serializer()),
                        ),
                        it,
                    )
                } ?: emptyMap(),
            ),
        )
    }

    override fun serialize(
        encoder: Encoder,
        value: AppActorLoginResponseDTO,
    ) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("AppActorLoginResponseDTO only supports JSON encoding.")
        val content = buildMap<String, kotlinx.serialization.json.JsonElement> {
            value.requestDate?.let { put("requestDate", jsonEncoder.json.encodeToJsonElement(it)) }
            value.requestDateMs?.let { put("requestDateMs", jsonEncoder.json.encodeToJsonElement(it)) }
            value.requestId?.let { put("requestId", jsonEncoder.json.encodeToJsonElement(it)) }
            put("appUserId", jsonEncoder.json.encodeToJsonElement(value.appUserId))
            value.serverUserId?.let { put("serverUserId", jsonEncoder.json.encodeToJsonElement(it)) }
            put("customer", jsonEncoder.json.encodeToJsonElement(value.customer))
        }
        jsonEncoder.encodeJsonElement(JsonObject(content))
    }
}

@Serializable(with = AppActorLogoutResponseDTOSerializer::class)
internal data class AppActorLogoutResponseDTO(
    override val requestId: String? = null,
    val success: Boolean = true,
) : AppActorRequestIdCarrier

internal object AppActorLogoutResponseDTOSerializer : KSerializer<AppActorLogoutResponseDTO> {

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("AppActorLogoutResponseDTO")

    override fun deserialize(decoder: Decoder): AppActorLogoutResponseDTO {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("AppActorLogoutResponseDTO only supports JSON decoding.")
        val objectValue = jsonDecoder.decodeJsonElement().jsonObject
        val requestId = objectValue["requestId"]?.jsonPrimitive?.contentOrNull

        val flatSuccess = objectValue["success"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
            ?: objectValue["value"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
        if (flatSuccess != null) {
            return AppActorLogoutResponseDTO(
                requestId = requestId,
                success = flatSuccess,
            )
        }

        val dataValue = objectValue["data"]?.jsonObject
            ?: return AppActorLogoutResponseDTO(requestId = requestId, success = true)
        val nestedSuccess = dataValue["success"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
            ?: dataValue["value"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
            ?: true
        return AppActorLogoutResponseDTO(
            requestId = requestId ?: dataValue["requestId"]?.jsonPrimitive?.contentOrNull,
            success = nestedSuccess,
        )
    }

    override fun serialize(
        encoder: Encoder,
        value: AppActorLogoutResponseDTO,
    ) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("AppActorLogoutResponseDTO only supports JSON encoding.")
        val content = buildMap<String, kotlinx.serialization.json.JsonElement> {
            value.requestId?.let { put("requestId", jsonEncoder.json.encodeToJsonElement(it)) }
            put("success", jsonEncoder.json.encodeToJsonElement(value.success))
        }
        jsonEncoder.encodeJsonElement(JsonObject(content))
    }
}
