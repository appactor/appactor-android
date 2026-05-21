package com.appactor.plugin.encoding

import com.appactor.android.models.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class RemoteConfigsSurrogate(
    val items: List<RemoteConfigItemSurrogate>,
) {
    constructor(from: AppActorRemoteConfigs) : this(
        items = from.items.map { RemoteConfigItemSurrogate(it) },
    )
}

@Serializable
internal data class RemoteConfigItemSurrogate(
    val key: String,
    @Serializable(with = ConfigValueSerializer::class) val value: AppActorConfigValue,
    @SerialName("value_type") val valueType: String,
) {
    constructor(from: AppActorRemoteConfigItem) : this(
        key = from.key,
        value = from.value,
        valueType = from.valueType.name.lowercase(),
    )
}
