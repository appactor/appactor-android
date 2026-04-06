package com.appactor.plugin.encoding

import com.appactor.android.models.AppActorStorefront
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class StorefrontSurrogate(
    val store: String,
    @SerialName("country_code") val countryCode: String? = null,
) {
    constructor(from: AppActorStorefront) : this(
        store = from.store.wireValue,
        countryCode = from.countryCode,
    )
}
