// dynamic-host-multi-loader-ext: optional extension that lets one host
// run multiple loader APKs concurrently. We don't currently need it but
// vendor it so the upstream graph stays complete. Vendored from Shadow
// `dynamic/dynamic-host-multi-loader-ext`.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.tencent.shadow.dynamic.host.multi"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    compileOnly(project(":shadow-common"))
    api(project(":shadow-dynamic-host"))
}
