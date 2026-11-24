plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
// 서명 키는 저장소에 두지 않고 GitHub Secrets에서 빌드할 때만 받아옴
val ksPath: String? = System.getenv("TG_KEYSTORE_PATH")
val ksPass: String? = System.getenv("TG_KEY_PASSWORD")

android {
    namespace = "com.kakario.tggallery"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kakario.tggallery"
        minSdk = 26
        targetSdk = 35
        versionCode = runNumber
        versionName = "0.5.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        if (ksPath != null && ksPass != null) create("rel") {
            storeFile = file(ksPath)
            storePassword = ksPass
            keyAlias = "tg"
            keyPassword = ksPass
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("rel")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
}
