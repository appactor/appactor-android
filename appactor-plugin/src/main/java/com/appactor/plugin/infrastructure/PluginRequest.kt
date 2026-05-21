package com.appactor.plugin.infrastructure

public interface PluginRequest {
    public suspend fun execute(): PluginResult
}

public interface PluginRequestFactory {
    public val method: String
    public fun create(json: String): PluginRequest
}
