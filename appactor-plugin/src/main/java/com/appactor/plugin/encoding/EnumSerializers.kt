package com.appactor.plugin.encoding

import com.appactor.android.models.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Serializes any enum with a `wireValue: String` property to its wire format. */
private inline fun <reified T> wireValueSerializer(
    name: String,
    crossinline toWire: (T) -> String,
): KSerializer<T> = object : KSerializer<T> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(name, PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: T): Unit = encoder.encodeString(toWire(value))
    override fun deserialize(decoder: Decoder): T = error("Decode not needed in plugin")
}

internal val StoreSerializer: KSerializer<AppActorStore> =
    wireValueSerializer("Store") { it.wireValue }

internal val PackageTypeSerializer: KSerializer<AppActorPackageType> =
    wireValueSerializer("PackageType") { it.wireValue }

internal val ProductTypeSerializer: KSerializer<AppActorProductType> =
    wireValueSerializer("ProductType") { it.wireValue }

internal val OwnershipTypeSerializer: KSerializer<AppActorOwnershipType> =
    wireValueSerializer("OwnershipType") { it.wireValue }

internal val PeriodTypeSerializer: KSerializer<AppActorPeriodType> =
    wireValueSerializer("PeriodType") { it.wireValue }

internal val SubscriptionStatusSerializer: KSerializer<AppActorSubscriptionStatus> =
    wireValueSerializer("SubscriptionStatus") { it.wireValue }

internal val CancellationReasonSerializer: KSerializer<AppActorCancellationReason> =
    wireValueSerializer("CancellationReason") { it.wireValue }

internal val StoreCapabilitySerializer: KSerializer<AppActorStoreCapability> =
    wireValueSerializer("StoreCapability") {
        when (it) {
            AppActorStoreCapability.Purchases -> "purchases"
            AppActorStoreCapability.Subscriptions -> "subscriptions"
            AppActorStoreCapability.InAppProducts -> "in_app_products"
            AppActorStoreCapability.PurchaseHistory -> "purchase_history"
            AppActorStoreCapability.Storefront -> "storefront"
        }
    }
