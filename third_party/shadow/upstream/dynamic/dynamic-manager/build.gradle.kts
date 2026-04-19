// dynamic-manager: PluginManagerThatUseDynamicLoader — the entry point
// the host code calls to load + start a plugin. Vendored from Shadow
// `dynamic/dynamic-manager`.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.tencent.shadow.dynamic.manager"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    compileOnly(project(":shadow-common"))
    implementation(project(":shadow-manager"))
    implementation(project(":shadow-dynamic-loader"))
    compileOnly(project(":shadow-dynamic-host"))
}
