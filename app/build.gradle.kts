plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.micplugin.wevobridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.micplugin.wevobridge"
        minSdk = 26
        targetSdk = 28
        versionCode = 1
        versionName = "1.0.0"
    }

    buildFeatures {
        aidl = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
    }
    }
}
