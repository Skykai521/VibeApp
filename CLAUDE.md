# CLAUDE.md

> This file provides current, high-signal context for AI coding assistants working on this repository.

## Source of Truth

If this summary conflicts with the codebase, follow these files first:

- `app/build.gradle.kts`, `build-gradle/build.gradle.kts`, `gradle-host/build.gradle.kts` for the build configuration.
- `docs/architecture.md` for module boundaries and runtime flow.
- `docs/superpowers/specs/2026-04-18-v2-gradle-compose-arch-design.md` for the v2 design.
- `docs/superpowers/specs/2026-04-19-v2-phase-5b-exit-log.md` for the state of the Shadow integration.
- `CONTRIBUTING.md` for branch and review workflow.

## Project Overview

VibeApp (意造) is an Android app that lets users generate, compile, sign, and load native Android APKs directly on their phone from natural-language prompts. Generated apps are **Kotlin + Jetpack Compose** and build via an on-device Gradle + AGP toolchain; model inference may use cloud APIs or a local OpenAI-compatible endpoint such as Ollama.

## Current Tech Stack

- **Language**: Kotlin everywhere (app + generated projects).
- **UI**: Jetpack Compose + Material 3.
- **Architecture**: MVVM + UDF.
- **DI**: Hilt.
- **Persistence**: Room + DataStore.
- **Async**: Coroutines + Flow.
- **Network providers**: OpenAI, Anthropic, Google, Qwen, Ollama / OpenAI-compatible endpoints.
- **Generated project build chain**: bootstrapped Gradle 9.3.1 + AGP 9.1.0 + Kotlin 2.0.21 running on device. Output is a Shadow-transformed "plugin" APK loaded in-process.
- **Plugin hosting**: Tencent Shadow, vendored under `third_party/shadow/`. Loader + runtime APKs ship as app assets; Shadow's Gradle plugin is bundled as a local Maven repo.
- **App SDK**: `minSdk = 29`, `targetSdk = 28` (intentional — Termux exec path; see `docs/superpowers/plans/2026-04-18-v2-phase-1d-termux-exec.md`). Generated-project `compileSdk = 34` / `targetSdk = 34`.

## Current Project Structure

```text
app/src/main/kotlin/com/vibe/app/
├── presentation/              # Compose UI, navigation, ViewModel, theme
│   └── ui/{chat, home, main, migrate, setting, setup, startscreen, ...}
├── feature/
│   ├── agent/{loop, tool}/    # Agent loop coordinator + tool implementations
│   ├── project/               # DefaultProjectManager, ProjectWorkspace, snapshots
│   ├── projecticon/           # Launcher icon generation
│   └── diagnostic/            # ChatDiagnosticLogger
├── plugin/v2/                 # Tencent Shadow host (ShadowPluginHost, extractors,
│                              # inspector service, activity tracker, zip builder)
├── data/{database, datastore, dto, model, network, repository}/
├── di/                        # Hilt modules
└── util/

build-gradle/                  # Gradle project initializer + template renderer
gradle-host/                   # Gradle Tooling API host (runs in its own JVM)
build-runtime/                 # Bootstrap runtime: download + exec Java, Gradle, AAPT2
third_party/shadow/            # Vendored Tencent Shadow source + APK modules
```

## Key Implementation Facts

