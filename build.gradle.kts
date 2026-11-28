plugins {
    id("com.android.application") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "1.9.24"
}

android {
    namespace = "com.makerhip.track16"
    compileSdk = 34

    defaultConfig {
        // 0.12: the Maker Hip release key and a new name (registered with Google's developer verification); it installs
        // beside 16 Track of before (com.makerhip.track16, the old key), whose work comes over by RESTORE
        applicationId = "com.makerhip.track16.studio"   // 16 Track Mixer Recorder, a Maker Hip app
        minSdk = 26
        targetSdk = 34
        versionCode = 12
        versionName = "0.12"
    }

    // 0.12: the Maker Hip release key (the same as Burger Studio's, so the INBOX works between them). CI writes
    // release.jks from the RELEASE_KEYSTORE_B64 secret and passes its password in RELEASE_PASSWORD; the key itself is
    // never in the repository. (Up to 0.11: a fixed debug key, written out in the workflow)
    val relKey = file("release.jks")
    val relPw = System.getenv("RELEASE_PASSWORD")
    if (relKey.exists() && !relPw.isNullOrEmpty()) {
        signingConfigs {
            create("makerhip") {
                storeFile = relKey
                storePassword = relPw
                keyAlias = "makerhip"
                keyPassword = relPw
            }
        }
        buildTypes {
            getByName("debug") {
                signingConfig = signingConfigs.getByName("makerhip")
            }
            getByName("release") {
                signingConfig = signingConfigs.getByName("makerhip")
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
