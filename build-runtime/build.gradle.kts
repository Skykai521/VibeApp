plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.android.hilt)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.vibe.build.runtime"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += ""
                cFlags += listOf("-Wall", "-Wextra", "-O2", "-std=c11")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        // Instrumented-test APK targetSdk. Matches the main app's
        // targetSdk so tests run under the same untrusted_app_28
        // SELinux domain — without this, exec-from-filesDir tests
        // in Phase 1d hit execute_no_trans denials.
        // See app/build.gradle.kts for context.
        targetSdk = 28
        unitTests {
            isIncludeAndroidResources = false
            isReturnDefaultValues = true
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(libs.hilt)
    ksp(libs.hilt.compiler)

    // tar+gzip bootstrap format: stdlib java.util.zip covers gzip
    implementation(libs.commons.compress)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
