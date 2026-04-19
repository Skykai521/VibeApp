# v2.0 Phase 2e: First Compose APK Implementation Plan

**Goal:** build an installable Kotlin + Jetpack Compose APK via the on-device Gradle pipeline. Same stack as Phase 2d (bootstrap → Termux JDK → GradleHost → Tooling API → Gradle 9.3.1 → AGP 9.1.0 → arm64 aapt2 → javac+kotlinc → d8 → apksigner) — only the probe sources change.

**Architecture:** the Phase 2d probe-app was a Java / no-Compose `android.app.Activity`. Phase 2e upgrades it in-place to Kotlin + Compose: `MainActivity.kt` uses `setContent { MaterialTheme { ... } }`, dependencies pull Compose BOM + activity-compose + core-ktx via Maven. The same `EmptyApkBuildInstrumentedTest` runs, plus a Compose-classes-in-dex assertion. Supporting code changes are minimal (build.gradle.kts plugins, dependency versions).

**Tech stack (pinned after iteration):**
- AGP 9.1.0 — unchanged from Phase 2d. **No separate `org.jetbrains.kotlin.android` plugin** — AGP 9 has built-in Kotlin support and registers the `kotlin` extension itself; adding the standalone plugin conflicts ("Cannot add extension with name 'kotlin'").
- Kotlin 2.0.21 + `org.jetbrains.kotlin.plugin.compose` 2.0.21 — Kotlin 2 switched the Compose compiler from a separate Maven artifact to a Gradle plugin bundled with Kotlin itself.
- Compose BOM `2024.06.00` + `activity-compose:1.9.0` + `core-ktx:1.13.1` + `lifecycle-runtime-ktx:2.8.3` — these are the last versions in their series that still target `compileSdk=34`. Later releases (2024.09+ BOM, core-ktx 1.15+) require compileSdk=35+.
- **compileSdk = targetSdk = 34** — raised from Phase 2d's 33 because Compose BOM 2024.06 minimum is 34. Requires `scripts/bootstrap/build-androidsdk.sh` to bundle `platforms/android-34/` instead of `platforms/android-33/`.
- aapt2: still Termux 13.0.0.6-23 (AOSP 13, self-reports "aapt 2.19"). API 34's resource table is compatible.

## Scope boundary

- **No Compose UI test / screenshot test.** Assert "APK contains `androidx.compose.*` classes in some `classes*.dex`" via a byte scan; verifying actual rendering belongs to a later phase with a real device Activity launch.
- **No Compose preview / tooling deps.** `compose-ui-tooling-preview` + Studio-only tooling not included.
- **No navigation-compose.** Single Activity, single Composable.
- **No material-icons.** M3 defaults only.

## Executed changes

**Modify (probe sources — `build-gradle/src/androidTest/assets/probe-app/` + mirror to `app/src/main/assets/probe-app/`):**

