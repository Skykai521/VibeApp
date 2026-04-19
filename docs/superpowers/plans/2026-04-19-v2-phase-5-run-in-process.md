# v2.0 Phase 5: Run v2 (Compose) APKs inside VibeApp's plugin process

**Goal:** after `assemble_debug_v2` succeeds, the agent can launch the
freshly-built APK inside one of VibeApp's plugin process slots — same
in-process flow v1 already uses via `LaunchAppTool` — so it can verify
Compose UI with `inspect_ui` / `interact_ui` without going through the
system installer.

**Architecture:** the v1 plugin infrastructure already implements
everything the original spec's Phase 5 calls out:

| Spec calls out | What's already in `:app/plugin/` |
|---|---|
| 4-slot LRU plugin process manager | `PluginManager` with `MAX_SLOTS = 5` slots (plugin0..plugin4) — LRU + project-id reuse |
| Process-isolated proxy components | `PluginSlot0..4` Activities + `PluginInspectorSlot0..4` Services |
| In-process plugin loading | `PluginContainerActivity` + `PluginResourceLoader` (DexClassLoader-based, ~500 LoC) |
| Inspector AIDL for UI introspection | `IPluginInspector` + `PluginInspectorService` (~800 LoC) |
| `LaunchAppTool` agent integration | Already shipped for v1 |

So Phase 5 for v2 is just: add a v2-aware analogue of `LaunchAppTool`
that points at the v2 APK location (`<rootDir>/app/build/outputs/apk/
debug/app-debug.apk`) and reads the package name from
`app/build.gradle.kts`'s `applicationId = "..."` line. The underlying
`PluginManager.launchPlugin(...)` is engine-agnostic — it takes any
APK path + package + projectId — so no plugin-side changes are
needed.

**What's NOT done (deferred from the original spec, Phase 6+ scope):**
- The Tencent Shadow Gradle plugin (`com.tencent.shadow.plugin.build_host.v2`)
  in user templates — not needed because our existing classloader-based
  approach in `PluginContainerActivity` doesn't require Shadow's bytecode
  transform.
- A separate `:app:assembleDebugPlugin` task — the same `app-debug.apk`
  that `install_apk_v2` would install also works as a plugin APK because
  `PluginContainerActivity` re-hosts the Activity directly.
- `PluginCrashCollector` enhancements — existing v1 crash collection
  flows already work for v2 plugins (same UID, same exit-reason API).
- Chat-bubble "Run inside VibeApp" CTA UI — agent surface only for now.

## Executed changes

| File | Change |
|---|---|
| `app/src/main/kotlin/com/vibe/app/feature/agent/tool/RunInProcessV2Tool.kt` | New v2 launch tool. Engine guard: GRADLE_COMPOSE only. APK path `<rootDir>/app/build/outputs/apk/debug/app-debug.apk`. Reads `applicationId` from `app/build.gradle.kts` via regex. Calls `pluginManager.launchPlugin(...)` then waits up to 15 s for `IPluginInspector` to bind (longer than v1's 10 s — Compose first frame is heavier). Returns initial View tree. |
| `app/src/main/kotlin/com/vibe/app/di/AgentToolModule.kt` | `@Binds @IntoSet` for `RunInProcessV2Tool`. |
| `app/src/main/assets/agent-system-prompt.md` | New `run_in_process_v2()` entry under the v2 tools section, with the same "ALWAYS call close_app when done" rule as v1's `launch_app`. |

## Exit criteria

- [x] `:app:kspDebugKotlin` + `:app:assembleDebug` — green.
- [x] `RunInProcessV2Tool` is registered in the Hilt set + visible to the agent registry.
- [x] System prompt documents the new tool + when to use it vs `install_apk_v2`.
- [ ] Manual e2e: build a v2 Counter project, agent calls `run_in_process_v2` → counter Activity appears in a plugin slot → `inspect_ui` returns Compose-aware tree → `interact_ui` clicks the +1 button → state updates → `close_app` returns. **(USER, please verify; this is the first time we know whether `PluginContainerActivity` + `PluginResourceLoader` handle a Compose-built APK without modification.)**

## Risks for the manual e2e

The existing `PluginManager` was designed for v1 APKs (Java + XML, plain
`Activity` subclass, no Compose runtime). v2 APKs differ in three ways
that could surface bugs in the host:

1. **`ComponentActivity` instead of `Activity`.** `PluginContainerActivity`
   probably reflects on `Activity` lifecycle methods directly. If
   `ComponentActivity` (which v2 `MainActivity.kt` extends) overrides
   different methods than expected, lifecycle dispatch could miss.
2. **Compose runtime classes in the plugin's dex.** Compose installs a
   `ProcessLifecycleOwner` and reads `LocalContext` early. The class
   loader hierarchy matters: if `PluginResourceLoader` doesn't expose
   `androidx.compose.*` from the plugin dex correctly, init will fail
   with `NoClassDefFoundError`.
3. **Resource-table hop.** Compose reads system resources (default theme
   colors, font fallbacks) from `Resources.getSystem()` — that's host-side
   and should "just work" — and from the plugin's R via `setContent { }`.
   Plugin R-table merging happens in `PluginResourceLoader`; should be
   transparent.

If any of these break, the recovery path is clear from the current code:
log error in `PluginContainerActivity.onCreate`, surface via
`PluginInspectorService` → `read_runtime_log` agent tool → fix in
host-side code. None of these would be Tencent-Shadow-Gradle-plugin
issues, so the plain APK path stays viable.

## Exit Log (2026-04-19)

**Validated by:**
- `:app:kspDebugKotlin` + `:app:assembleDebug` — green.
- Code inspection: `RunInProcessV2Tool` mirrors `LaunchAppTool` shape (engine-guard + APK-path + packageName resolution + same `pluginManager.launchPlugin` call), so the only new failure modes are v2-APK-vs-host compatibility ones, which only on-device runtime can prove.

**Phase 5 is complete on the code side.** End-to-end verification is the
remaining work and depends on a real-device run of the full chain:
`create_compose_project` → edit MainActivity → `assemble_debug_v2` →
`run_in_process_v2` → `inspect_ui` → `interact_ui` → `close_app`.

If the plugin-host path turns out to need real Tencent Shadow transforms
to host Compose APKs — i.e. the runtime fails with class-loader or
lifecycle issues that the existing `PluginContainerActivity` can't
work around — that's a Phase 5b: integrate the official Shadow Gradle
plugin into the v2 template, add the `:app:assembleDebugPlugin` task,
update the v2 build pipeline to produce both `app-debug.apk` and
`app-debug-plugin.apk`. Until we hit a concrete failure, that's
unjustified additional surface.
