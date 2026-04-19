// Shadow's specific Javassist transforms (Activity/Service/Fragment etc.).
// Vendored from Shadow `core/transform`. Pure-JVM Kotlin module that
// builds on top of :shadow-transform-kit.
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
    api(project(":shadow-transform-kit"))
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
