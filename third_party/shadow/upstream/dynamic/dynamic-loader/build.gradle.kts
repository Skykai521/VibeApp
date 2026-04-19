// dynamic-loader: AIDL/binder surface that dynamic-loader-impl implements
// inside the loader APK and that dynamic-host calls into. Vendored from
// Shadow `dynamic/dynamic-loader`.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.tencent.shadow.dynamic.loader"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures { aidl = true }
}
