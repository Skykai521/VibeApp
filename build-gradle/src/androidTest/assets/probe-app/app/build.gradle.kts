plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.vibe.probe"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vibe.probe"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
