import org.gradle.api.tasks.Exec

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.metalava) apply false
}

tasks.register<Exec>("verifyConnectedDeviceSmoke") {
    group = "verification"
    description = "Runs the deterministic connected-device smoke suite for the Android SDK."
    workingDir = rootDir
    commandLine("bash", "${rootDir.absolutePath}/scripts/verify-connected-device-smoke.sh")
}

tasks.register<Exec>("verifyConnectedInteractiveSmoke") {
    group = "verification"
    description = "Runs the interactive connected-device purchase smoke suite for the Android SDK."
    workingDir = rootDir
    commandLine("bash", "${rootDir.absolutePath}/scripts/verify-connected-interactive-smoke.sh")
}

tasks.register("verifyAndroidReleaseCandidate") {
    group = "verification"
    description = "Runs the Android release-candidate verification flow with deterministic connected-device smoke."
    dependsOn(
        ":appactor-android:apiCheck",
        ":appactor-android:testDebugUnitTest",
        ":appactor-android:assemble",
        ":app:assembleDebug",
        ":appactor-android:assembleDebugAndroidTest",
        ":appactor-android:publishReleasePublicationToMavenLocal",
        ":appactor-android:publishReleasePublicationToAppactorLocalRepository",
        "verifyConnectedDeviceSmoke",
    )
}
