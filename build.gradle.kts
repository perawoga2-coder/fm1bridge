plugins {
    id("com.android.application") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "1.9.24"
}

android {
    namespace = "com.example.fm1bridge"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.fm1bridge"
        minSdk = 23
        targetSdk = 34
        versionCode = 6
        versionName = "0.6"
    }

    // Same signing key for every build, so a new APK can be installed over the old one
    val fixedKey = file("debug.keystore")
    if (fixedKey.exists()) {
        signingConfigs {
            create("fixedDebug") {
                storeFile = fixedKey
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        buildTypes {
            getByName("debug") {
                signingConfig = signingConfigs.getByName("fixedDebug")
            }
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
