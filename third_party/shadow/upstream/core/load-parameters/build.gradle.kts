// Plugin load parameters — Parcelable data class. Vendored from Shadow
// `core/load-parameters`. Android library because LoadParameters
// implements android.os.Parcelable.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.tencent.shadow.core.load_parameters"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
