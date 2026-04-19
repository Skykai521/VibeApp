# Phase 5b exit log — Tencent Shadow integration

> Companion doc to the plan at `docs/superpowers/plans/2026-04-19-v2-phase-5b-shadow-full-integration.md`.
> Records what landed across 5b-1 through 5b-7, what's still untested,
> and the on-device checklist gating the `plugin/legacy/` deletion.

## Commit map

| Phase | Commit | Summary |
|---|---|---|
| 5b-1 | — | Audited v1 plugin host surface (report only, no code). |
| 5b-2 | `98281a9` → `ac22d9e` | Vendored Tencent Shadow source at commit `4e9f70a660`. Wired 16 modules (9 core + 7 dynamic) and 3 support modules (`:shadow-codegen-runner`, `:shadow-java-build-config`, `:shadow-{loader,runtime}-apk`) into the Gradle graph. All build clean. |
| 5b-3 | `94e0be8` | Produce `loader.apk` + `runtime.apk` from thin `com.android.application` wrappers. `copyShadowApks` task bundles both into `app/src/main/assets/shadow/`. |
| 5b-4A | `25eaf88` | Refactor: move `com.vibe.app.plugin.{PluginManager,PluginContainerActivity,PluginInspectorService,PluginResourceLoader}` to `com.vibe.app.plugin.legacy`; update 4 caller imports + 10 manifest entries. Scaffold `com.vibe.app.plugin.v2.ShadowPluginHost`. Delete `:plugin-host` stub module. |
| 5b-4B | `78b2f39` | Wire ShadowPluginHost through Shadow's `PluginManagerThatUseDynamicLoader`. New files: `ShadowApkExtractor` (asset→filesDir with SHA-256 gate), `ShadowPluginProcessService` (manifested with `android:process=":shadow_plugin"`), `ShadowPluginManager` (concrete manager subclass), `ShadowPluginZipBuilder` (Shadow-format plugin zip). Full `launchPlugin` orchestration: extract → install → bind → loadRunTime → loadPluginLoader → loadPlugin → callApplicationOnCreate → convertActivityIntent → startActivity. |
| 5b-5 | `752eba9` | Apply Shadow Gradle plugin to `KotlinComposeApp` template. `:shadow-{gradle-plugin,transform,transform-kit,manifest-parser}` all get `maven-publish`; new `:app:copyShadowPluginRepo` publishes into a zip bundled as `assets/shadow/plugin-repo.zip` (~275 KB). New `ShadowPluginRepoExtractor` extracts to `filesDir/shadow/plugin-repo/` at first project creation. Template `settings.gradle.kts.tmpl`, `libs.versions.toml`, `app/build.gradle.kts.tmpl` updated to apply `com.tencent.shadow.plugin` with `useHostContext = ["com.vibe.app.plugin.IPluginInspector"]`. `assemble_debug_v2` invokes `:app:assemblePluginDebug`; `run_in_process_v2` reads `apk/plugin/debug/app-plugin-debug.apk` and calls `ShadowPluginHost.launchPlugin`. |
| 5b-6 | `72ae98a` | Path B Compose shim: new `ShadowComposeActivity.kt.tmpl` in template. Extends `Activity` (rewritten to `ShadowActivity` post-transform), implements `LifecycleOwner`/`SavedStateRegistryOwner`/`ViewModelStoreOwner` by hand, exposes `setComposeContent { }`. `MainActivity.kt.tmpl` extends it; template deps add explicit `lifecycle-viewmodel` + `savedstate`. Agent system prompt updated with "v2 Compose scaffolding — don't touch ComponentActivity" callout. Path A (fork ShadowActivity to extend ComponentActivity) ruled out because `GeneratedPluginActivity` extends `ShadowContext`, not `Activity`. |
| 5b-7 | (this commit) | Implement `ShadowPluginHost.finishPluginAndReturn` — brings `com.vibe.app.presentation.ui.main.MainActivity` to foreground via `FLAG_ACTIVITY_REORDER_TO_FRONT`. `CloseAppTool` fans out to both hosts. This exit log. No code deletions yet. |

## Known gaps — not implemented, not tested

### 1. Inspector bridge is a stub

`ShadowPluginHost.getInspector(projectId)` returns `null`. `RunInProcessV2Tool` detects this and returns a `"running"` status without a `view_tree`. `InspectUiTool` / `InteractUiTool` still inject legacy `PluginManager`, so calling them on a Shadow-hosted project returns `"plugin not running"`.

To fix: declare a `ShadowPluginInspectorService` in the host manifest with `android:process=":shadow_plugin"` (same process as Shadow-hosted plugins). Host binds to it over `IPluginInspector` AIDL. Inside the shadow_plugin process the service walks the foreground `PluginContainerActivity`'s view tree — v1's `PluginInspectorService.dumpViewTree` logic can port over almost verbatim; the only change is finding the foreground Activity (no `ActivityHolder` between a user's Activity and the container under Shadow — you always want the container's decor view).

### 2. `RunInProcessV2Tool` launch has never been exercised

