package com.appactor.android.models

/**
 * Canonical configuration surface for AppActor on Android.
 *
 * Mirrors the iOS SDK mental model: a short configure call plus a compact
 * options object. Android-only runtime overrides stay internal to the SDK.
 */
public data class AppActorOptions @JvmOverloads constructor(
    public val logLevel: AppActorLogLevel? = null,
    public val platformInfo: AppActorPlatformInfo? = null,
)

internal fun AppActorOptions.toLegacyOptions(): AppActorConfiguration.Options {
    return AppActorConfiguration.Options(
        logLevel = logLevel,
        verifyResponseSignatures = true,
        requireResponseSignatures = true,
        platformInfo = platformInfo,
    )
}
