package com.appactor.plugin.infrastructure

import com.appactor.android.models.AppActorLogLevel
import kotlinx.serialization.json.Json

internal object PluginCoder {
    val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun parseLogLevel(wire: String): AppActorLogLevel? {
        val normalized = wire.replaceFirstChar { it.uppercase() }
        if (normalized == "Verbose") return AppActorLogLevel.Debug
        return runCatching { AppActorLogLevel.valueOf(normalized) }.getOrNull()
    }
}
