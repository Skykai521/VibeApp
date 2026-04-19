// Shadow's Gradle plugin (id: com.tencent.shadow.plugin). Vendored from
// Shadow `core/gradle-plugin`. Required so the Phase 5b-5 host/plugin
// templates can apply Shadow's variant API integration. Builds as a
// java-gradle-plugin JVM module.
plugins {
    `java-gradle-plugin`
    alias(libs.plugins.kotlin.jvm)
}

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

dependencies {
    implementation("com.android.tools.build:gradle:9.1.0")
    implementation("com.googlecode.json-simple:json-simple:1.1.1")
    implementation(project(":shadow-transform"))
    implementation(project(":shadow-manifest-parser"))
}
