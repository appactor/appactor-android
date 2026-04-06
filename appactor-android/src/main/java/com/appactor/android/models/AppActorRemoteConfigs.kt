package com.appactor.android.models

public data class AppActorRemoteConfigs(
    val items: List<AppActorRemoteConfigItem>,
) {
    public operator fun get(key: String): AppActorConfigValue? {
        return items.firstOrNull { it.key == key }?.value
    }
}

public data class AppActorRemoteConfigItem(
    val key: String,
    val value: AppActorConfigValue,
    val valueType: AppActorConfigValueType,
)
