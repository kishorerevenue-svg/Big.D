plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.soulfriend.pet"
    compileSdk = 36

    defaultConfig {
        // Change this to your own unique name before the first Play Store upload,
        // for example "com.yourname.soulfriend". It can never change afterwards.
        applicationId = "com.kishore.bigd"
        minSdk = 26
        targetSdk = 36
        // GitHub numbers every build, so each Play upload gets a higher version code.
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("VERSION_CODE") ?: "0")
    }

    buildFeatures {
        buildConfig = true
    }

    // Three editions of the app, built from the same code:
    //  - full: every feature, including "Reply to messages". This one goes to the Play Store first.
    //  - plus: full + Hands-free control (Accessibility: scroll Reels, Back, Home by voice/buttons).
    //          Same app id as full; upload it after Google approves the Accessibility declaration.
    //  - lite: everything except "Reply to messages". Share this APK on WhatsApp or a website.
    // "Reply to messages" needs notification access. In India, Play Protect blocks any APK with
    // notification access when it is installed from WhatsApp, a browser or a file manager.
    // The lite edition has its own app id and name, so it never clashes with the Play Store app.
    flavorDimensions += "edition"
    productFlavors {
        create("full") {
            dimension = "edition"
            buildConfigField("boolean", "MESSAGE_REPLIES", "true")
            buildConfigField("boolean", "HANDS_FREE", "false")
        }
        create("plus") {
            dimension = "edition"
            versionNameSuffix = "-plus"
            buildConfigField("boolean", "MESSAGE_REPLIES", "true")
            buildConfigField("boolean", "HANDS_FREE", "true")
        }
        create("lite") {
            dimension = "edition"
            applicationIdSuffix = ".lite"
            versionNameSuffix = "-lite"
            buildConfigField("boolean", "MESSAGE_REPLIES", "false")
            buildConfigField("boolean", "HANDS_FREE", "false")
        }
    }

    // The Play Store "stamp". The keystore and its password come from GitHub Secrets,
    // never from this public code.
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
    }
}
