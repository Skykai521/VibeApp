// Runtime APK: tiny com.android.application module that exists only to
// produce a signed APK containing Shadow's core-runtime + activity-
// container class bytes. The host app never launches it as an app; at
// runtime it's extracted from assets and loaded into a DexClassLoader
// so plugins resolve Shadow's rewritten android.app.Activity (etc.)
// against this APK instead of the framework classes. See Phase 5b-3 in
// docs/superpowers/plans/2026-04-19-v2-phase-5b-shadow-full-integration.md.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.tencent.shadow.runtime.apk"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.tencent.shadow.runtime.apk"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    // Everything the host needs on the runtime classpath to resolve
    // bytecode-rewritten references inside plugin classes.
    implementation(project(":shadow-core-runtime"))
    implementation(project(":shadow-activity-container"))
    implementation(project(":shadow-common"))
}
