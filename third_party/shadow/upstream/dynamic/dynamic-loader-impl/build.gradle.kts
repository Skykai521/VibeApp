// dynamic-loader-impl: ships inside the loader APK; binds the dynamic-loader
// AIDL to a real :shadow-loader instance. Vendored from Shadow
// `dynamic/dynamic-loader-impl`.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.tencent.shadow.dynamic.impl"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":shadow-loader"))
    implementation(project(":shadow-dynamic-loader"))
    compileOnly(project(":shadow-common"))
    compileOnly(project(":shadow-dynamic-host"))
    // DelegateProviderHolder lives in :shadow-activity-container; the
    // upstream `dynamic-loader-impl` build pulled it in transitively
    // through `:shadow-loader` -> `:shadow-core-runtime` but our split
    // doesn't, so name it explicitly.
    compileOnly(project(":shadow-activity-container"))
}
