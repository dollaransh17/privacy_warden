import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// ── Secret loading ─────────────────────────────────────────────────────────
// Reads API keys from local.properties (git-ignored) or environment variables,
// in that order of preference. Never hard-code secrets in this file or any
// source file — they would end up in the APK / git history.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(key: String): String =
    localProps.getProperty(key)
        ?: System.getenv(key)
        ?: ""

android {
    namespace = "com.privacywarden.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.privacywarden.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // Configure these per-build via gradle.properties or env, or hard-code for dev:
        buildConfigField("String", "TANK_WS_URL", "\"ws://10.20.44.65:8443/ws/phone\"")
        buildConfigField(
            "String",
            "TANK_PUBLIC_KEY_B64",
            "\"WvZEJCcWqwF1hp40aP/n7c9G/hVkS3hytDuN5US0uxM=\""
        )
        // Groq chat-completions key for the on-device AI Assistant. Put a line
        // like   GROQ_API_KEY=gsk_xxxxx   into local.properties (git-ignored).
        buildConfigField("String", "GROQ_API_KEY", "\"${secret("GROQ_API_KEY")}\"")
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Kotlinx
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.2")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Crypto: Ed25519 verification (BouncyCastle for portability across Android versions)
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // Local storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    testImplementation("junit:junit:4.13.2")
}
