// Loader APK: companion to runtime-apk. Carries core-loader,
// dynamic-loader-impl, and the PluginLoader binder implementation the
// host talks to over Shadow's Service/IPC contract. Like runtime-apk,
// never installed — loaded via DexClassLoader at runtime.
plugins {
    // AGP 9.x has built-in Kotlin support; no separate kotlin.android
    // plugin needed.
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.tencent.shadow.loader.apk"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.tencent.shadow.loader.apk"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(project(":shadow-loader"))
    implementation(project(":shadow-dynamic-loader"))
    implementation(project(":shadow-dynamic-loader-impl"))
    implementation(project(":shadow-core-runtime"))
    implementation(project(":shadow-activity-container"))
    implementation(project(":shadow-common"))
    implementation(project(":shadow-load-parameters"))
    implementation(project(":shadow-dynamic-host"))
}
