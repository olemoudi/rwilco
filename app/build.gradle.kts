// Kotlin is compiled by AGP's built-in support (AGP 9+): no kotlin-android plugin, and jvmTarget
// follows compileOptions.targetCompatibility. The compiler plugins below still apply as usual.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "dev.rwilco"
    // AndroidX from mid-2026 on (core 1.19, lifecycle 2.11, Compose 1.12) is compiled against
    // API 37 and refuses anything lower; targetSdk stays where the runtime behaviour was reviewed.
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.rwilco"
        minSdk = 29
        targetSdk = 36
        // release.yml reads these two lines out of this file to build version.json. Keep one of
        // each, and do not write either name followed by an equals sign anywhere else in here —
        // a comment that spells the pattern out is enough to be picked up instead. It was, in
        // v0.1.0 and v0.2.0: the published versionName came out as the comment's placeholder
        // with the real one stuck on the end. Updates kept working (the version CODE never
        // matched the comment), so nothing complained — it just offered a nonsense version.
        versionCode = 13
        versionName = "0.5.0-alpha"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Stable key so in-place auto-updates chain across releases. Committed on purpose
        // (personal app, no secrets). CI can override via SIGNING_* env if a secret is set.
        create("release") {
            storeFile = file(System.getenv("SIGNING_STORE_FILE") ?: "../rwilco-release.jks")
            storePassword = System.getenv("SIGNING_STORE_PASSWORD") ?: "rwilco"
            keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: "rwilco"
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD") ?: "rwilco"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            enableUnitTestCoverage = true
            // The release key, on purpose: a debug build can then be installed over a release
            // install (and vice versa) with `adb install -r`, without uninstalling and losing
            // the reminders. The keystore is committed; see the release signingConfig above.
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }

    // The exported Room schemas, so MigrationTestHelper can open a v1 database and walk it up
    // the real migration chain instead of trusting that the chain exists.
    sourceSets.getByName("androidTest") {
        assets.directories += "$projectDir/schemas"
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(project(":core-model"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.navigation.compose)

    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)

    implementation(libs.room.runtime)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.work.runtime)
    implementation(libs.okhttp)
    implementation(libs.osmdroid.android)
    // Geofencing. The platform's own addProximityAlert is unreliable and battery-hungry; this is
    // the API the phone's location stack actually optimises for. The app degrades to "place
    // reminders do not fire" where Play Services is missing, and says so.
    implementation(libs.play.services.location)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)

    // Instrumented tests: what only a real device can answer — Room migrations against a real
    // SQLite file, the editor flow through real Compose, the installer reading a real APK.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
