import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}
// Firebase 설정이 없는 개발 빌드도 실행할 수 있다. 알림 등록 실패는 설정 화면에 표시한다.
if (file("google-services.json").exists()) apply(plugin = "com.google.gms.google-services")
val environment = Properties().apply {
    rootProject.file("environment.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun setting(name: String, default: String = "") =
    providers.gradleProperty(name).orNull ?: environment.getProperty(name, default)
fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// The fixture APK never consumes a production Firebase client or installation.
tasks.configureEach {
    if (name == "processPreviewGoogleServices") enabled = false
}

android {
    namespace = "com.copenhagen.wallet"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.copenhagen.wallet"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"
        buildConfigField("boolean", "ALLOW_SCREEN_CAPTURE", "false")
        for (name in listOf("CORE_BASE_URL", "PAY_BASE_URL", "PG_MOCK_BASE_URL")) {
            buildConfigField("String", name, quoted(setting(name)))
        }
        buildConfigField("String", "APP_LINK_HOST", quoted(setting("APP_LINK_HOST", "unconfigured.invalid")))
        manifestPlaceholders["appLinkHost"] = setting("APP_LINK_HOST", "unconfigured.invalid")
    }
    buildTypes {
        debug { manifestPlaceholders["cleartextAllowed"] = "true" }
        // Keep the registered package/Firebase client so demonstrations retain login, links and push.
        // Only this explicitly named build permits capture in the real app activities.
        create("demo") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
            buildConfigField("boolean", "ALLOW_SCREEN_CAPTURE", "true")
        }
        // Isolated, visibly labelled fixture harness. Never shares installed app data or hosts.
        create("preview") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-fixture"
            matchingFallbacks += listOf("debug")
            for (name in listOf("CORE_BASE_URL", "PAY_BASE_URL", "PG_MOCK_BASE_URL")) {
                buildConfigField("String", name, quoted("http://127.0.0.1:8787"))
            }
            buildConfigField("String", "APP_LINK_HOST", quoted("fixture.invalid"))
            manifestPlaceholders["appLinkHost"] = "fixture.invalid"
        }
        release {
            manifestPlaceholders["cleartextAllowed"] = "false"
            isMinifyEnabled = false
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.fragment:fragment-ktx:1.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.0")
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    implementation(platform("com.google.firebase:firebase-bom:34.19.0"))
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.google.firebase:firebase-installations")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
