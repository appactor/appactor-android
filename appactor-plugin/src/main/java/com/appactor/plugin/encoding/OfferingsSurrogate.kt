package com.appactor.plugin.encoding

import com.appactor.android.models.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class OfferingsSurrogate(
    val current: OfferingSurrogate? = null,
    val all: Map<String, OfferingSurrogate> = emptyMap(),
    @SerialName("product_entitlements") val productEntitlements: Map<String, List<String>> = emptyMap(),
    @SerialName("verification") val verification: String = "notRequested",
) {
    constructor(from: AppActorOfferings) : this(
        current = from.current?.let { OfferingSurrogate(it) },
        all = from.all.mapValues { OfferingSurrogate(it.value) },
        productEntitlements = from.productEntitlements,
        verification = from.verification.wireValue,
    )
}

@Serializable
internal data class OfferingSurrogate(
    val id: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("is_current") val isCurrent: Boolean,
    @SerialName("lookup_key") val lookupKey: String? = null,
    val metadata: Map<String, String>? = null,
    val packages: List<PackageSurrogate> = emptyList(),
) {
    constructor(from: AppActorOffering) : this(
        id = from.id,
        displayName = from.displayName,
        isCurrent = from.isCurrent,
        lookupKey = from.lookupKey,
        metadata = from.metadata.toStringMap(),
        packages = from.packages.map { PackageSurrogate(it) },
    )
}

@Serializable
internal data class PackageSurrogate(
    val id: String,
    @SerialName("package_type") val packageType: String,
    @SerialName("product_id") val productId: String,
    @SerialName("store_product_id") val storeProductId: String? = null,
    @SerialName("product_type") val productType: String,
    val store: String,
    @SerialName("base_plan_id") val basePlanId: String? = null,
    @SerialName("offer_id") val offerId: String? = null,
    @SerialName("localized_price_string") val localizedPriceString: String? = null,
    val price: Double? = null,
    @SerialName("currency_code") val currencyCode: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("product_name") val productName: String? = null,
    @SerialName("product_description") val productDescription: String? = null,
    val metadata: Map<String, String>? = null,
    @SerialName("token_amount") val tokenAmount: Int? = null,
    val position: Int? = null,
    @SerialName("offering_id") val offeringId: String? = null,
) {
    constructor(from: AppActorPackage) : this(
        id = from.id,
        packageType = from.packageType.wireValue,
        productId = from.productId,
        storeProductId = from.storeProductId,
        productType = from.productType.wireValue,
        store = from.store.wireValue,
        basePlanId = from.basePlanId,
        offerId = from.offerId,
        localizedPriceString = from.localizedPriceString,
        price = from.price,
        currencyCode = from.currencyCode,
        displayName = from.displayName,
        productName = from.productName,
        productDescription = from.productDescription,
        metadata = from.metadata.toStringMap(),
        tokenAmount = from.tokenAmount,
        position = from.position,
        offeringId = from.offeringId,
    )
}

private fun AppActorMetadata.toStringMap(): Map<String, String>? =
    takeIf { it.isNotEmpty() }?.mapValues { (_, v) -> v?.toString() ?: "" }
