plugins {
    alias(libs.plugins.android.library)
}

val copyShadowRuntime by tasks.registering(Copy::class) {
    dependsOn(":shadow-runtime:bundleLibCompileToJarRelease")
    from(project(":shadow-runtime").layout.buildDirectory.file("intermediates/compile_library_classes_jar/release/bundleLibCompileToJarRelease/classes.jar"))
    into(layout.projectDirectory.dir("src/main/assets"))
    rename { "shadow-runtime.jar" }
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(copyShadowRuntime)
}

android {
    namespace = "com.vibe.build.engine"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        minSdk = 29

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation(project(":build-tools:common"))
    implementation(project(":build-tools:build-logic"))
    implementation(project(":build-tools:javac"))
    implementation(project(":build-tools:logging"))
    implementation("com.android.tools.build:apksig:9.1.0")
    implementation(libs.asm)
    implementation(libs.asm.commons)
}
