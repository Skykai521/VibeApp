import org.gradle.kotlin.dsl.aboutLibraries

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.android.hilt)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.auto.license)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.vibe.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vibe.app"
        minSdk = 29
        // targetSdk = 28 is load-bearing. It places VibeApp in the
        // untrusted_app_28 SELinux domain, which permits
        // execute_no_trans on app_data_file — the kernel precondition
        // for exec'ing downloaded binaries (java, gradle, aapt2) out
        // of filesDir/usr/opt/. Higher targetSdk values land in
        // untrusted_app_29+ where that is denied, breaking the whole
        // on-device build pipeline. See design doc §2.3 and
        // docs/superpowers/plans/2026-04-18-v2-phase-1d-termux-exec.md
        // (Phase 1d post-mortem) for the full context.
        targetSdk = 28
        versionCode = 15
        versionName = "1.9.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi")
        }
    }

    //noinspection WrongGradleMethod
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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
    buildFeatures {
        compose = true
        aidl = true
    }
    packaging {
        jniLibs {
            // Must stay true: libaapt2.so is executed as a binary via ProcessBuilder,
            // not loaded as a shared library. With false, Android 10+ does not extract
            // .so to nativeLibraryDir and the fallback files/ dir has noexec mount.
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
            excludes += "META-INF/eclipse.inf"
            excludes += "kotlin/kotlin.kotlin_builtins"
            excludes += "kotlin/ranges/ranges.kotlin_builtins"
            excludes += "kotlin/reflect/reflect.kotlin_builtins"
            excludes += "kotlin/collections/collections.kotlin_builtins"
            excludes += "kotlin/coroutines/coroutines.kotlin_builtins"
            excludes += "kotlin/annotation/annotation.kotlin_builtins"
            excludes += "kotlin/internal/internal.kotlin_builtins"
            excludes += "about_files/LICENSE-2.0.txt"
            excludes += "plugin.xml"
            excludes += "plugin.properties"
            // Javac compiler localized messages (not visible to users)
            excludes += "com/sun/tools/javac/resources/compiler_ja.properties"
            excludes += "com/sun/tools/javac/resources/compiler_zh_CN.properties"
            excludes += "com/sun/tools/javac/resources/javac_ja.properties"
            excludes += "com/sun/tools/javac/resources/javac_zh_CN.properties"
            excludes += "com/sun/tools/javac/resources/launcher_ja.properties"
            excludes += "com/sun/tools/javac/resources/launcher_zh_CN.properties"
            // Javap / doclint localized messages (unused)
            excludes += "com/sun/tools/javap/resources/javap_ja.properties"
            excludes += "com/sun/tools/javap/resources/javap_zh_CN.properties"
            excludes += "com/sun/tools/doclint/resources/doclint_ja.properties"
            excludes += "com/sun/tools/doclint/resources/doclint_zh_CN.properties"
            // JAXP/Xerces localized messages
            excludes += "org/openjdk/com/sun/org/apache/xerces/internal/impl/msg/*_*.properties"
            excludes += "org/openjdk/com/sun/org/apache/xml/internal/serializer/output_*.properties"
        }
    }
    lint {
        baseline = file("lint-baseline.xml")
    }
}

val copyGradleHostJar by tasks.registering(Copy::class) {
    dependsOn(":gradle-host:shadowJar")
    from(project(":gradle-host").layout.buildDirectory.file("libs"))
    into(layout.projectDirectory.dir("src/main/assets"))
    include("vibeapp-gradle-host-*-all.jar")
    rename { "vibeapp-gradle-host.jar" }
}

// Copy Shadow's loader + runtime APKs into assets/shadow/. At first run
// the app extracts them to filesDir/shadow/ so DexClassLoader can mmap
// them from a regular file path. See Phase 5b-3 in
// docs/superpowers/plans/2026-04-19-v2-phase-5b-shadow-full-integration.md.
val copyShadowApks by tasks.registering(Copy::class) {
    dependsOn(":shadow-loader-apk:assembleRelease", ":shadow-runtime-apk:assembleRelease")
    from(project(":shadow-loader-apk").layout.buildDirectory.file("outputs/apk/release/shadow-loader-apk-release-unsigned.apk")) {
        rename { "loader.apk" }
    }
    from(project(":shadow-runtime-apk").layout.buildDirectory.file("outputs/apk/release/shadow-runtime-apk-release-unsigned.apk")) {
        rename { "runtime.apk" }
    }
    into(layout.projectDirectory.dir("src/main/assets/shadow"))
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(copyGradleHostJar)
    dependsOn(copyShadowApks)
}

configurations.all {
    exclude(group = "com.intellij", module = "annotations")
    // bundletool JAR contains both DEX and Java bytecode, which breaks R8
    exclude(group = "com.android.tools.build", module = "bundletool")
}

dependencies {
    // Android
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.viewmodel)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.coil.compose)

    // SplashScreen
    implementation(libs.splashscreen)

    // DataStore
    implementation(libs.androidx.datastore)

    // Dependency Injection
    implementation(libs.hilt)
    implementation(libs.androidx.lifecycle.runtime.compose.android)
    ksp(libs.hilt.compiler)

    // Ktor
    implementation(libs.ktor.content.negotiation)
    implementation(libs.ktor.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.logging)
    implementation(libs.ktor.serialization)

    // License page UI
    implementation(libs.auto.license.core)
    implementation(libs.auto.license.ui)

    // Markdown
    implementation(libs.compose.markdown)
    implementation(libs.compose.markdown.code)

    // Navigation
    implementation(libs.hilt.navigation)
    implementation(libs.androidx.navigation)

    // Room
    implementation(libs.room)
    ksp(libs.room.compiler)
    implementation(libs.room.ktx)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // HTML parsing (web search)
    implementation(libs.jsoup)

    // Hidden API bypass — lets plugin inspector reflect WindowManagerGlobal.getRootViews()
    // so that dialogs / popup menus / bottom sheets are visible to the agent on API 30+.
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")

    implementation(project(":build-engine"))
    implementation(project(":shadow-runtime"))
    implementation(project(":build-runtime"))
    implementation(project(":build-gradle"))
    implementation(project(":plugin-host"))

    // Test
    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    debugImplementation(libs.chucker.debug)
    releaseImplementation(libs.chucker.release)
}

aboutLibraries {
    // Remove the "generated" timestamp to allow for reproducible builds
    export {
        excludeFields.add("generated")
    }
}
