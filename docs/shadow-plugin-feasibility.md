# Shadow Plugin Framework Integration Feasibility Analysis

> Evaluate whether Tencent Shadow can be used to run VibeApp-generated APKs inside VibeApp itself,
> without traditional installation, using a "generate final code" approach to bypass Shadow's Gradle plugin.

## 1. Goal Recap

- Generated apps can **run inside VibeApp** (plugin mode) without `REQUEST_INSTALL_PACKAGES`
- Generated apps can still be **independently installed** (standalone mode)
- Each running plugin app uses an **independent process** (crash isolation, memory isolation)
- On-device build pipeline **cannot run Shadow's Gradle plugin** (Javassist transforms), so we must **generate already-transformed source code** directly

---

## 2. Shadow Architecture Primer

Shadow separates five components:

| Component | Description | Where it lives |
|-----------|------------|----------------|
| **Host** | VibeApp itself. Contains proxy Activity/Service shells + `dynamic.host` library (~15KB) | VibeApp APK (static) |
| **Runtime** | Defines `PluginContainerActivity`, delegation interfaces | Bundled in VibeApp assets, loaded dynamically |
| **Loader** | Manages plugin ClassLoader, resource loading, component mapping | Bundled in VibeApp assets, loaded dynamically |
| **Manager** | Handles plugin discovery, loading, version management | Implemented in VibeApp code |
| **Plugin** | The generated app APK with Shadow-compatible bytecode | Generated on-device |

### How plugin Activities work

```
Android System
  → PluginContainerActivity      (real Activity registered in VibeApp manifest)
    → HostActivityDelegate       (delegation interface)
      → ShadowActivityDelegate   (loader implementation)
        → UserActivity           (plugin code, extends ShadowActivity)
```

The system only sees `PluginContainerActivity`. Shadow internally routes lifecycle calls to the plugin's `ShadowActivity` subclass. This is how a plugin runs without being installed.

---

## 3. What Shadow's Gradle Plugin Does (the work we must bypass)

Shadow's Gradle plugin applies **16 Javassist bytecode transforms** to ALL classes in the plugin APK (including dependencies). The critical ones:

### 3.1 Class Rename Transforms

| Original | Replacement |
|----------|-------------|
| `android.app.Activity` | `ShadowActivity` |
| `android.app.Application` | `ShadowApplication` |
| `android.app.Service` | `ShadowService` |
| `android.app.IntentService` | `ShadowIntentService` |
| `android.app.Application$ActivityLifecycleCallbacks` | `ShadowActivityLifecycleCallbacks` |

### 3.2 Non-trivial Transforms

| Transform | What it does |
|-----------|-------------|
| **FragmentSupportTransform** | Handles Fragment in code and XML |
| **DialogSupportTransform** | Transforms Dialog creation |
| **WebViewTransform** | Replaces WebView instantiation |
| **ContentProviderTransform** | Redirects `Uri.parse()`, `ContentResolver` calls |
| **PackageManagerTransform** | Intercepts 9 PackageManager methods |
| **LayoutInflaterTransform** | Redirects `getFactory()`/`getFactory2()` |
| **ReceiverSupportTransform** | Transforms BroadcastReceiver registration |

### 3.3 PluginManifest Generation

Extracts component declarations from the plugin's `AndroidManifest.xml` into a `PluginManifest.java` class that Shadow reads at runtime.

---

## 4. The "Generate Final Code" Approach — Core Idea

Instead of applying Javassist transforms post-compilation, instruct the AI agent to **generate source code that directly uses Shadow runtime classes**:

```java
// Instead of:
public class MainActivity extends AppCompatActivity { ... }

// Generate:
public class MainActivity extends ShadowActivity { ... }
```

### 4.1 What this approach covers

