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
include(":build-engine")
include(":build-tools:android-common-resources")
include(":build-tools:android-stubs")
include(":build-tools:build-logic")
include(":build-tools:common")
include(":build-tools:javac")
include(":build-tools:jaxp")
include(":build-tools:kotlinc")
include(":build-tools:logging")
include(":build-tools:manifmerger")
include(":build-tools:project")
include(":build-tools:snapshots")
include(":build-tools:jaxp:jaxp-internal")
include(":build-tools:jaxp:xml")
include(":shadow-runtime")
include(":build-runtime")
include(":build-gradle")
include(":plugin-host")
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
