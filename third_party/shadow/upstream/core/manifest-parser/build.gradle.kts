// Plugin AndroidManifest parser + PluginManifest.java generator. Vendored
// from Shadow `core/manifest-parser`. Pure-JVM Kotlin module — the source
// has zero `import android.*` statements and PluginManifest is referenced
// by name through JavaPoet (see PluginManifestGenerator.kt) so the Gradle
// plugin / build-engine integration can depend on it without an AAR.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin { jvmToolchain(17) }

dependencies {
    api("com.squareup:javapoet:1.13.0")
}
