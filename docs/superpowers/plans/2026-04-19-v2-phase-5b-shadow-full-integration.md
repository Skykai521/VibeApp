# v2.0 Phase 5b: Full Tencent Shadow integration

**Goal:** replace VibeApp's hand-rolled lightweight plugin layer with the
official Tencent Shadow stack, so plugin APKs get full Android-runtime
support — Fragment + Snackbar + the four main components + intra-plugin
Activity navigation — instead of the truncated subset our v1 custom
DexClassLoader approach can offer.

**Why heavy:** v1's `app/plugin/` (PluginManager + PluginContainerActivity
+ PluginResourceLoader, ~1500 LoC) implements just enough plugin loading
to demo "an Activity inside VibeApp". It does NOT support Fragments
(no FragmentActivity proxy), Snackbar (CoordinatorLayout requires real
ViewGroup hierarchy through host theme), the other 3 of 4 main
components (Service / BroadcastReceiver / ContentProvider), or
intra-plugin Activity navigation (`startActivity` from a plugin
Activity to another plugin Activity). Tencent Shadow solves all four
out of the box via its proper proxy-component mechanism + bytecode
transform that rewrites user Activities to extend ShadowActivity.

**Why now:** v2 (Compose) projects make this gap more painful — Compose
apps tend to use Fragments-as-Compose-host patterns less, but they still
need a real `ComponentActivity` lifecycle (Lifecycle / SavedStateRegistry
/ ViewModelStore owners) which Shadow's official ShadowActivity provides
via the proxy delegation, and which our v1 PluginContainerActivity
emphatically doesn't.

## Audit findings (Phase 5b-1)

### Code that gets DELETED in 5b-7

| Path | LoC | Role | Replaced by |
|---|---:|---|---|
| `app/src/main/kotlin/com/vibe/app/plugin/PluginManager.kt` | 187 | 5-slot LRU + slot Activity dispatch + Inspector binding | Shadow `PluginManagerThatUseDynamicLoader` (wrapped) |
| `app/src/main/kotlin/com/vibe/app/plugin/PluginContainerActivity.kt` | 386 | DexClassLoader-based Activity host | Shadow `HostActivityDelegate` + `ShadowActivity` |
| `app/src/main/kotlin/com/vibe/app/plugin/PluginResourceLoader.kt` | 110 | Plugin R-table resolution | Shadow's resource merging |
| `app/src/main/kotlin/com/vibe/app/plugin/PluginInspectorService.kt` | 817 | View-tree dump + interaction AIDL | **KEEP** — same AIDL + same impl, just running inside ShadowActivity |
| `app/src/main/kotlin/com/vibe/plugin/host/PluginHost.kt` | 19 | Phase-0 stub | **DELETE** — was always a placeholder |
| `shadow-runtime/src/main/java/com/tencent/shadow/core/runtime/*.java` | 388 | Hand-rolled minimal subset of Shadow runtime classes | Replaced by full vendored Shadow runtime |

### Code that STAYS (and gets re-pointed)

| Path | Change |
|---|---|
| `app/src/main/aidl/com/vibe/app/plugin/IPluginInspector.aidl` | Unchanged. Interface doesn't depend on Shadow vs custom-loader. |
| `app/src/main/kotlin/com/vibe/app/plugin/PluginInspectorService.kt` | Stays — implements the AIDL by walking the View tree of the currently-foregrounded plugin Activity. Works the same regardless of what loaded that Activity. Just needs to register itself inside the ShadowActivity context. |
| `app/src/main/kotlin/com/vibe/app/feature/agent/tool/LaunchAppTool.kt` | Re-points `pluginManager.launchPlugin` calls to the Shadow-wrapped manager. |
| `app/src/main/kotlin/com/vibe/app/feature/agent/tool/RunInProcessV2Tool.kt` | Same re-point + APK source becomes `app-debug-plugin.apk` (Shadow-transformed) instead of `app-debug.apk`. |
| `app/src/main/kotlin/com/vibe/app/feature/agent/tool/UiTools.kt` | `pluginManager.getInspector` / `finishPluginAndReturn` calls re-point. |
| `app/src/main/kotlin/com/vibe/app/presentation/ui/chat/ChatViewModel.kt` | One `pluginManager.launchPlugin` call, re-pointed. |
| `app/src/main/AndroidManifest.xml` lines 56–115 | The 5 PluginSlot Activities + 5 PluginInspectorSlot Services replaced by Shadow's proxy ContainerActivity declarations (still 5 slots, still process-isolated). |

