plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kafkasl.phonewhisper"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.svifi.vitalyspeak"
        minSdk = 30
        targetSdk = 34
        // CI passes -PversionCode=<run number>; the in-app updater compares against it.
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = "1.0.${(project.findProperty("versionCode") as String?) ?: "1"}"
    }

    // Fixed key so every CI build can install over the previous one.
    // It lives in the repo on purpose (personal sideloaded app); see README.
    signingConfigs {
        create("stable") {
            storeFile = rootProject.file("keystore/vitalyspeak.jks")
            storePassword = "vitalyspeak"
            keyAlias = "vitalyspeak"
            keyPassword = "vitalyspeak"
        }
    }

    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("stable") }
        getByName("release") {
            signingConfig = signingConfigs.getByName("stable")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    @Suppress("DEPRECATION")
    kotlinOptions { jvmTarget = "17" }

    testOptions { unitTests { isIncludeAndroidResources = true } }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
