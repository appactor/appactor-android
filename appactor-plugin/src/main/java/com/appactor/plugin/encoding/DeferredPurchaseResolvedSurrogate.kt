package com.appactor.plugin.encoding

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class DeferredPurchaseResolvedSurrogate(
    @SerialName("product_id") val productId: String,
    @SerialName("customer_info") val customerInfo: CustomerInfoSurrogate,
)
