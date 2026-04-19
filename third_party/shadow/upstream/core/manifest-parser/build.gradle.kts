// Plugin AndroidManifest parser + PluginManifest.java generator. Vendored
// from Shadow `core/manifest-parser`. Pure-JVM Kotlin module — the source
// has zero `import android.*` statements and PluginManifest is referenced
// by name through JavaPoet (see PluginManifestGenerator.kt) so the Gradle
// plugin / build-engine integration can depend on it without an AAR.
plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

group = "com.vibeapp.shadow"
version = "1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin { jvmToolchain(17) }

dependencies {
    api("com.squareup:javapoet:1.13.0")
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