| Transform | Covered by source generation? | Notes |
|-----------|:---:|-------|
| Activity → ShadowActivity | **Partially** | User code only |
| Application → ShadowApplication | **Yes** | User code only |
| Service → ShadowService | **Yes** | User code only |
| PluginManifest generation | **Yes** | Can generate as Java source |
| Fragment/Dialog/WebView transforms | **No** | Deep framework interception |
| ContentProvider/Uri transforms | **No** | Affects all Uri.parse() calls |
| PackageManager transforms | **No** | Affects all PM queries |
| LayoutInflater transforms | **No** | Framework-level interception |

### 4.2 The Critical Blocker: AndroidX

**This is the single biggest obstacle.**

Current generated apps extend `AppCompatActivity`, which is in the bundled `androidx-classes.jar`. The inheritance chain is:

```
AppCompatActivity
  → FragmentActivity
    → ComponentActivity
      → androidx.core.app.ComponentActivity
        → android.app.Activity          ← This must become ShadowActivity
```

Shadow's Gradle plugin rewrites ALL classes in `androidx-classes.jar` so that the chain ends at `ShadowActivity` instead of `android.app.Activity`. Without this transform:

- **User code extends `ShadowActivity` directly** — works but loses all AppCompat/Material functionality
- **User code extends `AppCompatActivity`** — the AndroidX chain still reaches `android.app.Activity`, which does NOT exist in the plugin ClassLoader → **ClassNotFoundException at runtime**

### 4.3 Possible Solutions to the AndroidX Blocker

#### Option A: Pre-transform AndroidX (Recommended)

Run Shadow's Javassist transforms on `androidx-classes.jar` **once** during VibeApp's own Gradle build, producing a `shadow-androidx-classes.jar`. Bundle both:

- `androidx-classes.jar` — for standalone APK builds (current behavior)
- `shadow-androidx-classes.jar` — for plugin mode builds

**Pros:**
- Generated apps can still use `AppCompatActivity`, Material Components, RecyclerView, etc.
- AI agent prompt changes are minimal — only `Application` class needs adjustment
- One-time cost during VibeApp development

**Cons:**
- Must maintain two versions of the AndroidX bundle
- Shadow transform tool needs to be integrated into VibeApp's Gradle build
- When AndroidX is updated, must re-run transforms

#### Option B: Raw ShadowActivity Only (No AndroidX)

Plugin-mode apps extend `ShadowActivity` directly, without AndroidX.

**Pros:**
- Simpler — no transformed AndroidX needed
- Smaller plugin APK

**Cons:**
- **Loses all Material Components** — no MaterialButton, MaterialCardView, Toolbar, etc.
- **Loses AppCompat theming** — no DayNight, no backward-compatible styles
- **Loses Fragment, ViewPager2, RecyclerView, ConstraintLayout** — all in AndroidX
- Generated apps would be drastically more limited
- Two completely different code generation strategies needed (standalone vs plugin)

**Verdict: Unacceptable** — this defeats the purpose of VibeApp's rich UI generation.

#### Option C: Custom "thin" compatibility layer

Create a minimal `VibePluginActivity` that extends `ShadowActivity` and re-implements the subset of `AppCompatActivity` APIs that generated apps actually use (theme, toolbar, etc.).

**Pros:**
- Small, controlled surface
- No need to transform all of AndroidX

**Cons:**
- Enormous implementation effort to reimplement AppCompat behavior
- Ongoing maintenance burden as generated apps evolve
- Bug-for-bug compatibility is nearly impossible

**Verdict: Too costly, too fragile.**

---

## 5. Pre-Transform AndroidX — Technical Deep-Dive

If we go with Option A, the process is:

### 5.1 Build-time step (in VibeApp's Gradle build)

```
Input:  androidx-classes.jar (current bundled classes)
Tool:   Shadow's TransformManager (Javassist)
Output: shadow-androidx-classes.jar

Transforms applied:
  1. Activity → ShadowActivity (all subclasses rewired)
  2. Application → ShadowApplication
  3. Service → ShadowService
  4. Fragment support transforms
  5. Dialog support transforms
  6. LayoutInflater transforms
  7. PackageManager transforms
  8. ContentProvider transforms (Uri.parse redirect)
  9. Receiver support transforms
```

