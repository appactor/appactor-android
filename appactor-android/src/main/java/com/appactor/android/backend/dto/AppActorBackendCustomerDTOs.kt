package com.appactor.android.backend.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonObject

@Serializable(with = AppActorCustomerEnvelopeDTOSerializer::class)
internal data class AppActorCustomerEnvelopeDTO(
    val requestDate: String? = null,
    val requestDateMs: Long? = null,
    override val requestId: String? = null,
    val appUserId: String? = null,
    val customer: AppActorCustomerDTO,
) : AppActorRequestIdCarrier

internal object AppActorCustomerEnvelopeDTOSerializer : KSerializer<AppActorCustomerEnvelopeDTO> {

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("AppActorCustomerEnvelopeDTO")

    override fun deserialize(decoder: Decoder): AppActorCustomerEnvelopeDTO {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("AppActorCustomerEnvelopeDTO only supports JSON decoding.")
        val objectValue = jsonDecoder.decodeJsonElement().jsonObject

        val requestDate = objectValue["requestDate"]?.jsonPrimitive?.contentOrNull
        val requestDateMs = objectValue["requestDateMs"]?.jsonPrimitive?.longOrNull
        val requestId = objectValue["requestId"]?.jsonPrimitive?.contentOrNull
        val appUserId = objectValue["appUserId"]?.jsonPrimitive?.contentOrNull
        val customerElement = objectValue["customer"] ?: objectValue["data"]
            ?: throw SerializationException("Expected either 'customer' or 'data' in customer response.")
        val customer = jsonDecoder.json.decodeFromJsonElement(AppActorCustomerDTO.serializer(), customerElement)

        return AppActorCustomerEnvelopeDTO(
            requestDate = requestDate,
            requestDateMs = requestDateMs,
            requestId = requestId,
            appUserId = appUserId,
            customer = customer,
        )
    }

    override fun serialize(
        encoder: Encoder,
        value: AppActorCustomerEnvelopeDTO,
    ) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("AppActorCustomerEnvelopeDTO only supports JSON encoding.")
        val content = buildMap<String, kotlinx.serialization.json.JsonElement> {
            value.requestDate?.let { put("requestDate", jsonEncoder.json.encodeToJsonElement(it)) }
            value.requestDateMs?.let { put("requestDateMs", jsonEncoder.json.encodeToJsonElement(it)) }
            value.requestId?.let { put("requestId", jsonEncoder.json.encodeToJsonElement(it)) }
            value.appUserId?.let { put("appUserId", jsonEncoder.json.encodeToJsonElement(it)) }
            put("customer", jsonEncoder.json.encodeToJsonElement(value.customer))
        }
        jsonEncoder.encodeJsonElement(JsonObject(content))
    }
}

@Serializable
internal data class AppActorCustomerDTO(
    val managementUrl: String? = null,
    val tokenBalance: AppActorTokenBalanceDTO? = null,
    val firstSeen: String? = null,
    val lastSeen: String? = null,
    val entitlements: Map<String, AppActorEntitlementDTO> = emptyMap(),
    val subscriptions: Map<String, AppActorSubscriptionDTO> = emptyMap(),
    val nonSubscriptions: Map<String, List<AppActorNonSubscriptionDTO>> = emptyMap(),
)

@Serializable
internal data class AppActorEntitlementDTO(
    val isActive: Boolean = false,
    val status: String? = null,
    val expiresAt: String? = null,
    val purchaseDate: String? = null,
    val startsAt: String? = null,
    val productId: String? = null,
    val basePlanId: String? = null,
    val offerId: String? = null,
    val store: String? = null,
    val grantedBy: String? = null,
    val ownershipType: String? = null,
    val periodType: String? = null,
    val isSandbox: Boolean? = null,
    val gracePeriodExpiresAt: String? = null,
    val billingIssueDetectedAt: String? = null,
    val unsubscribeDetectedAt: String? = null,
    val cancellationReason: String? = null,
    val renewedAt: String? = null,
    val activePromotionalOfferType: String? = null,
    val activePromotionalOfferId: String? = null,
)

@Serializable
internal data class AppActorSubscriptionDTO(
    val isActive: Boolean = false,
    val status: String? = null,
    val purchaseDate: String? = null,
    val startsAt: String? = null,
    val expiresAt: String? = null,
    val store: String? = null,
    val productId: String = "",
    val basePlanId: String? = null,
    val offerId: String? = null,
    val isSandbox: Boolean? = null,
    val autoRenew: Boolean? = null,
    val periodType: String? = null,
    val gracePeriodExpiresAt: String? = null,
    val unsubscribeDetectedAt: String? = null,
    val cancellationReason: String? = null,
    val renewedAt: String? = null,
    val originalTransactionId: String? = null,
    val latestTransactionId: String? = null,
    val activePromotionalOfferType: String? = null,
    val activePromotionalOfferId: String? = null,
)

@Serializable
internal data class AppActorNonSubscriptionDTO(
    val purchaseDate: String? = null,
    val store: String? = null,
    val productId: String = "",
    val basePlanId: String? = null,
    val offerId: String? = null,
    val isSandbox: Boolean? = null,
    val isConsumable: Boolean? = null,
    val isRefund: Boolean? = null,
    val storeTransactionIdentifier: String? = null,
)
