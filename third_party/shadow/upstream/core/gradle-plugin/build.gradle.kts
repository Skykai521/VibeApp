// Shadow's Gradle plugin (id: com.tencent.shadow.plugin). Vendored from
// Shadow `core/gradle-plugin`. Required so the Phase 5b-5 host/plugin
// templates can apply Shadow's variant API integration. Builds as a
// java-gradle-plugin JVM module.
plugins {
    `java-gradle-plugin`
    `maven-publish`
    alias(libs.plugins.kotlin.jvm)
}

group = "com.vibeapp.shadow"
version = "1.4"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin { jvmToolchain(17) }

gradlePlugin {
    plugins {
        create("shadow") {
            id = "com.tencent.shadow.plugin"
            implementationClass = "com.tencent.shadow.core.gradle.ShadowPlugin"
        }
    }
}

// Note: the `shadowPluginRepoLocal` repo is declared in the root
// settings.gradle.kts dependencyResolutionManagement block — Gradle
// 8+ rejects per-project repos when the root uses
// FAIL_ON_PROJECT_REPOS.

dependencies {
    implementation("com.android.tools.build:gradle:9.1.0")
    implementation("com.googlecode.json-simple:json-simple:1.1.1")
    implementation(project(":shadow-transform"))
    implementation(project(":shadow-manifest-parser"))
    // Runtime classes (PluginManifest, ShadowActivity, ShadowInstrumentation,
    // …) on the gradle-plugin's classpath. ShadowPlugin captures
    // Thread.currentThread().contextClassLoader at apply() time; Javassist's
    // ClassPool is built from that loader, so the transform can only
    // resolve `com.tencent.shadow.core.runtime.*` if those classes are
    // already on the buildscript classpath. Declared as a real Maven
    // coordinate (not `files(...)`) so the published POM lists it — that's
    // what causes on-device Gradle to pull it onto the host project's
    // buildscript classpath when `alias(libs.plugins.shadow)` resolves.
    implementation("com.tencent.shadow.core:runtime-classes:${project.version}")
}

// Make sure :shadow-core-runtime publishes `runtime-classes` to the local
// repo BEFORE this module's compile resolves dependencies, otherwise the
// `implementation("com.tencent.shadow.core:runtime-classes:…")` line above
// fails to resolve on a clean build.
tasks.matching { it.name == "compileKotlin" || it.name == "compileJava" }.configureEach {
    dependsOn(":shadow-core-runtime:publishRuntimeClassesPublicationToShadowPluginRepoRepository")
}

// Publish to a local Maven repo under the root build dir. The :app
// module picks this up (via copyShadowPluginRepo) and bundles it into
// assets/shadow/plugin-repo so on-device Gradle builds can resolve
// `com.tencent.shadow.plugin` from filesDir/shadow/plugin-repo at
// template-project configuration time.
publishing {
    repositories {
        maven {
            name = "shadowPluginRepo"
            url = uri(rootProject.layout.buildDirectory.dir("shadow-plugin-repo"))
        }
    }
}
