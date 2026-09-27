package com.appactor.android.models

import android.content.Context

internal class AppActorConfiguration(
    context: Context,
    apiKey: String,
    appUserId: String? = null,
    val baseUrl: String = DEFAULT_BASE_URL,
    val headerMode: HeaderMode = HeaderMode.Bearer,
    val environment: AppActorEnvironment = AppActorEnvironment.Production,
    val options: Options = Options(),
) {
    // Trimmed as the backend trims the key it authenticates and signs responses with: a padded
    // key would log in but fail every salt-signed response, offerings and remote configs included.
    val apiKey: String = apiKey.backendTrim()
    val appUserId: String? = appUserId?.takeIf { it.trim().isNotEmpty() }

    init {
        validateConfiguration(
            apiKey = this.apiKey,
            appUserId = this.appUserId,
            baseUrl = baseUrl,
        )
    }

    val applicationContext: Context = context.applicationContext

    enum class HeaderMode {
        Bearer,
        ApiKey,
    }

    data class Options(
        val logLevel: AppActorLogLevel? = null,
        val verifyResponseSignatures: Boolean = true,
        val requireResponseSignatures: Boolean = true,
        val platformInfo: AppActorPlatformInfo? = null,
    )

    companion object {
        const val DEFAULT_BASE_URL: String = "https://api.appactor.com"

        private fun validateConfiguration(
            apiKey: String,
            appUserId: String?,
            baseUrl: String,
        ) {
            require(apiKey.isNotBlank()) {
                "AppActor apiKey must not be blank."
            }
            // A placeholder id means nobody is signed in; the identity store resolves it.
            appUserId
                ?.takeUnless(AppActorValidation::isPlaceholderAppUserId)
                ?.let(AppActorValidation::validateAppUserId)
            require(baseUrl.isNotBlank()) {
                "AppActor baseUrl must not be blank."
            }
        }
    }
}