The `launchPlugin` pipeline in `ShadowPluginHost` compiles but has never been run against a Shadow-transformed plugin APK. The first time someone does `create_compose_project` → `assemble_debug_v2` → `run_in_process_v2`, something will break. Likely suspects in order of probability:

- **Shadow transform fails on the template.** If `useHostContext` isn't permissive enough (or if Compose / Compose runtime classes reference things we haven't whitelisted) the `:app:assemblePluginDebug` build itself errors out. Fix is to add classes to `useHostContext`.
- **The zip builder emits a malformed config.json.** `PluginConfig.parseFromJson` is strict about which fields exist (`version`, `UUID_NickName`, `pluginLoader`, `runtime`, `plugins`). Walk the debugger through `UnpackManager.getConfigJson` if `installPluginFromZip` throws.
- **`bindPluginProcessService` races with `awaitServiceConnected`.** Happens if the process hasn't initialised. The `SERVICE_CONNECT_TIMEOUT_SEC=10` default may not be enough on a cold start; bump if it flaps.
- **Reflective access to `mPluginLoader` breaks.** `ShadowPluginHost.loaderBinder()` reaches into `PluginManagerThatUseDynamicLoader`'s protected field via reflection. If R8 shrinks it or upstream renames it, the reflection returns null and the host fails with a clear NPE at `loader.loadPlugin`.

### 3. `:shadow-runtime` v1 stub + `plugin/legacy/` still live

Both are deliberately kept. The plan gates deletion on the E2E checklist below. Deleting either before validation would lose the fallback path if Shadow doesn't fly.

### 4. Multi-project / LRU behaviour

`ShadowPluginHost` keeps an `installed: Map<projectId, InstalledProject>` but doesn't evict. Shadow uses one plugin process (`:shadow_plugin`) for all projects; there's no equivalent of v1's 5-slot LRU yet. Not a blocker — just means concurrent projects share one process. Port the slot policy onto Shadow later if it matters.

## On-device validation checklist

Before deleting `plugin/legacy/` (Phase 5b-7 proper):

- [ ] `create_compose_project` lays down a v2 project with the Shadow plugin applied + the template's Compose shim.
- [ ] `assemble_debug_v2` runs `:app:assemblePluginDebug` to completion. Verify `app/build/outputs/apk/plugin/debug/app-plugin-debug.apk` exists and is Shadow-transformed (decompile MainActivity, check that its superclass chain leads to `com.tencent.shadow.core.runtime.ShadowActivity`).
- [ ] `run_in_process_v2` on the freshly-built APK renders the `CounterScreen` (Compose) and the `+1` button increments on tap.
- [ ] Add a Fragment to the plugin (NavigationCompose with `NavHost { ... }`, or a bare `FragmentContainerView`). Plugin launches without `NoSuchMethodError` and the fragment's UI renders.
- [ ] Add a Snackbar call inside the plugin. Snackbar shows.
- [ ] Add a second Activity in the plugin, navigate to it from `MainActivity` via `startActivity`. Second Activity renders.
- [ ] Add a Service in the plugin (bound or started, doesn't matter). Plugin starts the service, plugin-side callbacks fire.
- [ ] Wire the inspector bridge (see gap 1). Verify `inspect_ui` returns the expected view tree for a Shadow-hosted plugin.
- [ ] `close_app` brings VibeApp to the foreground — plugin disappears from view.
- [ ] Re-test `run_in_process_v2` on the same projectId; install cache is reused, second launch is fast.

When all of the above pass:

- [ ] Delete `app/src/main/kotlin/com/vibe/app/plugin/legacy/` (4 files).
- [ ] Delete v1 slot entries from `AndroidManifest.xml` (`PluginSlot0..4` activities + `PluginInspectorSlot0..4` services).
- [ ] Remove `implementation(project(":shadow-runtime"))` from `app/build.gradle.kts` and `include(":shadow-runtime")` from `settings.gradle.kts`; delete the `shadow-runtime/` module.
- [ ] Switch `LaunchAppTool`, `InspectUiTool`, `InteractUiTool` to inject `ShadowPluginHost` (they currently use legacy `PluginManager`).
- [ ] Drop the `CloseAppTool` fan-out — it can inject only `ShadowPluginHost`.
- [ ] Bump `versionCode` / `versionName` in `app/build.gradle.kts`.

## Where to look when Shadow misbehaves

- Host-side load: `com.vibe.app.plugin.v2.ShadowPluginHost` (orchestrator), `ShadowPluginManager` (manager subclass).
- Plugin-process service: manifest entry `com.vibe.app.plugin.v2.ShadowPluginProcessService` with `android:process=":shadow_plugin"`. Logcat tag `PluginProcessService` (from Shadow).
- Zip format: `ShadowPluginZipBuilder` writes `config.json` per `PluginConfig.parseFromJson` (Shadow `core/manager/.../PluginConfig.java`).
- Bytecode transform rules: `:shadow-transform/src/main/kotlin/com/tencent/shadow/core/transform/specific/*.kt`.
- Flavor wiring: `:shadow-gradle-plugin`'s `ShadowPlugin.addFlavorForTransform` — creates the `normal` + `plugin` flavors under the `Shadow` dimension.
