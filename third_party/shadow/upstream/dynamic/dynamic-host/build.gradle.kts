// dynamic-host: PluginProcessService, DynamicPluginManager, the AIDL
// interfaces consumed by both host and loader. Vendored from Shadow
// `dynamic/dynamic-host`.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.tencent.shadow.dynamic.host"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures { aidl = true }
}

dependencies {
    implementation(project(":shadow-utils"))
    compileOnly(project(":shadow-common"))
    api(project(":shadow-dynamic-apk"))
}
