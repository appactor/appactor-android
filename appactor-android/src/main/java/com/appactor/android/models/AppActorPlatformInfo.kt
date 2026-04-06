package com.appactor.android.models

public data class AppActorPlatformInfo @JvmOverloads constructor(
    public val flavor: String,
    public val version: String? = null,
) {
    init {
        require(flavor.isNotBlank()) {
            "platformInfo flavor must not be blank."
        }
    }
}
