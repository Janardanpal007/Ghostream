import groovy.json.JsonSlurper
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// The Android app has its own version (fdVersion in gradle.properties), so an app-only release leaves the
// desktop app's version alone; without it, the desktop app's package.json.
val pkgFile = rootDir.resolve("../package.json")
val appVersion = providers.gradleProperty("fdVersion").orNull
    ?: (JsonSlurper().parse(pkgFile) as Map<*, *>)["version"] as String
val (vMajor, vMinor, vPatch) = appVersion.split(".").map { it.toInt() }

// Release signing key, kept outside the repository (see android/README.md).
val keyProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val envKey = System.getenv("FD_KEYSTORE")

android {
    namespace = "com.fastdownloader.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ghostream.app"
        minSdk = 26 // Android 8.0
        targetSdk = 37 // Android 17
        versionCode = vMajor * 10000 + vMinor * 100 + vPatch
        // -PfdVersionOverride=1.0.0 makes the app call itself older (same versionCode, so it installs
        // over the real one and keeps its data), to try the update check.
        versionName = providers.gradleProperty("fdVersionOverride").orNull ?: appVersion
    }

    signingConfigs {
        create("release") {
            val store = envKey ?: keyProps.getProperty("storeFile")
            if (store != null) {
                storeFile = file(store)
                storePassword = System.getenv("FD_KEYSTORE_PASSWORD") ?: keyProps.getProperty("storePassword")
                keyAlias = System.getenv("FD_KEY_ALIAS") ?: keyProps.getProperty("keyAlias")
                keyPassword = System.getenv("FD_KEY_PASSWORD") ?: keyProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // Installs beside the release app (which has another signature), so testing never wipes its downloads.
        debug {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appLabel"] = "Ghostream Debug"
        }
        release {
            manifestPlaceholders["appLabel"] = "Ghostream"
            // Not minified: yt-dlp, FFmpeg and libtorrent are reached through JNI and reflection.
            isMinifyEnabled = false
            // Without a release key the build stops (see the check below) rather than ship a debug-signed app.
            val release = signingConfigs.getByName("release")
            if (release.storeFile != null) signingConfig = release
        }
    }

    // An arm64 APK for modern phones (smaller download) plus one that runs everywhere.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = true
        }
    }

    packaging {
        // yt-dlp's Python and FFmpeg are run from the extracted native-library folder.
        jniLibs.useLegacyPackaging = true
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The ad and tracker list is shared with the browser extension (a copy sits in rules/ for the published source).
    // Without it the app's own blocking list would be silently left out, so the build stops instead.
    val rulesDir = listOf(rootDir.resolve("../extension/rules"), rootDir.resolve("rules"))
        .firstOrNull { it.resolve("trackers.json").isFile }
        ?: throw GradleException("trackers.json not found: put it in android/rules/ (or ../extension/rules/).")
    sourceSets["main"].assets.srcDir(rulesDir)
}

// A release APK or bundle needs the release key (FD_KEYSTORE* or keystore.properties); debug builds don't.
gradle.taskGraph.whenReady {
    val releaseTask = allTasks.any { t ->
        t.project == project && t.name.endsWith("Release") &&
            listOf("assemble", "bundle", "package").any { t.name.startsWith(it) }
    }
    if (releaseTask && android.signingConfigs.getByName("release").storeFile == null) {
        throw GradleException(
            "No release signing key: set FD_KEYSTORE, FD_KEYSTORE_PASSWORD, FD_KEY_ALIAS and FD_KEY_PASSWORD, " +
                "or create android/keystore.properties (see android/README.md).",
        )
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.webkit:webkit:1.18.0")
    implementation("androidx.biometric:biometric:1.1.0")
    // Biometric brings an old Fragment; the activity's result launchers need 1.3 or later.
    implementation("androidx.fragment:fragment-ktx:1.8.5")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")

    // Torrents
    val lt = "2.1.0-39"
    implementation("org.libtorrent4j:libtorrent4j:$lt")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:$lt")
    implementation("org.libtorrent4j:libtorrent4j-android-arm:$lt")
    implementation("org.libtorrent4j:libtorrent4j-android-x86_64:$lt")
    implementation("org.libtorrent4j:libtorrent4j-android-x86:$lt")

    // Video sites (yt-dlp with Python, FFmpeg and QuickJS for Android)
    val ytdl = "0.18.1"
    implementation("io.github.junkfood02.youtubedl-android:library:$ytdl")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$ytdl")

    testImplementation("junit:junit:4.13.2")
}
