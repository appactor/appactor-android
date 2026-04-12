package com.appactor.android.backend.auth

import com.appactor.android.models.AppActorConfiguration
import okhttp3.Request
import java.util.UUID

internal object AppActorAuthHeaderProvider {

    fun apply(
        builder: Request.Builder,
        configuration: AppActorConfiguration,
        path: String,
    ): String? {
        when (configuration.headerMode) {
            AppActorConfiguration.HeaderMode.Bearer -> {
                builder.header("Authorization", "Bearer ${configuration.apiKey}")
            }
            AppActorConfiguration.HeaderMode.ApiKey -> {
                builder.header("X-API-Key", configuration.apiKey)
            }
        }

        configuration.options.platformInfo?.let { platformInfo ->
            builder.header("X-Platform-Flavor", platformInfo.flavor)
            platformInfo.version?.takeIf(String::isNotBlank)?.let { version ->
                builder.header("X-Platform-Flavor-Version", version)
            }
        }

        if (!AppActorEndpointSigningPolicy.forPath(path).needsNonce) {
            return null
        }

        val nonce = UUID.randomUUID().toString()
        builder.header("X-AppActor-Nonce", nonce)
        return nonce
    }
}
