plugins {
    id("com.android.application") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "1.9.24"
}

android {
    namespace = "com.makerhip.track16"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.makerhip.track16"      // 16 Track Mixer Recorder, a Maker Hip app
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "0.7"
    }

    // the Maker Hip key (the same as Burger Studio's): a new APK installs over the old one, and the apps can share later
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

base {
    archivesName.set("16track-v" + android.defaultConfig.versionName!!)
}
