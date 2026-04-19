plugins {
    alias(libs.plugins.android.application)
    // AGP 9.x provides built-in Kotlin support; no separate kotlin.android
    // plugin needed (and registering one fails with "Cannot add extension
    // with name 'kotlin'").
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.vibe.probe"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vibe.probe"
        minSdk = 24
        targetSdk = 34
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

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
}
