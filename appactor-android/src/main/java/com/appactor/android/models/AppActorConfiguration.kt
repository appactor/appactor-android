package com.appactor.android.models

import android.content.Context

internal class AppActorConfiguration(
    context: Context,
    val apiKey: String,
    appUserId: String? = null,
    val baseUrl: String = DEFAULT_BASE_URL,
    val headerMode: HeaderMode = HeaderMode.Bearer,
    val environment: AppActorEnvironment = AppActorEnvironment.Production,
    val options: Options = Options(),
) {
    val appUserId: String? = appUserId?.takeIf { it.trim().isNotEmpty() }

    init {
        validateConfiguration(
            apiKey = apiKey,
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
            appUserId?.let(AppActorValidation::validateAppUserId)
            require(baseUrl.isNotBlank()) {
                "AppActor baseUrl must not be blank."
            }
        }
    }
}
