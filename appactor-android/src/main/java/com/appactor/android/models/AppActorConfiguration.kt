package com.appactor.android.models

import android.content.Context
import com.appactor.android.internal.logging.AppActorLogger

internal class AppActorConfiguration(
    context: Context,
    val apiKey: String,
    appUserId: String? = null,
    val baseUrl: String = DEFAULT_BASE_URL,
    val headerMode: HeaderMode = HeaderMode.Bearer,
    val environment: AppActorEnvironment = AppActorEnvironment.Production,
    val options: Options = Options(),
) {
    // A placeholder such as "null" (e.g. `user?.id.toString()` while signed out) means no user,
    // like a blank id, so configure() must not fail for it.
    val appUserId: String? = appUserId?.takeUnless(AppActorValidation::meansNoUser)

    init {
        if (appUserId != null && AppActorValidation.isPlaceholderAppUserId(appUserId)) {
            AppActorLogger.warn("[Identity] appUserId '$appUserId' is a placeholder the backend rejects; treating it as no user.")
        }
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
