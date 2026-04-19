// Vendored from Tencent Shadow `projects/sdk/core/common/`.
// Shadow's original build.gradle used their internal `common-jar-settings`
// plugin (which we deliberately did not vendor) to build this as a pure JAR.
// We rebuild it as an Android library because half of the classes touch
// android.content.ContentProvider / android.os.Parcel etc.; the other half
// (InstalledApk, Logger) are pure-JVM and would happily live in a JAR but
// AAR works for both.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.tencent.shadow.core.common"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
