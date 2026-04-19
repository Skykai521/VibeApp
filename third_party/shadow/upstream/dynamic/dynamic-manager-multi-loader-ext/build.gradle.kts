// dynamic-manager-multi-loader-ext: PluginManager subclass that drives
// multiple loaders. Pairs with :shadow-dynamic-host-multi-loader-ext.
// Vendored from Shadow `dynamic/dynamic-manager-multi-loader-ext`.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.tencent.shadow.dynamic.manager.multi"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":shadow-manager"))
    implementation(project(":shadow-dynamic-manager"))
    implementation(project(":shadow-dynamic-loader"))
    compileOnly(project(":shadow-common"))
    compileOnly(project(":shadow-dynamic-host-multi-loader-ext"))
}
