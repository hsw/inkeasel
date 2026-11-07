plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing comes from the environment (CI secrets, or a local shell for a hand-made release):
// all four variables set → the release key; none set → the debug key, with a warning on release builds;
// only some set → a release build fails rather than quietly falling back to the debug key. Only the tasks
// that package and sign a release count: release unit tests and lint run without the variables.
val releaseKey: Map<String, String?> = listOf(
    "INKEASEL_KEYSTORE",
    "INKEASEL_KEYSTORE_PASSWORD",
    "INKEASEL_KEY_ALIAS",
    "INKEASEL_KEY_PASSWORD",
).associateWith { providers.environmentVariable(it).orNull?.takeIf(String::isNotEmpty) }
val releaseKeySet = releaseKey.values.all { it != null }
val releasePackaging = setOf("assembleRelease", "bundleRelease", "packageRelease", "packageReleaseBundle", "signReleaseBundle")

gradle.taskGraph.whenReady {
    if (releaseKeySet || allTasks.none { it.project == project && it.name in releasePackaging }) {
        return@whenReady
    }
    val missing = releaseKey.filterValues { it == null }.keys
    if (missing.size < releaseKey.size) {
        throw GradleException("Release signing: set all of ${releaseKey.keys} or none; missing $missing")
    }
    logger.warn("WARNING: release build signed with the debug key (set ${releaseKey.keys} for the release key)")
}

android {
    namespace = "io.github.hsw.inkeasel"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.hsw.inkeasel"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    signingConfigs {
        if (releaseKeySet) {
            create("release") {
                storeFile = file(releaseKey.getValue("INKEASEL_KEYSTORE")!!)
                storePassword = releaseKey["INKEASEL_KEYSTORE_PASSWORD"]
                keyAlias = releaseKey["INKEASEL_KEY_ALIAS"]
                keyPassword = releaseKey["INKEASEL_KEY_PASSWORD"]
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (releaseKeySet) "release" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    // lists the app under Settings → App languages (Android 13+) with the locales of res/ (default: res/resources.properties)
    androidResources {
        generateLocaleConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.tv:tv-material:1.0.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")

    testImplementation("junit:junit:4.13.2")
    // the real org.json: android.jar only has stubs, and catalog errors need a real JSONException
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
