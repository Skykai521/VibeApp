# v2.0 Phase 3a: Project template generator

**Goal:** turn the hardcoded probe-app sources from Phase 2d/2e into a reusable Compose project template with `{{VAR}}` substitution, so any future project (debug button now, agent-driven `create_project` tool later) can spin up a buildable Compose app from one canonical asset tree.

**Architecture:** the existing `ProjectStager` (Phase 2d) handled file-content substitution in `.tmpl` files. Phase 3a extends it to substitute path components too — so a directory named `{{PACKAGE_PATH}}` renders to `com/vibe/example/`. A new Android-side `GradleProjectInitializer` extracts the template tree from `:build-gradle`'s `assets/templates/<name>/`, computes the canonical variable map (`PROJECT_NAME`, `PACKAGE_NAME`, `PACKAGE_PATH`, `SDK_DIR`, `GRADLE_USER_HOME`), and delegates to `ProjectStager.stage()`. The duplicate probe-app trees under `:build-gradle`'s androidTest assets and `:app`'s main assets are deleted; both consumers (instrumented test + debug UI button) now go through the initializer for the same code path.

**Tech stack:** unchanged from Phase 2e. Template pinned at AGP 9.1.0, Kotlin 2.0.21, Compose BOM 2024.06.00, compileSdk=34.

## Scope boundary

- **Single template only.** `KotlinComposeApp` ships; multi-template registry is future scope.
- **Variable map is fixed.** Caller passes specific input fields; arbitrary `{{VAR}}` keys aren't part of the public API yet.
- **No pre-generation validation.** `packageName` is trusted; sanitizing user-typed package names belongs to the agent-tool layer in Phase 3b.
- **No legacy migration.** v1 project archive handling stays in Phase 6.

## Executed changes

| File | Change |
|---|---|
| `build-gradle/src/main/kotlin/com/vibe/build/gradle/ProjectStager.kt` | Added `substitutePath()` — splits each rel path on `File.separatorChar`, substitutes `{{VAR}}` per component, joins back. Variables containing `/` (e.g. `PACKAGE_PATH`) expand into multi-segment paths cleanly. |
| `build-gradle/src/test/kotlin/com/vibe/build/gradle/ProjectStagerTest.kt` | New test: `substitutes_in_path_components_including_multi_segment_vars`. |
| `build-gradle/src/main/assets/templates/KotlinComposeApp/` | New tree (9 files). 5 `.tmpl` files (settings.gradle.kts, app/build.gradle.kts, MainActivity.kt, strings.xml, gradle.properties, local.properties). The Kotlin source dir is named `{{PACKAGE_PATH}}/` literally — ProjectStager renames it on stage. MainActivity is now a Counter app with a `+1` button (more interesting than empty). |
| `build-gradle/src/main/kotlin/com/vibe/build/gradle/GradleProjectInitializer.kt` | New `@Singleton`. Public `initialize(Input)` takes templateName/projectName/packageName/sdkDir/gradleUserHome/destinationDir. Internally extracts the asset tree to a cache subdir, computes vars (PACKAGE_PATH = packageName.replace('.', '/')), delegates to ProjectStager. |
| `build-gradle/src/androidTest/kotlin/com/vibe/build/gradle/GradleProjectInitializerInstrumentedTest.kt` | New test. Asserts top-level files exist, `.tmpl` content substitutions land (rootProject.name, namespace, sdk.dir), and `{{PACKAGE_PATH}}` directory rename works (`app/src/main/kotlin/com/vibe/counter/MainActivity.kt`). Does NOT run Gradle — the e2e build coverage stays in `EmptyApkBuildInstrumentedTest`. |
| `build-gradle/src/androidTest/kotlin/com/vibe/build/gradle/EmptyApkBuildInstrumentedTest.kt` | Refactored to call `GradleProjectInitializer.initialize(...)` instead of staging the deleted probe-app fixture. The e2e build now exercises the same code path the agent will use. |
| `build-gradle/src/androidTest/assets/probe-app/` | Deleted. |
| `app/src/main/assets/probe-app/` | Deleted. |
| `app/src/main/kotlin/com/vibe/app/feature/bootstrap/BuildRuntimeDebugViewModel.kt` | Replaced `projectStager: ProjectStager` injection with `projectInitializer: GradleProjectInitializer`. Removed local `copyAssetDir` helper; the initializer handles asset extraction. Renamed the test project from "probe" to "counter". |
| `app/src/main/kotlin/com/vibe/app/feature/bootstrap/BuildRuntimeDebugScreen.kt` | Button label updated: "Generate Counter project + assembleDebug". |

## Exit criteria

- [x] `ProjectStagerTest` passes new path-substitution test + existing two.
- [x] `GradleProjectInitializerInstrumentedTest` passes — proves asset extraction + variable rendering + `{{PACKAGE_PATH}}` directory rename all work on-device.
- [x] `EmptyApkBuildInstrumentedTest` (Phase 2e e2e) still passes through the new initializer path — installable Compose APK in ~255s.
- [x] `:app:kspDebugKotlin` and `:app:assembleDebug` still green (the new debug-button wiring through GradleProjectInitializer compiles and links).
- [x] No references remain to the deleted probe-app assets.

## Exit Log (2026-04-19)

**Validated on:** Pixel 7 Pro emulator (API 31, arm64-v8a, 6 GB RAM). Dev server at `http://localhost:8000`.

**Result:**
- `ProjectStagerTest`: 3/3 tests pass.
- `GradleProjectInitializerInstrumentedTest`: 1/1 pass — confirms the bundled `KotlinComposeApp` template extracts and renders correctly.
- `EmptyApkBuildInstrumentedTest` (e2e via initializer): pass in 4m 15s — produces an installable APK whose dex contains `androidx.compose.*` classes.
- `:app:kspDebugKotlin`, `:app:assembleDebug`: green.

**Side benefit:** removed ~400 lines of duplication (two copies of probe-app + two `copyAssetDir` helpers). The instrumented test now covers the same staging path as the production debug-UI button, so any future template-generation regression surfaces in CI.

**Phase 3a is complete.** Phase 3b (Agent build/install tools — `RunGradleTool`, `InstallApkTool`, updated workspace path resolution for `read/write/list_project_files`) is unblocked.
