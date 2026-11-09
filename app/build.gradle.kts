plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("androidx.room")
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
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.hsw.inkeasel"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
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
            // R8 shrinks code and resources but keeps names: see docs/decisions.md
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        buildConfig = true // BuildConfig.DEBUG: what plays reaches the log only in debug builds (MediaLog)
    }
    // lists the app under Settings → App languages (Android 13+) with the locales of res/ (default: res/resources.properties)
    androidResources {
        generateLocaleConfig = true
    }
}

// the catalog database's schema history, committed: each version's JSON shows what changed
room {
    schemaDirectory("$projectDir/schemas")
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
    // Room 2.6.1, not 2.7.x: 2.7 pulls kotlin-stdlib 2.1 over the Kotlin 2.0.21 compiler. Paging 3.3.6, not
    // 3.4+: paging-compose 3.4 pulls Compose foundation over the BOM.
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    implementation("androidx.room:room-paging:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.paging:paging-runtime:3.3.6")
    implementation("androidx.paging:paging-compose:3.3.6")

    testImplementation("junit:junit:4.13.2")
    // the real org.json: android.jar only has stubs, and catalog errors need a real JSONException
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // Room DAOs, the import and the paging source run on an in-memory database under Robolectric
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.paging:paging-testing:3.3.6")
    // the same OkHttp line as the app (5.x would replace OkHttp 4 on the test classpath)
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
