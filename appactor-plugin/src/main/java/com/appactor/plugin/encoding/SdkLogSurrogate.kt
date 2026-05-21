package com.appactor.plugin.encoding

import kotlinx.serialization.Serializable

@Serializable
internal data class SdkLogSurrogate(
    val level: String,
    val message: String,
    val category: String,
    val timestamp: String,
)
