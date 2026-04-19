// dynamic-apk: APK ClassLoader + ChangeApkContextWrapper helpers used by
// dynamic-host. Vendored from Shadow `dynamic/dynamic-apk`.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.tencent.shadow.dynamic.apk"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":shadow-utils"))
    compileOnly(project(":shadow-common"))
}
