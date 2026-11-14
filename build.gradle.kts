plugins {
    id("com.android.application") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "1.9.24"
}
      - name: Upload APK
        uses: actions/upload-artifact@v4
        with:
          name: debug-apk
          path: app/build/outputs/apk/debug/app-debug.apk
``` ด้วยวิธีนี้ ไฟล์จะถูก ระบุตำแหน่งที่แน่นอน หมดปัญหาหาไฟล์ไม่เจอครับ ลองปรับตามนี้ดูนะครับ เป็นกำลังใจให้ครับ!
android {
    namespace = "com.example.fm1bridge"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.fm1bridge"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
