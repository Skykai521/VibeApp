pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        // Gradle Tooling API artifacts (used by :gradle-host)
        maven { url = uri("https://repo.gradle.org/gradle/libs-releases") }
    }
}

rootProject.name = "VibeApp"
include(":app")
// v2 drives all builds through on-device Gradle (`:gradle-host` +
// bootstrapped AGP) — the Java/XML on-device compiler pipeline
// (`:build-engine` + `build-tools/*` + `:shadow-runtime` v1 stub) was
// removed in Phase 6 Layer B.
include(":build-runtime")
include(":build-gradle")
include(":gradle-host")

// ── Vendored Tencent Shadow modules (Phase 5b-2 onwards) ──
// Source under third_party/shadow/upstream/, with our build.gradle.kts
// per module. See third_party/shadow/README.md.
include(":shadow-common")
project(":shadow-common").projectDir =
    file("third_party/shadow/upstream/core/common")

// One-shot driver to (re-)produce Shadow's `Generated*.java` source files
// from the vendored ActivityCodeGenerator. Output is committed back into
// the consumer modules; this module is only used when refreshing.
include(":shadow-codegen-runner")
project(":shadow-codegen-runner").projectDir =
    file("third_party/shadow/codegen-runner")

include(":shadow-activity-container")
project(":shadow-activity-container").projectDir =
    file("third_party/shadow/upstream/core/activity-container")

// Real Shadow runtime — separate module from the legacy v1 stub
// :shadow-runtime, which stays around until 5b-4 deletes the v1
// plugin path entirely. Same `com.tencent.shadow.core.runtime` package
// is used in both — at the time of v1 deletion we'll repoint the
// :shadow-runtime name to this directory or just rename consumers.
include(":shadow-core-runtime")
project(":shadow-core-runtime").projectDir =
    file("third_party/shadow/upstream/core/runtime")

include(":shadow-utils")
project(":shadow-utils").projectDir = file("third_party/shadow/upstream/core/utils")

include(":shadow-load-parameters")
project(":shadow-load-parameters").projectDir = file("third_party/shadow/upstream/core/load-parameters")

include(":shadow-manifest-parser")
project(":shadow-manifest-parser").projectDir = file("third_party/shadow/upstream/core/manifest-parser")

include(":shadow-loader")
project(":shadow-loader").projectDir = file("third_party/shadow/upstream/core/loader")

include(":shadow-manager")
project(":shadow-manager").projectDir = file("third_party/shadow/upstream/core/manager")

include(":shadow-transform-kit")
project(":shadow-transform-kit").projectDir = file("third_party/shadow/upstream/core/transform-kit")

include(":shadow-transform")
project(":shadow-transform").projectDir = file("third_party/shadow/upstream/core/transform")

include(":shadow-gradle-plugin")
project(":shadow-gradle-plugin").projectDir = file("third_party/shadow/upstream/core/gradle-plugin")

// Dynamic family — host-side runtime + AIDL surface that lets the host
// load and call into a separately-built loader APK.
include(":shadow-dynamic-apk")
project(":shadow-dynamic-apk").projectDir =
    file("third_party/shadow/upstream/dynamic/dynamic-apk")

include(":shadow-dynamic-host")
project(":shadow-dynamic-host").projectDir =
    file("third_party/shadow/upstream/dynamic/dynamic-host")

include(":shadow-dynamic-loader")
project(":shadow-dynamic-loader").projectDir =
    file("third_party/shadow/upstream/dynamic/dynamic-loader")

include(":shadow-dynamic-loader-impl")
project(":shadow-dynamic-loader-impl").projectDir =
    file("third_party/shadow/upstream/dynamic/dynamic-loader-impl")

include(":shadow-dynamic-manager")
project(":shadow-dynamic-manager").projectDir =
    file("third_party/shadow/upstream/dynamic/dynamic-manager")

include(":shadow-dynamic-host-multi-loader-ext")
project(":shadow-dynamic-host-multi-loader-ext").projectDir =
    file("third_party/shadow/upstream/dynamic/dynamic-host-multi-loader-ext")

include(":shadow-dynamic-manager-multi-loader-ext")
project(":shadow-dynamic-manager-multi-loader-ext").projectDir =
    file("third_party/shadow/upstream/dynamic/dynamic-manager-multi-loader-ext")

// Thin APK wrappers — see third_party/shadow/{loader-apk,runtime-apk}/
// for rationale. Produces the two APKs bundled into the host's assets.
// Shared stub for com.tencent.shadow.coding.java_build_config.BuildConfig.
// Single source of the class; both :shadow-activity-container and
// :shadow-loader depend on it instead of shipping their own copy.
include(":shadow-java-build-config")
project(":shadow-java-build-config").projectDir =
    file("third_party/shadow/java-build-config")

include(":shadow-runtime-apk")
project(":shadow-runtime-apk").projectDir = file("third_party/shadow/runtime-apk")

include(":shadow-loader-apk")
project(":shadow-loader-apk").projectDir = file("third_party/shadow/loader-apk")