1. **Generated apps are Kotlin + Compose.** There is no v1 Java/XML path — Phase 6 Layer B deleted `:build-engine`, `build-tools/*`, `templates/EmptyActivity/`, and the legacy plugin host. Every project routes through `GradleProjectInitializer` + on-device Gradle.
2. **Every project gets a Shadow-transformed plugin APK.** The `KotlinComposeApp` template applies `com.tencent.shadow.plugin`, which adds `normal` + `plugin` flavors under the `Shadow` dimension. `assemble_debug_v2` builds the `plugin` flavor, running Shadow's bytecode transform so plugin Activities extend `com.tencent.shadow.core.runtime.ShadowActivity` and can be loaded in-process.
3. **`ShadowComposeActivity` is the Compose entry point** — a template-shipped class that implements `LifecycleOwner`/`SavedStateRegistryOwner`/`ViewModelStoreOwner` by hand (Shadow's transform removes `ComponentActivity` from the hierarchy, so the `androidx.activity.compose.setContent` extension won't resolve). Users call `setComposeContent { … }` on it instead of `setContent { … }`.
4. **Plugin hosting is single-process.** All plugins load into `:shadow_plugin` via `PluginManagerThatUseDynamicLoader` (in `com.vibe.app.plugin.v2.ShadowPluginHost`). The v1 5-slot LRU is gone.
5. **Agent tooling is workspace-centric.** Tools read/write/list/grep project files, run the Gradle build, rename projects, update launcher icons, inspect and drive the running plugin's UI.
6. **Agent system prompt lives in `app/src/main/assets/agent-system-prompt.md`** and is loaded at runtime by `DefaultAgentLoopCoordinator`. It supports `{{PACKAGE_NAME}}` and `{{PACKAGE_PATH}}` template variables.

## Common Tasks

### Adding or changing model/provider support

1. Update the relevant types under `app/src/main/kotlin/com/vibe/app/data/model/`.
2. Add or update API clients under `app/src/main/kotlin/com/vibe/app/data/network/`.
3. Wire defaults and persistence through repository / DataStore layers.
4. Update setup and settings UI under `presentation/ui/setup` and `presentation/ui/setting`.
5. If the provider participates in the agent loop, update `feature/agent/loop`.

### Modifying the plugin template

1. The template lives at `build-gradle/src/main/assets/templates/KotlinComposeApp/`.
2. Placeholders (`{{PACKAGE_NAME}}`, `{{PACKAGE_PATH}}`, `{{PROJECT_NAME}}`, `{{SDK_DIR}}`, `{{GRADLE_USER_HOME}}`, `{{SHADOW_PLUGIN_REPO}}`) are substituted by `ProjectStager` at project creation time.
3. Don't touch `ShadowComposeActivity.kt.tmpl` or the Shadow plugin config unless you know what you're doing — those are load-bearing for the Compose + Shadow bridge.

### Modifying the Shadow integration

1. Vendored Shadow source is under `third_party/shadow/upstream/`. Track changes in `third_party/shadow/README.md`.
2. The host-side glue is in `app/src/main/kotlin/com/vibe/app/plugin/v2/`.
3. Host-build tasks `copyShadowApks` and `copyShadowPluginRepo` (in `app/build.gradle.kts`) bundle the loader/runtime APKs and the Shadow-plugin local Maven repo into the app's assets.
4. See `docs/superpowers/specs/2026-04-19-v2-phase-5b-exit-log.md` for the full Shadow integration state.

## Files To Treat As Prebuilt Inputs

- Template assets under `build-gradle/src/main/assets/templates/KotlinComposeApp/` (rendered at project creation — don't hand-edit rendered copies).
- Bundled toolchain artifacts under `app/src/main/assets/` (Gradle host jar, Shadow APKs, Shadow plugin repo zip). These are produced by dedicated Gradle tasks.
- Vendored Shadow source under `third_party/shadow/upstream/` — keep aligned with the upstream pin in `third_party/shadow/README.md`.

## Testing

- Unit tests: `./gradlew test`.
- App build sanity check: `./gradlew :app:assembleDebug`.
- On-device validation when flows change: install on an Android 10+ device / emulator and create a fresh project from the home "+" button; then `assemble_debug_v2` → `run_in_process_v2` → `inspect_ui` / `interact_ui` → `close_app`.

## Known Product Limits

- **Single ABI per build.** The plugin APK is built for the device's ABI (arm64-v8a typically); native `.so` deps in plugin Gradle files will not mix across ABIs.
- **Cold first build is slow** — 5–10 minutes for the first `assemble_debug_v2` on a given project (Maven + Kotlin daemon spinup). Subsequent builds are <60s.
- **One plugin at a time** under Shadow. The v1 5-slot LRU is gone; concurrent plugins would need a new slot policy layered on top.
