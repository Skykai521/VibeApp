// Shadow's plugin loader — turns an InstalledApk + LoadParameters into a
// running ShadowActivityDelegate that can host plugin Activities. Vendored
// from Shadow `core/loader`. Android library because it depends on
// PluginManifest etc. from :shadow-core-runtime.
//
// `GeneratedShadowActivityDelegate.java` in src/main/java is produced by
// :shadow-codegen-runner — see codegen-runner's Main.kt for refresh.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.tencent.shadow.core.loader"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":shadow-core-runtime"))
    api(project(":shadow-load-parameters"))
    compileOnly(project(":shadow-common"))
    compileOnly(project(":shadow-activity-container"))
    // ComponentManager.kt + ShadowActivityDelegate.kt reference
    // com.tencent.shadow.coding.java_build_config.BuildConfig. Pulled
    // from the shared stub module; see :shadow-java-build-config.
    implementation(project(":shadow-java-build-config"))
}