| File | Change |
|---|---|
| `gradle/libs.versions.toml` | Added `kotlin=2.0.21`, `composeBom=2024.06.00`, `activityCompose=1.9.0`, `coreKtx=1.13.1`, `lifecycleRuntime=2.8.3` versions + matching library/plugin entries. |
| `build.gradle.kts` (root) | Added `alias(libs.plugins.kotlin.compose) apply false`. |
| `app/build.gradle.kts` | Added `alias(libs.plugins.kotlin.compose)` (only — no kotlin.android, see Issue #1 in exit log); bumped `compileSdk` + `targetSdk` to 34; added `buildFeatures { compose = true }`, Kotlin `jvmTarget.set(JVM_17)`, 4 Compose/AndroidX `implementation` lines. |
| `app/src/main/java/com/vibe/probe/MainActivity.java` | Deleted. |
| `app/src/main/kotlin/com/vibe/probe/MainActivity.kt` | Created. `ComponentActivity` subclass, `setContent { MaterialTheme { Scaffold { Greeting("Compose") } } }`. |
| `gradle.properties.tmpl` | Bumped heaps: `org.gradle.jvmargs=-Xmx896m -XX:MaxMetaspaceSize=384m`, `kotlin.daemon.jvm.options=-Xmx768m -XX:MaxMetaspaceSize=256m`. |

**Modify (dev bootstrap):**

| File | Change |
|---|---|
| `scripts/bootstrap/build-androidsdk.sh` | `platform_rev` default → `34-ext12_r01`, `platform_api` default → `34`. Env-overridable. |
| `scripts/bootstrap/android-sdk-descriptors/platform-34-package.xml` | New fixture. Derived from platform-33 descriptor by sed (api-level 33→34, path `platforms;android-33` → `platforms;android-34`, display name). |

**Modify (test):**

| File | Change |
|---|---|
| `build-gradle/src/androidTest/kotlin/com/vibe/build/gradle/EmptyApkBuildInstrumentedTest.kt` | Android-jar path assertion → `platforms/android-34/android.jar`. Run-build timeout 600_000 → 1_200_000 (Compose BOM resolution adds several minutes on cold cache). Added `indexOfBytes` helper + post-build scan for `"androidx/compose/"` bytes in all `classes*.dex` entries. |

## Exit criteria

- [x] `EmptyApkBuildInstrumentedTest.probe_app_assembleDebug_produces_installable_apk` passes with Compose probe sources.
- [x] Produced `app-debug.apk` contains `androidx.compose.*` class names in its dex.
- [x] Phase 1 + 2a + 2b + 2c instrumented regression stays green against the new platform-34 SDK tarball.
- [x] `:app:kspDebugKotlin` + `:app:assembleDebug` stay green.
- [x] probe-app duplicated into `app/src/main/assets/probe-app/` for the debug-UI button.

## Exit Log (2026-04-19)

**Validated on:** Pixel 7 Pro emulator (API 31, arm64-v8a), 6 GB RAM. Dev server at `http://localhost:8000` with a 4-component manifest (hello + jdk-17.0.13 + gradle-9.3.1 + android-sdk-36.0.0).

**Result:** `BUILD SUCCESSFUL` in ~210 seconds (cold cache). Compose-classes-in-dex assertion green.

**Regression:**
- `:build-runtime:connectedDebugAndroidTest` — 11/11 pass (Phase 1/2a/2b).
- `:build-gradle:connectedDebugAndroidTest` (Phase 2c `GradleHostInstrumentedTest`) — 1/1 pass.
- `:app:kspDebugKotlin`, `:app:assembleDebug` — green.

**Issues discovered and resolved during iteration:**

1. *`kotlin.android` plugin conflicts with AGP 9's built-in Kotlin.* First attempt applied `alias(libs.plugins.kotlin.android)` in `app/build.gradle.kts`. AGP 9.1.0 registers the `kotlin` extension itself when `com.android.application` is applied; the standalone plugin fails with `Cannot add extension with name 'kotlin', as there is an extension already registered with that name`. Removed the kotlin.android alias from both `plugins {}` blocks. Only `kotlin.compose` is applied.

2. *Root `plugins {}` needs `apply false` for all plugins the subproject uses.* Gradle's multi-project classloader expects every plugin alias used anywhere in the tree to be declared at the root with `apply false` — otherwise `Error resolving plugin ... plugin is already on the classpath with an unknown version`. Moved kotlin.compose declaration up.

3. *`androidx.core:core:1.15.0` requires compileSdk ≥ 35.* AAR metadata check failed. Downgraded to `core-ktx 1.13.1` + `activity-compose 1.9.0` + `lifecycle-runtime-ktx 2.8.3` + `compose-bom 2024.06.00`, the last versions in their respective series that still target compileSdk=34.

4. *Phase 2d's compileSdk=33 was too old for any modern Compose BOM.* Compose BOM 2024.06 requires compileSdk=34+. Tested platform-34 with the Termux AOSP 13 aapt2 — it works fine (resource-table format changes between API 33 and 34 are additive and aapt2-2.19 tolerates them). Raised `platform_api` default in `build-androidsdk.sh` from 33 to 34, added `platform-34-package.xml` fixture.

5. *Test timeout had to grow.* Cold Maven resolution of the Compose BOM closure takes ~2-3 extra minutes over Phase 2d's plain AGP closure. Bumped `withTimeout(600_000)` to `1_200_000`.

**Phase 2e is complete.** The v2 stack can now build installable Compose APKs on-device. Remaining v2 work (Phase 3+): `GradleProjectInitializer` template generator, Agent tool integration (`run_gradle`, `add_dependency`, `install_apk`), build-diagnostic ingest pipeline, Shadow in-process running.
