# v2.0 Phase 0: Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Scaffold three new empty Gradle modules (`:build-runtime`, `:build-gradle`, `:plugin-host`) on a fresh `v2-arch` branch, wired into Hilt and CI, without breaking existing builds. Old `:build-engine` stays intact.

**Architecture:** Each new module is a `com.android.library` with Kotlin support and a single placeholder `@Singleton` class exposed via Hilt. App module depends on all three. CI gets added build + lint jobs for the new modules. Nothing in v1 Java/XML generation flow changes.

**Tech Stack:** Gradle Kotlin DSL, AGP 9.1.0, Kotlin 2.3.10, Hilt 2.59.2, KSP 2.3.6 (versions inherited from repo's `libs.versions.toml` — do not add new versions in Phase 0).

---

## Spec References

- Design doc: `docs/superpowers/specs/2026-04-18-v2-gradle-compose-arch-design.md`
- This plan implements §10 Phase 0 only.

## File Structure

**AGP 9.x note (correction applied 2026-04-18 during Task 5):** AGP 9.1.0 bundles Kotlin support; applying the standalone `org.jetbrains.kotlin.android` plugin errors out. The Tasks 2-4 code blocks below have been updated to drop that plugin alias + the `kotlinOptions {}` block. Java level is enforced via `compileOptions` only. Each new module also carries a `.gitignore` containing `/build` to match existing module convention.

**Create (all new, per module):**

- `<module>/.gitignore` — one line `/build`.
- `<module>/build.gradle.kts` — Android library + Hilt + KSP. Kotlin support comes from AGP automatically.
- `<module>/consumer-rules.pro` — empty placeholder.
- `<module>/proguard-rules.pro` — empty placeholder.
- `<module>/src/main/AndroidManifest.xml` — empty `<manifest>` with namespace only.
- `<module>/src/main/kotlin/<package path>/<Class>.kt` — placeholder `@Singleton` class.

Applied to each of `build-runtime/` (class `BuildRuntime`), `build-gradle/` (class `GradleBuildService`), `plugin-host/` (class `PluginHost`).

- `app/src/main/kotlin/com/vibe/app/di/BuildRuntimeModule.kt` — Hilt stub.
- `app/src/main/kotlin/com/vibe/app/di/BuildGradleModule.kt` — Hilt stub.
- `app/src/main/kotlin/com/vibe/app/di/PluginHostModule.kt` — Hilt stub.

**Modify:**

- `settings.gradle.kts` — add 3 `include()` calls.
- `app/build.gradle.kts` — add 3 `implementation(project(...))` entries.
- `.github/workflows/android.yml` — assemble job already covers all sub-modules transitively via `app:assembleDebug`; add a dedicated lint job for new modules.

**Do NOT touch in Phase 0:**

- `:build-engine` or any `build-tools/*` (removal happens in Phase 6).
- `app/src/main/assets/agent-system-prompt.md`.
- Any `feature/` code.
- Any Room entity / DB migration.

## Namespace Convention

Following existing `com.vibe.build.engine` pattern:

| Module | Android namespace | Kotlin package root |
|---|---|---|
| `:build-runtime` | `com.vibe.build.runtime` | `com.vibe.build.runtime` |
| `:build-gradle` | `com.vibe.build.gradle` | `com.vibe.build.gradle` |
| `:plugin-host` | `com.vibe.plugin.host` | `com.vibe.plugin.host` |

## SDK / Java Versions

Match existing `:build-engine` conventions:

- `compileSdk = 36`
- `minSdk = 29`
- `sourceCompatibility = JavaVersion.VERSION_11`
- `targetCompatibility = JavaVersion.VERSION_11`
- `jvmTarget = "11"` (for Kotlin)

These are VibeApp internal SDK levels, **not** the generated-app SDK levels (which stay 24/34 per spec §2.3).

---

## Task 1: Branch setup

**Files:** none (git operation only)

- [ ] **Step 1: Verify on dev branch and clean tree**

```bash
git status --short
git branch --show-current
```

Expected: `dev` branch, empty `git status` output.

- [ ] **Step 2: Create v2-arch branch**

```bash
git checkout -b v2-arch
git branch --show-current
```

Expected: `v2-arch`.

- [ ] **Step 3: Verify existing build still works on the new branch**

```bash
./gradlew --no-daemon help
```

Expected: BUILD SUCCESSFUL. (No code changes yet — just sanity check.)

---

## Task 2: Add `:build-runtime` module skeleton

**Files:**
- Create: `build-runtime/build.gradle.kts`
- Create: `build-runtime/consumer-rules.pro`
- Create: `build-runtime/proguard-rules.pro`
- Create: `build-runtime/src/main/AndroidManifest.xml`
- Create: `build-runtime/src/main/kotlin/com/vibe/build/runtime/BuildRuntime.kt`

- [ ] **Step 1: Create `build-runtime/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.android.hilt)
    alias(libs.plugins.kotlin.ksp)
}

android {
    namespace = "com.vibe.build.runtime"
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
}

dependencies {
    implementation(libs.hilt)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
}
```

- [ ] **Step 2: Create empty `build-runtime/consumer-rules.pro`**

```
# Consumer ProGuard rules for :build-runtime.
# No rules required for Phase 0 (placeholder module).
```

- [ ] **Step 3: Create empty `build-runtime/proguard-rules.pro`**

```
# ProGuard rules for :build-runtime release builds.
# No rules required for Phase 0 (placeholder module).
```

- [ ] **Step 4: Create `build-runtime/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" />
```

- [ ] **Step 5: Create placeholder class `build-runtime/src/main/kotlin/com/vibe/build/runtime/BuildRuntime.kt`**

```kotlin
package com.vibe.build.runtime

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 0 placeholder. Real implementation lands in Phase 1 (native process
 * runtime: bootstrap, exec wrapper, NativeProcess API).
 *
 * See docs/superpowers/specs/2026-04-18-v2-gradle-compose-arch-design.md §3.
 */
@Singleton
class BuildRuntime @Inject constructor() {
    fun version(): String = "phase-0"
}
```

- [ ] **Step 6: Do NOT add to settings.gradle.kts yet** (done in Task 5 after all three modules exist — keeps each "add module" task self-contained)

Verification deferred to Task 5.

---

## Task 3: Add `:build-gradle` module skeleton

**Files:**
- Create: `build-gradle/build.gradle.kts`
- Create: `build-gradle/consumer-rules.pro`
- Create: `build-gradle/proguard-rules.pro`
- Create: `build-gradle/src/main/AndroidManifest.xml`
- Create: `build-gradle/src/main/kotlin/com/vibe/build/gradle/GradleBuildService.kt`

- [ ] **Step 1: Create `build-gradle/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.android.hilt)
    alias(libs.plugins.kotlin.ksp)
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
}

dependencies {
    implementation(project(":build-runtime"))
    implementation(libs.hilt)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
}
```

- [ ] **Step 2: Create empty `build-gradle/consumer-rules.pro`**

```
# Consumer ProGuard rules for :build-gradle.
# No rules required for Phase 0 (placeholder module).
```

- [ ] **Step 3: Create empty `build-gradle/proguard-rules.pro`**

```
# ProGuard rules for :build-gradle release builds.
# No rules required for Phase 0 (placeholder module).
```

- [ ] **Step 4: Create `build-gradle/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" />
```

- [ ] **Step 5: Create placeholder class `build-gradle/src/main/kotlin/com/vibe/build/gradle/GradleBuildService.kt`**

```kotlin
package com.vibe.build.gradle

import com.vibe.build.runtime.BuildRuntime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 0 placeholder. Real implementation lands in Phase 2 (GradleHost child
 * process + Tooling API client + JSON IPC).
 *
 * See docs/superpowers/specs/2026-04-18-v2-gradle-compose-arch-design.md §4.
 */
@Singleton
class GradleBuildService @Inject constructor(
    private val runtime: BuildRuntime,
) {
    fun version(): String = "phase-0 (uses runtime=${runtime.version()})"
}
```

Verification deferred to Task 5.

---

## Task 4: Add `:plugin-host` module skeleton

**Files:**
- Create: `plugin-host/build.gradle.kts`
- Create: `plugin-host/consumer-rules.pro`
- Create: `plugin-host/proguard-rules.pro`
- Create: `plugin-host/src/main/AndroidManifest.xml`
- Create: `plugin-host/src/main/kotlin/com/vibe/plugin/host/PluginHost.kt`

- [ ] **Step 1: Create `plugin-host/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.android.hilt)
    alias(libs.plugins.kotlin.ksp)
}

android {
    namespace = "com.vibe.plugin.host"
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
}

dependencies {
    implementation(project(":build-runtime"))
    implementation(libs.hilt)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
}
```

- [ ] **Step 2: Create empty `plugin-host/consumer-rules.pro`**

```
# Consumer ProGuard rules for :plugin-host.
# No rules required for Phase 0 (placeholder module).
```

- [ ] **Step 3: Create empty `plugin-host/proguard-rules.pro`**

```
# ProGuard rules for :plugin-host release builds.
# No rules required for Phase 0 (placeholder module).
```

- [ ] **Step 4: Create `plugin-host/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" />
```

- [ ] **Step 5: Create placeholder class `plugin-host/src/main/kotlin/com/vibe/plugin/host/PluginHost.kt`**

```kotlin
package com.vibe.plugin.host

import com.vibe.build.runtime.BuildRuntime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 0 placeholder. Real implementation lands in Phase 5 (Shadow host
 * integration, proxy activities, process slot manager, PluginRunner).
 *
 * See docs/superpowers/specs/2026-04-18-v2-gradle-compose-arch-design.md §7.
 */
@Singleton
class PluginHost @Inject constructor(
    private val runtime: BuildRuntime,
) {
    fun version(): String = "phase-0 (uses runtime=${runtime.version()})"
}
```

Verification deferred to Task 5.

---

## Task 5: Register new modules in `settings.gradle.kts`

**Files:**
- Modify: `settings.gradle.kts:26` (append three `include()` calls after the existing `:app` / `:build-engine` includes)

- [ ] **Step 1: Read current state**

```bash
cat settings.gradle.kts
```

Current tail of includes is:

```
include(":app")
include(":build-engine")
include(":build-tools:android-common-resources")
...
include(":shadow-runtime")
```

- [ ] **Step 2: Append three includes after the existing `:shadow-runtime` line**

Use Edit to add these lines immediately after `include(":shadow-runtime")`:

```
include(":build-runtime")
include(":build-gradle")
include(":plugin-host")
```

Final file should end with:

```kotlin
include(":shadow-runtime")
include(":build-runtime")
include(":build-gradle")
include(":plugin-host")
```

- [ ] **Step 3: Verify Gradle recognizes all new modules**

```bash
./gradlew --no-daemon projects
```

Expected output includes (among existing):

```
+--- Project ':build-gradle'
+--- Project ':build-runtime'
+--- Project ':plugin-host'
```

Exit code 0.

- [ ] **Step 4: Assemble each new module in isolation**

```bash
./gradlew --no-daemon :build-runtime:assembleDebug :build-gradle:assembleDebug :plugin-host:assembleDebug
```

Expected: BUILD SUCCESSFUL. Each module produces an empty AAR under `<module>/build/outputs/aar/`.

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts \
        build-runtime/ build-gradle/ plugin-host/
git commit -m "feat(v2): scaffold :build-runtime, :build-gradle, :plugin-host modules

Phase 0 of the v2.0 architecture upgrade. Three empty Hilt-enabled
Android library modules with placeholder @Singleton classes. Each
new module assembles cleanly and is registered in settings.gradle.kts.

App module wiring and CI integration come in subsequent tasks.

Ref: docs/superpowers/specs/2026-04-18-v2-gradle-compose-arch-design.md §10 Phase 0"
```

---

## Task 6: App-side Hilt wiring stubs

**Files:**
- Create: `app/src/main/kotlin/com/vibe/app/di/BuildRuntimeModule.kt`
- Create: `app/src/main/kotlin/com/vibe/app/di/BuildGradleModule.kt`
- Create: `app/src/main/kotlin/com/vibe/app/di/PluginHostModule.kt`

These three Hilt modules in the `app` layer aggregate the new service classes into `SingletonComponent`. Because the placeholder classes use constructor injection (`@Inject constructor()`), Hilt can create them automatically — the explicit `@Provides` methods below exist to make the DI graph self-documenting and to give us a stable hook point for Phase 1-5 when the classes gain dependencies that need non-trivial providers.

- [ ] **Step 1: Create `app/src/main/kotlin/com/vibe/app/di/BuildRuntimeModule.kt`**

```kotlin
package com.vibe.app.di

import com.vibe.build.runtime.BuildRuntime
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the `:build-runtime` service to the application graph.
 * Phase 0: trivial pass-through. Real providers (ProcessLauncher,
 * RuntimeBootstrapper, etc.) land in Phase 1.
 */
@Module
@InstallIn(SingletonComponent::class)
object BuildRuntimeModule {

    @Provides
    @Singleton
    fun provideBuildRuntime(): BuildRuntime = BuildRuntime()
}
```

- [ ] **Step 2: Create `app/src/main/kotlin/com/vibe/app/di/BuildGradleModule.kt`**

```kotlin
package com.vibe.app.di

import com.vibe.build.gradle.GradleBuildService
import com.vibe.build.runtime.BuildRuntime
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the `:build-gradle` service to the application graph.
 * Phase 0: trivial pass-through. Real providers (GradleHost spawner,
 * IPC channel, build event flow) land in Phase 2.
 */
@Module
@InstallIn(SingletonComponent::class)
object BuildGradleModule {

    @Provides
    @Singleton
    fun provideGradleBuildService(
        runtime: BuildRuntime,
    ): GradleBuildService = GradleBuildService(runtime)
}
```

- [ ] **Step 3: Create `app/src/main/kotlin/com/vibe/app/di/PluginHostModule.kt`**

```kotlin
package com.vibe.app.di

import com.vibe.build.runtime.BuildRuntime
import com.vibe.plugin.host.PluginHost
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the `:plugin-host` service to the application graph.
 * Phase 0: trivial pass-through. Real providers (PluginRunner,
 * PluginProcessSlotManager) land in Phase 5.
 */
@Module
@InstallIn(SingletonComponent::class)
object PluginHostModule {

    @Provides
    @Singleton
    fun providePluginHost(
        runtime: BuildRuntime,
    ): PluginHost = PluginHost(runtime)
}
```

Verification deferred to Task 7 (after app module depends on the new modules).

---

## Task 7: Wire app module dependencies

**Files:**
- Modify: `app/build.gradle.kts:168-169` (add three `implementation(project(...))` entries alongside existing `:build-engine` and `:shadow-runtime`)

- [ ] **Step 1: Read current state**

Current lines in `app/build.gradle.kts`:

```kotlin
    implementation(project(":build-engine"))
    implementation(project(":shadow-runtime"))
```

- [ ] **Step 2: Append three new `implementation(project(...))` entries**

After `implementation(project(":shadow-runtime"))`, add:

```kotlin
    implementation(project(":build-runtime"))
    implementation(project(":build-gradle"))
    implementation(project(":plugin-host"))
```

Final block:

```kotlin
    implementation(project(":build-engine"))
    implementation(project(":shadow-runtime"))
    implementation(project(":build-runtime"))
    implementation(project(":build-gradle"))
    implementation(project(":plugin-host"))
```

- [ ] **Step 3: Verify Hilt DI graph compiles**

```bash
./gradlew --no-daemon :app:kspDebugKotlin
```

Expected: BUILD SUCCESSFUL. (This step runs Hilt's KSP pass; if any `@Inject`/`@Provides`/`@Module` is malformed across the three new app-side modules, it fails here with a clear Dagger error.)

- [ ] **Step 4: Full debug assemble**

```bash
./gradlew --no-daemon :app:assembleDebug
```

Expected: BUILD SUCCESSFUL. APK produced at `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts \
        app/src/main/kotlin/com/vibe/app/di/BuildRuntimeModule.kt \
        app/src/main/kotlin/com/vibe/app/di/BuildGradleModule.kt \
        app/src/main/kotlin/com/vibe/app/di/PluginHostModule.kt
git commit -m "feat(v2): wire :build-runtime, :build-gradle, :plugin-host into app Hilt graph

Adds three Hilt modules in com.vibe.app.di that provide placeholder
services from the new Phase 0 modules. App module now depends on all
three new modules.

DI graph verified by ./gradlew :app:kspDebugKotlin. Full debug APK
assembles cleanly.

Ref: docs/superpowers/specs/2026-04-18-v2-gradle-compose-arch-design.md §10 Phase 0"
```

---

## Task 8: Extend CI workflow

**Files:**
- Modify: `.github/workflows/android.yml` — add lint coverage for new modules to the existing `android-lint` job.

The existing `assemble-debug` job already builds the whole dependency graph transitively (app depends on all three new modules). The `unit-tests` job runs `:app:testDebugUnitTest` which is unaffected. The only gap is lint — `:app:lintDebug` doesn't traverse library modules, so we need a multi-module lint invocation.

- [ ] **Step 1: Read current `android-lint` job**

Lines 84-100 of `.github/workflows/android.yml`:

```yaml
  android-lint:
    name: Android Lint
    runs-on: ubuntu-latest
    permissions:
      contents: read
    steps:
      - uses: actions/checkout@v5
      - name: Set up JDK 17
        uses: actions/setup-java@v5
        with:
          java-version: "17"
          distribution: "temurin"
          cache: gradle
      - name: Grant execute permission for gradlew
        run: chmod +x gradlew
      - name: Run Android lint
        run: ./gradlew --no-daemon app:lintDebug
```

- [ ] **Step 2: Update the lint step to cover new modules**

Replace the final step's `run` line:

```yaml
      - name: Run Android lint
        run: ./gradlew --no-daemon app:lintDebug :build-runtime:lintDebug :build-gradle:lintDebug :plugin-host:lintDebug
```

- [ ] **Step 3: Verify locally that each new module's lint runs cleanly**

```bash
./gradlew --no-daemon :build-runtime:lintDebug :build-gradle:lintDebug :plugin-host:lintDebug
```

Expected: BUILD SUCCESSFUL. If lint emits any warning on the placeholder files, treat it as a Phase 0 bug and fix inline (placeholder code should be trivially clean).

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/android.yml
git commit -m "ci(v2): add new Phase 0 modules to android-lint job

Lint for :build-runtime, :build-gradle, :plugin-host now runs in CI
alongside app:lintDebug. assemble-debug already covers the new
modules transitively.

Ref: docs/superpowers/specs/2026-04-18-v2-gradle-compose-arch-design.md §10 Phase 0"
```

---

## Task 9: Final verification

**Files:** none (validation only)

- [ ] **Step 1: Clean build**

```bash
./gradlew --no-daemon clean
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Full assemble + unit tests + lint locally (mirrors CI)**

```bash
./gradlew --no-daemon :app:assembleDebug :app:testDebugUnitTest \
    :app:lintDebug :build-runtime:lintDebug :build-gradle:lintDebug :plugin-host:lintDebug \
    :build-engine:test
```

Expected: BUILD SUCCESSFUL. All existing v1 flows (build-engine tests, app debug APK, lint) pass unchanged.

- [ ] **Step 3: Inspect dependency graph for new modules**

```bash
./gradlew --no-daemon :app:dependencies --configuration debugRuntimeClasspath | grep -E "build-runtime|build-gradle|plugin-host"
```

Expected output (exact lines may vary with Gradle version):

```
+--- project :build-runtime
+--- project :build-gradle
|    \--- project :build-runtime (*)
+--- project :plugin-host
|    \--- project :build-runtime (*)
```

Confirms: `:build-gradle` and `:plugin-host` both depend on `:build-runtime`; no reverse dependency.

- [ ] **Step 4: Verify v1 agent chat flow still works on device**

Install the Phase 0 debug APK on a test device and reproduce one end-to-end v1 path:

```bash
./gradlew --no-daemon :app:installDebug
```

Then manually on the device:
1. Open VibeApp.
2. Create a new project (still using v1 Java+XML template).
3. Chat prompt: "make a Hello World app".
4. Tap Build → verify APK is produced.
5. Install and run the generated APK.

Expected: all v1 behavior unchanged. If any regression, stop and diagnose before declaring Phase 0 complete.

- [ ] **Step 5: Push branch to remote**

```bash
git push -u origin v2-arch
```

Expected: remote `v2-arch` branch created and tracks local.

---

## Phase 0 Exit Criteria

All of the following must hold:

- [ ] `v2-arch` branch exists both locally and on origin.
- [ ] `settings.gradle.kts` includes `:build-runtime`, `:build-gradle`, `:plugin-host`.
- [ ] `./gradlew clean assembleDebug` succeeds on a fresh checkout.
- [ ] `./gradlew :app:dependencies` shows all three new modules as direct or transitive deps.
- [ ] Hilt DI graph compiles (no Dagger errors) and the debug APK runs on device.
- [ ] The v1 Java+XML generation & build flow is unchanged end-to-end.
- [ ] CI's `android-lint` job covers the three new modules.
- [ ] No file under `build-engine/`, `build-tools/`, `shadow-runtime/`, or agent prompt assets has been modified.

When all boxes above are checked, Phase 0 is complete. Phase 1 (`:build-runtime` real implementation — bootstrap download, native process, libtermux-exec) begins next.

---

## Self-Review Notes

- **Spec coverage:** §10 Phase 0 task list:
  - "git checkout -b v2-arch" → Task 1
  - "new :build-runtime/:build-gradle/:plugin-host Gradle module" → Tasks 2-4
  - "settings.gradle.kts include" → Task 5
  - "Hilt stub for each module" → Task 6
  - "dependency direction lint rule" → **Deferred** — Phase 0 relies on code review + the transitive dependency report in Task 9 Step 3. A programmatic guard (Gradle task or `dependency-analysis` plugin) is scoped out because the only three new modules are trivially easy to review and adding a custom plugin for this alone is YAGNI at Phase 0. This decision should be revisited if we add more modules before Phase 6.
  - "CI pipeline covers new modules" → Task 8
- **Placeholder scan:** All code blocks contain real code. No "TBD" / "TODO" / "similar to above".
- **Type consistency:** `BuildRuntime`, `GradleBuildService`, `PluginHost` used consistently across tasks (module source + DI modules). Package names match the table in "Namespace Convention".
