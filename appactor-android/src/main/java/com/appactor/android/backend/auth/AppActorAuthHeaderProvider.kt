package com.appactor.android.backend.auth

import com.appactor.android.models.AppActorConfiguration
import okhttp3.Request
import java.util.UUID

internal object AppActorAuthHeaderProvider {
    const val SIGNATURE_BINDING_HEADER: String = "X-AppActor-Signature-Binding"
    const val SIGNATURE_BINDING_REQUEST: String = "request"

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
            builder.header("X-AppActor-Signature-Target", "path-query")
            return null
        }

        val nonce = UUID.randomUUID().toString()
        builder.header("X-AppActor-Nonce", nonce)
        // Asks the backend to sign the method, path + query and body next to the nonce, so a
        // response to a rewritten request (another user's path) can't pass as this one's.
        builder.header(SIGNATURE_BINDING_HEADER, SIGNATURE_BINDING_REQUEST)
        return nonce
    }
}
