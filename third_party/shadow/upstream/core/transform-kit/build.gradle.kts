// Pure-JVM bytecode transform plumbing (Javassist + AGP utility helpers).
// Vendored from Shadow `core/transform-kit`. Used by :shadow-transform and,
// eventually, by build-engine when we wire host/plugin APK rewriting into
// the on-device build pipeline. Keep as a pure JVM module.
plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

group = "com.vibeapp.shadow"
version = "1.1"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin { jvmToolchain(17) }

dependencies {
    api("org.javassist:javassist:3.30.2-GA")
    // AGP gradle API — only a couple of utility classes are actually used
    // (com.android.SdkConstants, com.android.utils.FileUtils, and a tiny
    // toImmutableMap extension). We need it on the compile classpath.
    api("com.android.tools.build:gradle:9.1.0")
    api("com.android.tools:common:32.1.0")
    api(gradleApi())
}

publishing {
    publications {
        create<MavenPublication>("maven") { from(components["java"]) }
    }
    repositories {
        maven {
            name = "shadowPluginRepo"
            url = uri(rootProject.layout.buildDirectory.dir("shadow-plugin-repo"))
        }
    }
}
