// Vendored from Tencent Shadow `projects/sdk/core/runtime/`. Replaces the
// v1 stub :shadow-runtime module that lived at /shadow-runtime/ — same
// Gradle name, new physical location.
//
// `GeneratedPluginActivity.java` in src/main/java is produced by
// :shadow-codegen-runner — see that module's README for refresh steps.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.tencent.shadow.core.runtime"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // ShadowActivity references HostActivityDelegator + PluginContainerActivity
    // which live in :shadow-activity-container. compileOnly because at runtime
    // the host's ContainerActivity is what's actually loaded.
    compileOnly(project(":shadow-activity-container"))
    // ShadowApplication uses InstalledApk + Logger from :shadow-common.
    compileOnly(project(":shadow-common"))

    // appcompat is referenced by some optional Shadow runtime helpers.
    compileOnly("androidx.appcompat:appcompat:1.7.0")
}
