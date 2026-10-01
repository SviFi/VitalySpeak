plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI passes -PversionCode=<run number>.
val buildNumber = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "com.svifi.vitalyspeak"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.svifi.vitalyspeak"
        minSdk = 30
        targetSdk = 36
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        buildConfigField("String", "PRIVACY_URL", "\"https://svifi.com/vitalyspeak/privacy\"")
        buildConfigField("String", "TERMS_URL", "\"https://svifi.com/vitalyspeak/terms\"")
        // Interface translations shipped (keeps unused library translations out of the app).
        @Suppress("DEPRECATION")
        resourceConfigurations += listOf("en", "es", "pt-rBR", "fr", "de", "it", "ru", "uk", "pl", "nl", "fi", "tr",
            "ar", "hi", "in", "vi", "th", "ja", "ko", "zh-rCN", "zh-rTW")
    }

    buildFeatures { buildConfig = true }


    signingConfigs {
        // Sideload builds: one fixed key so every build installs over the previous one.
        create("direct") {
            storeFile = rootProject.file("keystore/vitalyspeak.jks")
            storePassword = "vitalyspeak"
            keyAlias = "vitalyspeak"
            keyPassword = "vitalyspeak"
        }
        // Google Play upload key, supplied by CI secrets (never committed).
        create("upload") {
            val path = System.getenv("UPLOAD_KEYSTORE")
            if (path != null && file(path).exists()) {
                storeFile = file(path)
                storePassword = System.getenv("UPLOAD_STORE_PASSWORD")
                keyAlias = System.getenv("UPLOAD_KEY_ALIAS") ?: "upload"
                keyPassword = System.getenv("UPLOAD_KEY_PASSWORD") ?: System.getenv("UPLOAD_STORE_PASSWORD")
            } else {
                // Lets the bundle build anywhere; such a bundle can't be uploaded to Play.
                storeFile = rootProject.file("keystore/vitalyspeak.jks")
                storePassword = "vitalyspeak"; keyAlias = "vitalyspeak"; keyPassword = "vitalyspeak"
            }
        }
    }

    flavorDimensions += "channel"
    productFlavors {
        // APK from GitHub releases, can check for and offer its own updates.
        create("direct") {
            dimension = "channel"
            buildConfigField("boolean", "SELF_UPDATE", "true")
            signingConfig = signingConfigs.getByName("direct")
        }
        // Google Play: updates come only from Play (Play policy), no self-update code path.
        create("play") {
            dimension = "channel"
            buildConfigField("boolean", "SELF_UPDATE", "false")
            signingConfig = signingConfigs.getByName("upload")
        }
    }

    buildTypes {
        getByName("release") { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions { unitTests { isIncludeAndroidResources = true } }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
