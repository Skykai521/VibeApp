// Vendored from Tencent Shadow `projects/sdk/core/runtime/`. Replaces the
// v1 stub :shadow-runtime module that lived at /shadow-runtime/ — same
// Gradle name, new physical location.
//
// `GeneratedPluginActivity.java` in src/main/java is produced by
// :shadow-codegen-runner — see that module's README for refresh steps.
plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

// Coordinate matches what the on-device template asks for:
//   compileOnly("com.tencent.shadow.core:runtime:<shadow-version>")
// Shadow's gradle-plugin generates `PluginManifest.java` against
// `com.tencent.shadow.core.runtime.PluginManifest` — the plugin variant's
// compile classpath needs that interface available, but the host APK
// ships the actual runtime classes via shadow-runtime-apk, so the plugin
// must NOT bundle them. compileOnly is the correct scope on the
// consumer side.
group = "com.tencent.shadow.core"
version = "1.4"

android {
    namespace = "com.tencent.shadow.core.runtime"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    publishing {
        // AGP 9 publishing: opt the release variant into being a Maven
        // component so `from(components["release"])` works below.
        singleVariant("release") {
            // No sources/javadoc — this is a runtime-stub AAR, not a
            // public library; the plugin-repo zip should stay small.
        }
    }
}

dependencies {
    // ShadowActivity references HostActivityDelegator + PluginContainerActivity
    // which live in :shadow-activity-container. compileOnly because at runtime
    // the host's ContainerActivity is what's actually loaded.
    compileOnly(project(":shadow-activity-container"))
    // ShadowApplication uses InstalledApk + Logger from :shadow-common.
    compileOnly(project(":shadow-common"))

    // appcompat is referenced by some optional Shadow runtime helpers.
    compileOnly("androidx.appcompat:appcompat:1.7.0")
}

// The AAR's classes.jar — the same bytecode but exposed as a plain JAR
// so JVM consumers (Shadow's gradle-plugin classpath) can load
// PluginManifest / ShadowInstrumentation etc. via Javassist's ClassPool.
// Extracted from intermediates/runtime_library_classes_jar/release/syncReleaseLibJars/classes.jar.
val runtimeClassesJar by tasks.registering(Copy::class) {
    dependsOn("bundleLibRuntimeToJarRelease")
    from(layout.buildDirectory.file(
        "intermediates/runtime_library_classes_jar/release/bundleLibRuntimeToJarRelease/classes.jar"
    )) {
        rename { "runtime-classes-${project.version}.jar" }
    }
    into(layout.buildDirectory.dir("libs"))
}

publishing {
    publications {
        register<MavenPublication>("release") {
            // `afterEvaluate` because `components["release"]` is created
            // by AGP after evaluation.
            afterEvaluate { from(components["release"]) }
            artifactId = "runtime"
        }
        // JVM-consumable copy of the runtime classes. Used by
        // :shadow-gradle-plugin so Javassist's ClassPool can resolve
        // com.tencent.shadow.core.runtime.* during ShadowTransform.
        register<MavenPublication>("runtimeClasses") {
            artifactId = "runtime-classes"
            artifact(layout.buildDirectory.file(
                "libs/runtime-classes-${project.version}.jar"
            )) {
                builtBy(runtimeClassesJar)
            }
        }
    }
    repositories {
        maven {
            name = "shadowPluginRepo"
            url = uri(rootProject.layout.buildDirectory.dir("shadow-plugin-repo"))
        }
    }
}