### Toolchain constraints (relevant to 5b-2)

- Kotlin 2.3.10
- AGP 9.1.0 (uses the Variant API, NOT the old Transform API — Shadow's `build-host-plugin` uses old Transform; **we will need to rewrite that part**)
- Gradle 9.3.1
- compileSdk 36, targetSdk 28 (Termux mode), minSdk 29

## Strategy

### 5b-2: Vendor Shadow source under `third_party/shadow/`

- Tencent Shadow last meaningful release: stable tag in 2023, repo
  `https://github.com/Tencent/Shadow`. We'll vendor at HEAD of the
  newest tag and forward-port from there in our repo.
- Layout: `third_party/shadow/` as a git subtree mirror, with a
  README explaining "this is vendored, edit freely, do not blindly
  re-sync from upstream — diverge as needed".
- Modules we actually need (in dependency order):
  - `projects/sdk/core/common-cxx`           → optional native bits
  - `projects/sdk/core/runtime`              → ShadowActivity et al.
  - `projects/sdk/core/loader`               → DynamicLoader
  - `projects/sdk/core/manager`              → DynamicPluginManager
  - `projects/sdk/dynamic-host`              → host integration
  - `projects/sdk/dynamic-runtime`           → runtime APK module
  - `projects/sdk/dynamic-loader`            → loader APK module
  - `projects/plugin-build-tools/build-host-plugin` → Gradle plugin (NEEDS REWRITE)
- Modules we drop: samples, integration tests, demo apps, anything
  in `projects/sample-host` etc.

### 5b-3: Build + bundle Loader / Runtime APKs

- Two new internal Gradle tasks:
  - `:third_party:shadow:dynamic-loader:assembleRelease` → loader APK
  - `:third_party:shadow:dynamic-runtime:assembleRelease` → runtime APK
- App-side `copyShadowApks` task copies them into
  `app/src/main/assets/shadow/` before `mergeDebugAssets`.
- App-side first-run extracts to `filesDir/shadow/` so DexClassLoader
  can mmap from a normal file path.

### 5b-4: Replace PluginManager

- New `app/src/main/kotlin/com/vibe/app/plugin/v2/ShadowPluginHost.kt`
  wraps `PluginManagerThatUseDynamicLoader`. Exposes our existing
  surface (`launchPlugin(apkPath, packageName, projectId, projectName)`,
  `getInspector(projectId)`, `finishPluginAndReturn(projectId)`) so
  the agent tools don't need to change much.
- 5-slot LRU stays — implemented on top of Shadow's process slots
  (Shadow's `ContainerActivity` proxies are themselves process-isolated
  via `android:process`).
- `app/src/main/kotlin/com/vibe/app/plugin/` (the v1 directory) gets
  archived under `app/src/main/kotlin/com/vibe/app/plugin/legacy/`
  during 5b-4 (so we can keep v1 LEGACY plugin loading working
  side-by-side until 5b-7 deletes it).
- `:plugin-host` and `:shadow-runtime` modules: depending on whether
  the vendored Shadow modules cover these roles, either delete or
  re-purpose as our application-side glue.

### 5b-5: Apply Shadow build plugin to v2 KotlinComposeApp template

- Add `id 'com.tencent.shadow.plugin.build_host.v2'` to template
  `app/build.gradle.kts.tmpl`.
- Update `GradleBuildService.runBuild` callers (or add a new task
  list option) so `assemble_debug_v2` ALSO runs `:app:assembleDebugPlugin`.
- `RunInProcessV2Tool` switches to using
  `app/build/outputs/apk-plugin/debug/app-debug-plugin.apk` (or whatever
  path the Shadow plugin outputs to).

### 5b-6: Compose compatibility shim

- ShadowActivity extends `Activity` (not `ComponentActivity`).
  Compose requires:
  - `LifecycleOwner` (provided to `setContent { ... }`)
  - `SavedStateRegistryOwner`
  - `ViewModelStoreOwner`
  - `OnBackPressedDispatcherOwner` (for `BackHandler {}`)
- Two paths:
  - **Path A (preferred):** fork Shadow's ShadowActivity in our vendored
    copy to extend `ComponentActivity`. Risk: Shadow's HostActivityDelegate
    bytecode rewrite might break with the new superclass. Need to test.
  - **Path B (fallback):** keep ShadowActivity as-is, manually wire all
    four owners via a Compose host helper that constructs them inside
    `setContent`. Workable; widely used by community plugin frameworks.
    Brittle around config changes / process death.
- We'll start with Path A in 5b-6, fall back to Path B if Shadow's
  build plugin rejects the new base class.

### 5b-7: E2E + delete v1 code

- Build a v2 Compose project with the Shadow plugin, run via
  `run_in_process_v2`, verify:
  - Counter Activity renders
  - Add a Fragment (NavigationCompose / FragmentContainerView), verify
  - Show a Snackbar, verify
  - Navigate to a second Activity in the plugin, verify
  - Spin up a Service in the plugin, verify
- Delete `app/src/main/kotlin/com/vibe/app/plugin/legacy/` once the
  Shadow path passes all of the above + v1 LEGACY projects also work
  via the same Shadow path.
- `:plugin-host` and `:shadow-runtime` modules: delete (replaced by
  vendored Shadow modules).

## Risk register

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Shadow's `build-host-plugin` uses pre-AGP-7 Transform API and won't even apply on AGP 9.x | High | Blocks 5b-5 entirely | Rewrite as AGP Variant API + AsmClassVisitorFactory in 5b-2; test on a minimal sample early |
| ShadowActivity-as-ComponentActivity (Path A) breaks Shadow's bytecode transform | Medium | Forces us into Path B, ~1 extra week | Try Path A first; if blocked, write the Path B Compose host helper |
| `dynamic-loader` APK doesn't tolerate AGP 9 / Kotlin 2.x | Medium | Have to fork + maintain | Vendor + modify in our repo (which we're doing anyway) |
| Resources differences: v2 Compose doesn't use XML themes the way Shadow's resource merger expects | Low-Medium | Compose UI breaks visually | Most Compose apps use `MaterialTheme {}` programmatically; theme XML usage is minimal in our template |
| `IPluginInspector` doesn't bind cleanly through Shadow's ContainerActivity (the Service is in the plugin process, but Shadow's process model may interfere with manual `bindService`) | Medium | Inspector + agent UI introspection breaks | Re-test after 5b-4; if blocked, host inspector AIDL on host-side and have ShadowActivity post events to it |

## Schedule estimate

| Phase | Estimate | Notes |
|---|---|---|
| 5b-1 | 0.5 day | This audit. **DONE.** |
| 5b-2 | 2-4 days | Vendor + AGP/Kotlin compat. The `build-host-plugin` rewrite is the unknown. |
| 5b-3 | 1 day | Just gradle plumbing. |
| 5b-4 | 2-3 days | Code surgery, but well-bounded. |
| 5b-5 | 1-2 days | Template + GradleBuildService change. |
| 5b-6 | 2-5 days | Compose compatibility — variance depends on Path A vs B. |
| 5b-7 | 1 day | If everything before went well. |
| **Total** | **9-16 working days** | Optimistic 1.5 weeks, pessimistic 3 weeks. |

## Exit criteria (overall)

- [x] **5b-1:** audit complete, plan committed.
- [ ] **5b-2:** Shadow modules vendored, all compile under our toolchain.
- [ ] **5b-3:** loader.apk + runtime.apk produced + bundled in main app.
- [ ] **5b-4:** ShadowPluginHost replaces PluginManager; agent tools re-pointed; v1 LEGACY plugin path still works (parallel).
- [ ] **5b-5:** v2 template builds `:app:assembleDebugPlugin`; RunInProcessV2Tool uses the plugin APK.
- [ ] **5b-6:** Counter Compose plugin renders correctly inside ShadowActivity.
- [ ] **5b-7:** E2E passes for Fragment + Snackbar + multi-Activity + Service in a v2 plugin; v1 custom code deleted; exit log written.
