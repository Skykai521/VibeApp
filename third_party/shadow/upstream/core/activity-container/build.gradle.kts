// Vendored from Tencent Shadow `projects/sdk/core/activity-container/`.
// Same JAR-vs-AAR rationale as :shadow-common.
//
// `Generated*.java` files in this module's src/main/java are produced by
// :shadow-codegen-runner — DO NOT edit by hand. To refresh:
//   1. ./gradlew :shadow-codegen-runner:run --args="/tmp/sg"
//   2. cp /tmp/sg/activity_container/com/tencent/shadow/core/runtime/container/*.java \
//        third_party/shadow/upstream/core/activity-container/src/main/java/com/tencent/shadow/core/runtime/container/
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.tencent.shadow.core.runtime.container"
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
    // ContentProvider delegate types live in :shadow-common; the Generated*
    // classes here reference them.
    compileOnly(project(":shadow-common"))
}
