import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.metalava)
    alias(libs.plugins.mavenPublish)
}

group = providers.gradleProperty("GROUP").getOrElse("com.appactor")
version = providers.gradleProperty("VERSION_NAME").getOrElse("1.0.0")

mavenPublishing {
    coordinates(group.toString(), "appactor-android", version.toString())
    pom {
        name.set("AppActor Android SDK")
        description.set("Server-authoritative Google Play Billing SDK for AppActor.")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }
    }
}

android {
    namespace = "com.appactor.android"
    compileSdk = 36

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "APPACTOR_SDK_VERSION", "\"${project.version}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
    testOptions {
        unitTests.all {
            it.jvmArgs("-Xmx1g", "-XX:+UseParallelGC", "-XX:+TieredCompilation", "-XX:TieredStopAtLevel=1")
            it.testLogging {
                events("passed", "skipped", "failed")
            }
        }
    }
}

metalava {
    filename.set("api.txt")
    arguments.addAll(listOf("--hide", "ReferencesHidden"))
    excludedSourceSets.setFrom(
        listOf(
            "src/test",
            "src/androidTest",
        )
    )
}

tasks.withType<KotlinCompilationTask<*>>().configureEach {
    if (!name.contains("UnitTest") && !name.contains("AndroidTest")) {
        compilerOptions.freeCompilerArgs.add("-Xexplicit-api=strict")
    }
}

tasks.register("apiDump") {
    group = "verification"
    description = "Generates the checked-in public API signature for the release variant."
    dependsOn("metalavaGenerateSignatureRelease")
}

tasks.register("apiCheck") {
    group = "verification"
    description = "Verifies the public API against the checked-in release signature."
    dependsOn("metalavaCheckCompatibilityRelease")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.google.play.billing)
    implementation(libs.google.install.referrer)
    implementation(libs.bouncycastle.bcprov)
    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockk)
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.espresso.core)
}
