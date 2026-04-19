// Plugin AndroidManifest parser + PluginManifest generator. Uses Android
// Parcelable through PluginManifest, so it's an Android library not a pure
// JVM module. Vendored from Shadow `core/manifest-parser`.
plugins {
    alias(libs.plugins.android.library)
    // AGP 9.x ships built-in Kotlin support; no separate kotlin.android needed.
}

android {
    namespace = "com.tencent.shadow.core.manifest_parser"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation("com.squareup:javapoet:1.13.0")
    compileOnly(project(":shadow-core-runtime"))
}
