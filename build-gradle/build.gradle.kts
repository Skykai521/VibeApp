plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.android.hilt)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.vibe.build.gradle"
    compileSdk = 36

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

    testOptions {
        // Instrumented-test APK targetSdk must match main app (28) so the
        // test runs under the same untrusted_app_28 SELinux domain —
        // otherwise execute_no_trans denials block exec-from-filesDir.
        // Same rationale + wiring as :build-runtime/build.gradle.kts.
        targetSdk = 28
    }
}

// Copy GradleHost fat JAR into this module's androidTest assets so the
// instrumented test APK can load it via AssetManager. Library
// instrumented-test APKs have isolated asset sets — they don't pick up
// the main app's assets, so we duplicate the same copy pattern here
// targeting src/androidTest/assets/. The copied JAR is gitignored.
val copyGradleHostJarForAndroidTest by tasks.registering(Copy::class) {
    dependsOn(":gradle-host:shadowJar")
    from(project(":gradle-host").layout.buildDirectory.file("libs"))
    into(layout.projectDirectory.dir("src/androidTest/assets"))
    include("vibeapp-gradle-host-*-all.jar")
    rename { "vibeapp-gradle-host.jar" }
}

tasks.matching { it.name == "mergeDebugAndroidTestAssets" }.configureEach {
    dependsOn(copyGradleHostJarForAndroidTest)
}

dependencies {
    implementation(project(":build-runtime"))
    implementation(libs.hilt)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.serialization.json)
    // For ApkInstaller's FileProvider.getUriForFile.
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
