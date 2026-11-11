plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Habit Brain: the optional tiny brain that answers when the phone has no internet at all.
// It is a separate small app, so Habit AI itself stays small and still installs on every phone
// (the on-phone AI needs a 64-bit phone). It runs Google's Gemma (Apache 2.0) on the phone with LiteRT-LM;
// the model (about 2.6 GB) is downloaded inside it, over Wi-Fi, only when you ask.
android {
    namespace = "com.kishore.bigd.brain"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kishore.bigd.brain"
        minSdk = 26
        targetSdk = 36
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("VERSION_CODE") ?: "0")
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // the same stamp as Habit AI, so the two apps trust each other (and only each other)
    val keystoreFile = System.getenv("KEYSTORE_FILE")
    signingConfigs {
        create("release") {
            if (keystoreFile != null) {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = "bigd"
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystoreFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += "-Xskip-metadata-version-check"   // the AI library may be built with a newer Kotlin
    }
}

dependencies {
    // Google's on-device language model runtime (runs Gemma on the phone's CPU or GPU)
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")
}
