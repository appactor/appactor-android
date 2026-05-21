package com.appactor.plugin.infrastructure

import kotlinx.serialization.KSerializer

public sealed class PluginResult {
    internal data class Success(val payload: String) : PluginResult()
    internal data class Error(val error: PluginError) : PluginResult()

    public val jsonString: String
        get() = when (this) {
            is Success -> """{"success":$payload}"""
            is Error -> {
                val errorJson = PluginCoder.json.encodeToString(PluginError.serializer(), error)
                """{"error":$errorJson}"""
            }
        }

    public companion object {
        public val successVoid: PluginResult = Success("true")
        internal val nullPayload: PluginResult = Success("null")

        public fun <T> encoding(serializer: KSerializer<T>, value: T): PluginResult {
            return try {
                val payload = PluginCoder.json.encodeToString(serializer, value)
                Success(payload)
            } catch (e: Exception) {
                Error(
                    PluginError(
                        code = PluginError.ENCODING_FAILED,
                        message = "Failed to encode response",
                        detail = e.message ?: "",
                    )
                )
            }
        }
    }
}