### 5.2 On-device build pipeline changes

When building for **plugin mode**:

| Pipeline stage | Change |
|----------------|--------|
| RESOURCE | No change (AAPT2 doesn't care about Shadow) |
| COMPILE | Use `shadow-androidx-classes.jar` + `shadow-runtime.jar` on classpath instead of `androidx-classes.jar` |
| DEX | Include `shadow-androidx-classes.jar` as program input (instead of `androidx-classes.jar`) |
| PACKAGE | Same as current |
| SIGN | Same as current (or skip signing — Shadow loads unsigned APKs too) |

### 5.3 Additional compile-time dependencies

The plugin classpath needs:

| JAR | Purpose | Size (approx) |
|-----|---------|---------------|
| `shadow-runtime.jar` | ShadowActivity, ShadowApplication, ShadowService, etc. | ~80KB |
| `shadow-androidx-classes.jar` | AndroidX with Shadow transforms applied | ~same as current |
| `android.jar` | Platform APIs (unchanged) | same |

### 5.4 PluginManifest Generation

Before Java compilation, generate `PluginManifest.java` by parsing the project's `AndroidManifest.xml`:

```java
package com.tencent.shadow.core.manifest_parser;

public class PluginManifest {
    public static final String[] ACTIVITIES = {
        "com.vibe.generated.p20260315.MainActivity"
    };
    public static final String APPLICATION_CLASS_NAME =
        "com.vibe.generated.p20260315.CrashHandlerApp";
    // ... services, receivers, providers
}
```

This can be generated as a build pipeline step between RESOURCE and COMPILE.

---

## 6. VibeApp Host-Side Integration

### 6.1 Manifest additions

VibeApp must declare proxy components for each process slot:

```xml
<!-- Process 1 -->
<activity android:name=".shadow.PluginDefaultProxyActivity1"
    android:process=":plugin1" android:exported="false"
    android:launchMode="standard" android:theme="@style/Theme.PluginContainer" />
<activity android:name=".shadow.PluginSingleTaskProxyActivity1"
    android:process=":plugin1" android:exported="false"
    android:launchMode="singleTask" />

<!-- Process 2 -->
<activity android:name=".shadow.PluginDefaultProxyActivity2"
    android:process=":plugin2" android:exported="false" ... />

<!-- ... repeat for N process slots -->

<!-- Plugin process services -->
<service android:name=".shadow.PluginProcessService1"
    android:process=":plugin1" />
<service android:name=".shadow.PluginProcessService2"
    android:process=":plugin2" />
```

**Process slot limit:** Android has a practical limit of ~5 concurrent processes per app before OOM killer becomes aggressive. Recommend **4 plugin process slots** initially.

### 6.2 Host dependencies

```kotlin
// build.gradle.kts (VibeApp)
implementation("com.tencent.shadow.dynamic:host:$shadowVersion")
```

This is the only static dependency (~15KB, ~160 methods).

### 6.3 Runtime/Loader bundling

Shadow's Runtime and Loader APKs are bundled in VibeApp's assets and loaded dynamically. They can be built from Shadow's sample project and customized.

### 6.4 Plugin Manager implementation

VibeApp needs a `PluginManager` implementation that:
1. Takes a generated APK path
2. Selects an available process slot
3. Loads the Runtime + Loader into that process (if not already loaded)
4. Loads the plugin APK
5. Starts the plugin's main Activity via the proxy

---

## 7. AI Agent Prompt Changes

### 7.1 Plugin-mode template differences

| Aspect | Standalone mode (current) | Plugin mode |
|--------|--------------------------|-------------|
| Base Activity | `AppCompatActivity` | `AppCompatActivity` (Shadow-transformed) |
| Base Application | `Application` | `ShadowApplication` |
| Lifecycle callbacks | `ActivityLifecycleCallbacks` | `ShadowActivityLifecycleCallbacks` |
| Compile classpath | `androidx-classes.jar` | `shadow-androidx-classes.jar` + `shadow-runtime.jar` |
| Manifest | Full, with `<intent-filter>` | Components only, no launcher filter |
| Extra generated file | None | `PluginManifest.java` |

### 7.2 Key differences for the AI

The Application class changes:

```java
// Plugin mode:
import com.tencent.shadow.core.runtime.ShadowApplication;

public class CrashHandlerApp extends ShadowApplication {
    // ActivityLifecycleCallbacks → ShadowActivityLifecycleCallbacks
}
```

Activity code **stays the same** (still extends `AppCompatActivity`) because AndroidX is pre-transformed.

### 7.3 What the AI does NOT need to worry about

- Fragment, Dialog, WebView transforms → handled by pre-transformed AndroidX
- URI/ContentProvider redirects → handled by pre-transformed AndroidX
- PackageManager intercepts → handled by pre-transformed AndroidX
- LayoutInflater → handled by pre-transformed AndroidX

---

## 8. Concurrent Plugin Safety

### 8.1 Process isolation

Each plugin runs in its own `:pluginN` process. This provides:
- **Memory isolation** — plugin crash doesn't affect VibeApp or other plugins
- **ClassLoader isolation** — each process has its own ClassLoader hierarchy
- **Native library isolation** — no SO conflicts

### 8.2 Build pipeline concurrency

The existing `BuildMutex` serializes AAPT2 builds. Multiple plugins can be compiled sequentially. This is already handled.

### 8.3 Resource ID conflicts

Shadow uses resource partition IDs >= `0x80` for plugins (host uses `0x7f`). Multiple plugins don't conflict because each is loaded in a separate process with its own resource table.

---

## 9. Dual-Mode Build Support

The on-device pipeline must support both modes. Proposed approach:

```
CompileInput(
    ...
    buildMode: BuildMode = BuildMode.STANDALONE,  // NEW
)

enum BuildMode {
    STANDALONE,  // Current behavior — produces installable APK
    PLUGIN,      // Shadow plugin — produces loadable APK
}
```

When `buildMode == PLUGIN`:
1. Use `shadow-androidx-classes.jar` instead of `androidx-classes.jar`
2. Add `shadow-runtime.jar` to classpath
3. Generate `PluginManifest.java` before COMPILE stage
4. Template uses `ShadowApplication` instead of `Application`

The generated APK is structurally identical to a normal APK — Shadow loads it directly.

---

## 10. Risk Assessment

### HIGH risk

| Risk | Impact | Mitigation |
|------|--------|------------|
| Shadow AndroidX transform produces incompatible classes | Plugin crashes at runtime | Extensive testing with all Material components; pin Shadow + AndroidX versions |
| ShadowActivity missing API methods | NoSuchMethodError in generated code | Audit ShadowActivity API surface vs AppCompatActivity; restrict agent to safe APIs |
| Memory pressure from multiple processes | OOM kills | Limit to 4 process slots; monitor memory |

### MEDIUM risk

| Risk | Impact | Mitigation |
|------|--------|------------|
| Shadow project maintenance | Framework becomes unmaintained | Shadow is actively maintained (last commit March 2026); worst case we fork |
| Pre-transformed AndroidX version skew | Classes don't match what the agent assumes | Automate shadow-transform as Gradle task; run on every AndroidX version bump |
| Plugin APK size increase | Longer build times, more storage | Minimal — shadow-runtime is ~80KB |

### LOW risk

| Risk | Impact | Mitigation |
|------|--------|------------|
| Resource partition conflict | Resources load incorrectly | Each plugin in separate process; no conflict possible |
| Process slot exhaustion | Can't launch more plugins | Show user "max 4 running apps" limit; offer to stop one |

---

## 11. Implementation Plan (Phased)

### Phase 0: Proof of Concept (validate core assumption)

1. Manually create a simple "Hello World" plugin APK:
   - Hand-write `MainActivity extends ShadowActivity` (no AndroidX)
   - Hand-write `PluginManifest.java`
   - Compile + DEX + package on device using current pipeline with `shadow-runtime.jar` on classpath
   - Load it in VibeApp via Shadow host
2. **Success criteria:** Plugin Activity launches and renders in VibeApp process
3. **Estimated effort:** 2-3 days

### Phase 1: Pre-transform AndroidX

1. Add Shadow transform as Gradle task in VibeApp build
2. Transform `androidx-classes.jar` → `shadow-androidx-classes.jar`
3. Bundle both versions in VibeApp assets
4. Test: plugin that extends `AppCompatActivity` with Material Components
5. **Success criteria:** Full Material UI plugin runs correctly
6. **Estimated effort:** 3-5 days

### Phase 2: Build Pipeline Dual-Mode

1. Add `BuildMode` to `CompileInput`
2. Implement classpath switching in `JavacCompiler` and `D8DexConverter`
3. Add `PluginManifest` generation step
4. Add plugin template (similar to current EmptyActivity but with ShadowApplication)
5. **Success criteria:** On-device pipeline produces working plugin APK
6. **Estimated effort:** 3-5 days

### Phase 3: Host Integration

1. Integrate Shadow `dynamic.host` library into VibeApp
2. Declare proxy components in manifest (4 process slots)
3. Build Runtime + Loader APKs
4. Implement `VibePluginManager` (load, launch, stop plugins)
5. UI: "Run in app" button alongside "Install APK"
6. **Success criteria:** End-to-end flow — generate → build → run inside VibeApp
7. **Estimated effort:** 5-7 days

### Phase 4: Multi-Plugin & Process Management

1. Process slot allocation/deallocation
2. Plugin lifecycle management (start, stop, restart)
3. Running plugins list UI
4. Memory monitoring and OOM prevention
5. **Estimated effort:** 3-5 days

---

## 12. Verdict

### Is the "generate final code" approach feasible?

**Yes, with a critical prerequisite: pre-transformed AndroidX.**

The approach of generating Shadow-compatible source code directly is sound for **user-written code** (Activity, Application, Service). The AI agent only needs to change the Application base class — Activity code stays the same because the pre-transformed AndroidX chain handles the `Activity → ShadowActivity` rewrite.

Without pre-transformed AndroidX, the approach is only feasible for trivially simple apps (no Material Components, no AppCompat, no RecyclerView) — which defeats VibeApp's value proposition.

### Recommended path

**Option A (Pre-transform AndroidX)** is the only viable path that preserves VibeApp's full UI generation capability. The one-time build-time cost is well justified.

### Key advantage over alternative plugin frameworks

Shadow is chosen because:
1. **Zero reflection, zero hidden APIs** — survives Google's API restrictions
2. **Fully dynamic** — framework itself can be updated without VibeApp update
3. **Battle-tested** — hundreds of millions of users in Tencent apps
4. **Active maintenance** — commits as recent as March 2026
5. **Process isolation** — native support for running plugins in separate processes
6. **Minimal host footprint** — ~15KB static integration

### Alternatives considered and rejected

| Framework | Reason for rejection |
|-----------|---------------------|
| VirtualApp/VirtualXposed | Uses reflection + hidden APIs; blocked by Android restrictions |
| RePlugin | Uses hidden APIs; not maintained since 2020 |
| DroidPlugin | Uses reflection; incompatible with modern Android |
| Custom ClassLoader approach | Would need to reimplement Shadow's proxy mechanism from scratch |
| Android Instant Apps | Requires Google Play; not suitable for on-device generation |
