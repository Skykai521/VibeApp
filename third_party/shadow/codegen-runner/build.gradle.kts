// One-shot driver to invoke Tencent Shadow's `ActivityCodeGenerator`
// (vendored verbatim from `projects/sdk/coding/code-generator/`). Outputs
// `Generated*.java` source files that the runtime / activity-container /
// loader consumer modules then check in to their src/main/java/.
//
// Run with:
//   ./gradlew :shadow-codegen-runner:run --args="<output-root>"
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

application {
    mainClass.set("com.vibe.shadow.codegen.MainKt")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("org.javassist:javassist:3.30.2-GA")
    implementation("com.squareup:javapoet:1.13.0")

    // android.jar provides Activity / Application / NativeActivity etc. that
    // the generator's Javassist ClassPool introspects. Use the local Android
    // SDK install — same source Android Studio reads from.
    val sdkRoot = providers.environmentVariable("ANDROID_HOME")
        .orElse(providers.environmentVariable("ANDROID_SDK_ROOT"))
        .orElse(System.getProperty("user.home") + "/Library/Android/sdk")
        .get()
    compileOnly(files("$sdkRoot/platforms/android-36/android.jar"))
    runtimeOnly(files("$sdkRoot/platforms/android-36/android.jar"))
}
