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
    }
}

rootProject.name = "VibeApp"
include(":app")
include(":build-engine")
include(":build-tools:android-common-resources")
include(":build-tools:android-stubs")
include(":build-tools:build-logic")
include(":build-tools:common")
include(":build-tools:eclipse-standalone")
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

