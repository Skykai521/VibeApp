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
version = "1.2"

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
