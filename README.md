# VibeApp

> Describe it. Build it. Install it. All on your phone.

[![License](https://img.shields.io/badge/license-GPL%203.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%2010.0%2B-green.svg)](https://android.com)
[![Min SDK](https://img.shields.io/badge/minSdk-29-brightgreen.svg)]()
[![Version](https://img.shields.io/badge/version-2.0.0-blue.svg)]()
[![Status](https://img.shields.io/badge/status-In%20Development-orange.svg)]()

[**中文文档**](README_CN.md)

<p align="center">
  <img src="docs/assets/banner.png" alt="VibeApp Banner" width="1650"/>
</p>

---

## What is VibeApp

VibeApp is a **fully open-source** Android app that lets anyone **generate, compile, and install** a real native Android app directly on their phone using natural language — no PC, no coding skills, no cloud compiler required.

The entire build pipeline runs on-device. Your code never leaves your phone.

**v2.0 highlight:** generated apps are **Kotlin + Jetpack Compose**, built on-device by a real **Gradle 9.3.1 + AGP 9.1.0 + Kotlin 2.0.21** toolchain that ships bundled inside the APK. No runtime downloads.

### Screenshots

| Home | Chat | Add Platform | Settings |
|:----:|:----:|:------------:|:--------:|
| <img src="docs/assets/screenshot_home.jpg" alt="Home" width="200"/> | <img src="docs/assets/screenshot_chat_screen.jpg" alt="Chat" width="200"/> | <img src="docs/assets/screenshot_add_platform.jpg" alt="Add Platform" width="200"/> | <img src="docs/assets/screenshot_setting.jpg" alt="Settings" width="200"/> |

## Why VibeApp

There are many AI app builders out there, but they share a common problem: **the output isn't a real app**.

|      | Other AI Builders     | VibeApp             |
|------|-----------------------|---------------------|
| Output  | Web App / PWA / Cloud-compiled | **Native Android APK (Kotlin + Compose)** |
| Compilation | Cloud-based                | **On-device local build (Gradle + AGP)** |
| Privacy | Code uploaded to servers          | **Code never leaves your phone**       |
| Source Export | Mostly unsupported             | **One-tap source code export**          |
| Barrier | Requires deployment/environment setup         | **Just configure an AI API key** |

We believe that in the AI era, apps won't disappear — instead, more people will **become app creators for the first time**.

---

## Features

### Core Capabilities

- **Conversational Creation** — Describe what you want in natural language, iterate through multi-turn dialogue
- **On-Device Gradle + AGP** — Full Android build stack (Gradle 9.3.1, AGP 9.1.0, Kotlin 2.0.21) runs inside your phone, no cloud compile
- **Bundled Toolchain** — JDK 17, Gradle, Android SDK 36, AAPT2 all ship inside the APK; zero network setup on first build
- **Run-in-Process** — Launch generated apps inside VibeApp via the Tencent Shadow plugin framework — no system installer, no reboot
- **Standalone Install** — One-tap build of a non-Shadow flavor APK + system installer handoff; share the app like any other APK
- **Automatic Error Fix Loop** — Compilation failures are sanitized, fed back to the AI, and retried
- **Multi-Project Management** — Manage multiple app projects simultaneously with auto snapshots and per-project workspaces
- **Multi-Model Support** — Claude, GPT, Gemini, Qwen, Kimi, MiniMax, Groq, OpenRouter, and OpenAI-compatible local Ollama
- **Flexible Export** — Run in-process, install as a standalone APK, or export the full source tree

### Code Generation Strategy

Stable AI code generation is the product's core. VibeApp combines three layers:

1. **Kotlin + Compose Template** — AI starts from a working `KotlinComposeApp` template with a pre-wired Shadow-compatible `ShadowComposeActivity`; structural errors are minimized
2. **Strict System Prompt** — Compose-only conventions, explicit don'ts around `ComponentActivity` / `setContent` / native libs
3. **Auto-Fix Loop** — On build failure, AGP's structured diagnostics are fed to the AI for repair; covers most common Compose/Kotlin errors

---

## Architecture

```
+--------------------------------------------------------------+
| Presentation Layer                                           |
| Compose Screens + ViewModels + Navigation                    |
| chat / home / setup / settings / start / diagnostic          |
+--------------------------------------------------------------+
| Feature Layer                                                |
| Agent Loop Coordinator (gateway-agnostic tool dispatch)      |
| Project Manager + Workspace + Snapshot                       |
| Agent Tools — v2 build tools, file ops, icon, UI inspect     |
+--------------------------------------------------------------+
| Data Layer                                                   |
| Room + DataStore + Repository + Network API clients          |
| OpenAI / Anthropic / Google / Qwen / Kimi / MiniMax / Groq   |
+--------------------------------------------------------------+
| On-Device Build Stack                                        |
|   build-gradle : GradleProjectInitializer, template renderer |
|                  GradleBuildService (Tooling API client)     |
|   gradle-host  : isolated JVM running Gradle Tooling API     |
|   build-runtime: bundled JDK + Gradle + AndroidSDK extractor |
+--------------------------------------------------------------+
| Plugin Runtime (Tencent Shadow — vendored)                   |
| ShadowPluginHost -> :shadow_plugin process                   |
| VibeAppPluginLoader + VibeAppComponentManager                |
| PluginContainerActivity + Inspector Service                  |
+--------------------------------------------------------------+
| Device Filesystem                                            |
| /files/projects/{projectId}/         — Gradle multi-module   |
| /files/usr/opt/{jdk,gradle,android-sdk}/ — bundled toolchain |
| /files/shadow/{loader.apk,runtime.apk,plugin-repo}/          |
+--------------------------------------------------------------+
```

Main pipeline:

```
ChatScreen
  -> ChatViewModel
  -> AgentLoopCoordinator -> Agent Tool (e.g. assemble_debug_v2)
  -> GradleBuildService -> gradle-host JVM -> Gradle Tooling API
  -> :app:assemblePluginDebug -> app-plugin-debug.apk
  -> ShadowPluginHost.launchPlugin -> :shadow_plugin
```

> For detailed layer descriptions, module responsibilities, and key sequences, see [docs/architecture.md](docs/architecture.md)

---

## How the Build Chain Works

```
User describes what they want
     |
AI edits files inside the generated Kotlin + Compose project
  (create_compose_project / read_project_file / write_project_file /
   edit_project_file, all scoped to the per-chat project workspace)
     |
assemble_debug_v2  ->  :app:assemblePluginDebug
  (on-device Gradle runs AGP 9 against the bundled Android SDK)
  |-- Failure -> structured diagnostics -> AI fix -> retry
  +-- Success |
app-plugin-debug.apk   (Shadow-transformed, runs ONLY inside VibeApp)
     |
Two output paths:
  [A] run_in_process_v2    -> Shadow loads the APK into :shadow_plugin
                              (fast iteration, no install required)
  [B] install_apk_v2       -> runs :app:assembleNormalDebug too
                              -> app-normal-debug.apk
                              -> system installer handoff
                              (standalone app on the launcher)
```

### Build Chain Tech Stack

| Component | Purpose | Notes |
|-----------|---------|-------|
| **Gradle 9.3.1** | Build orchestrator | Runs on-device via Gradle Tooling API, driven from `:gradle-host` |
| **AGP 9.1.0** | Android Gradle Plugin | Full AAPT2 / Kotlin compile / D8 / packaging / signing pipeline |
| **Kotlin 2.0.21** | Source language + Compose compiler | Kotlin daemon runs in the on-device Gradle process |
| **Tencent Shadow 2.x** | In-process plugin runtime | Bytecode transforms plugin APK so Activities extend `ShadowActivity` |
| **Bundled JDK 17** | JVM for Gradle | Shipped arm64-v8a; extracted to `filesDir` on first build |
| **Bundled Android SDK 36** | `android.jar`, `build-tools/aapt2` | Shipped arm64-v8a; extracted alongside the JDK |

---

## Quick Start

### Requirements

- Android 10.0 (API 29) or above, **arm64-v8a**
- An AI API Key (Claude / GPT-4o / Gemini / Qwen / Kimi / MiniMax — pick one) or a local Ollama service
- Roughly 2 GB of free space (bundled toolchain + per-project Gradle caches)

### Install

[Download the latest APK from the Releases page](https://github.com/Skykai521/VibeApp/releases)

### Build from Source

```bash
git clone https://github.com/Skykai521/VibeApp.git
cd VibeApp
# Generate the bundled toolchain tarballs (one-time, requires a host-side JDK + AGP):
#   scripts/bootstrap/build-jdk.sh        --abi arm64-v8a
#   scripts/bootstrap/build-gradle.sh
#   scripts/bootstrap/build-androidsdk.sh --abi arm64-v8a
#   scripts/bootstrap/build-manifest.sh
# Then build the app APK:
./gradlew assembleDebug
```

### First Use

1. Open VibeApp → Settings → Configure your AI API Key
2. Tap "+" on the Home screen → a Kotlin + Compose project is laid down automatically
3. Describe the app you want in natural language
4. The agent edits files, runs `assemble_debug_v2`, and launches via `run_in_process_v2`
5. Use the top-bar **Run** button to re-launch without a turn, or the **Install APK** menu item to sideload the standalone build

---

## Project Structure

```
VibeApp/
+-- app/                                   # Android host app
|   +-- src/main/kotlin/com/vibe/app/
|   |   +-- presentation/                  # Compose UI, navigation, ViewModels, theme
|   |   |   +-- ui/{chat, home, main, setup, setting, startscreen, diagnostic, ...}
|   |   +-- feature/
|   |   |   +-- agent/{loop, tool, service}  # Agent loop + tool registry
|   |   |   +-- project/                   # ProjectManager, Workspace, snapshots
|   |   |   +-- projecticon/               # Launcher icon generation (Lucide)
|   |   |   +-- diagnostic/                # ChatDiagnosticLogger
|   |   +-- plugin/v2/                     # Tencent Shadow host integration
|   |   |   +-- ShadowPluginHost           # Top-level launch orchestrator
|   |   |   +-- ShadowPluginManager        # Concrete manager subclass
|   |   |   +-- ShadowPluginProcessService # :shadow_plugin process entry
|   |   |   +-- ShadowPluginInspectorService, ShadowActivityTracker, ...
|   |   +-- data/                          # Room, DataStore, network, repositories
|   |   +-- di/                            # Hilt modules
|   |   +-- util/
|   +-- src/main/res/                      # UI resources + multi-language strings
|   +-- src/main/assets/
|       +-- bootstrap/                     # Bundled toolchain tarballs
|       |   +-- jdk-17.0.13-arm64-v8a.tar.gz
|       |   +-- gradle-9.3.1-common.tar.gz
|       |   +-- android-sdk-36.0.0-arm64-v8a.tar.gz
|       |   +-- manifest.json
|       +-- shadow/
|       |   +-- loader.apk                 # Loaded into :shadow_plugin at runtime
|       |   +-- runtime.apk
|       |   +-- plugin-repo.zip            # Shadow Gradle plugin local Maven repo
|       +-- agent-system-prompt.md         # v2 agent prompt (Kotlin + Compose + Shadow)
|       +-- vibeapp-gradle-host.jar        # gradle-host module, copied in
+-- build-gradle/                          # Gradle orchestration from the host app
|   +-- GradleProjectInitializer           # Renders template -> workspace
|   +-- GradleBuildService                 # Tooling API client wrapper
|   +-- ApkInstaller + StandaloneApkBuilder
|   +-- src/main/assets/templates/KotlinComposeApp/   # Compose project template
+-- build-runtime/                         # On-device toolchain bootstrap
|   +-- BootstrapFileSystem, RuntimeBootstrapper, ZstdExtractor
|   +-- (extracts bootstrap/* tarballs into filesDir/usr/opt/)
+-- gradle-host/                           # Isolated JVM running Gradle Tooling API
+-- third_party/shadow/                    # Vendored Tencent Shadow
|   +-- upstream/                          # Shadow SDK source (core/ + dynamic/)
|   +-- loader-apk/                        # Our CoreLoaderFactoryImpl + VibeApp glue
|   +-- runtime-apk/                       # Shadow runtime APK wrapper
+-- scripts/bootstrap/                     # Build the bundled toolchain tarballs
+-- docs/                                  # Architecture + plans + specs
+-- .github/                               # Issue templates / CI
+-- CONTRIBUTING.md
+-- LICENSE
+-- README.md / README_CN.md
```

> The v1 Java/XML build stack (`build-engine/`, `build-tools/*`, `shadow-runtime/` stub, Material Components XML pattern library) was removed in the 2.0 cleanup. Only Kotlin + Compose remains.

---

## Roadmap

### Phase 1 — MVP: End-to-End Pipeline

> Goal: User types a sentence -> gets an installable APK

- [x] Integrate Claude / OpenAI / Qwen APIs for code generation
- [x] Integrate build modules (JavacTool + D8 + AAPT2)
- [x] Single Activity + View-based app generation
- [x] Automatic error fix loop
- [x] APK signing + PackageInstaller guided installation
- [x] App icon generation support
- [x] Basic UI: chat interface + build progress

### Phase 2 — Experience Refinement

> Goal: Make the generation process visible, controllable, and iterable

- [x] Multi-project management
- [x] Multi-model switching (Claude / GPT / Gemini / Qwen / Kimi / MiniMax / Groq / OpenRouter / Ollama)
- [x] Plugin system — run generated apps inside VibeApp without installation
- [x] Build cache — pre-dex caching for library JARs, significantly faster subsequent builds
- [x] AI multimodal support — image input across Anthropic, OpenAI, and Kimi providers
- [x] Context compaction — multi-strategy conversation compaction to support longer multi-turn sessions
- [x] Diagnostic logging — structured event tracking for the agent loop, viewable in-app

### Phase 3 — Quality & Capability

> Goal: Generate higher-quality utility apps and lightweight data tools that anyone can use

- [x] Smarter auto-fix — broader error coverage and higher first-attempt success rate
- [x] Continuous iteration — per-turn auto snapshots with undo, project memo injected into every iterate-mode turn, multi-turn refinement
- [ ] Utility app enhancements — network requests, local storage, scheduled tasks, and other common capabilities
- [ ] Web scraping & data tools — structured data fetching and display
- [ ] Community template marketplace — share and reuse high-quality tool templates

### Phase 4 — v2.0 Kotlin + Compose + On-Device Gradle

> Goal: Replace the hand-rolled Java/XML pipeline with a real Android build stack

- [x] Generated apps are Kotlin + Jetpack Compose (no more Java / XML Views)
- [x] On-device Gradle 9.3.1 + AGP 9.1.0 + Kotlin 2.0.21 via the Tooling API
- [x] Bundled toolchain (JDK 17, Gradle, Android SDK 36, AAPT2) shipped in assets — zero network download on first build
- [x] Tencent Shadow integration — `:shadow_plugin` process, bytecode transform, custom `CoreLoaderFactoryImpl` + `ComponentManager`
- [x] `run_in_process_v2` vs `install_apk_v2` — same source, two flavors (`plugin` for in-process, `normal` for standalone)
- [x] Standalone top-bar Run button + Install APK menu item — run/install without burning an agent turn
- [x] Full v1 deletion — `build-engine`, `build-tools/*`, legacy plugin host, M2 XML UI pattern library

---

## Acknowledgments

VibeApp stands on the shoulders of these excellent open-source projects:

| Project | Contribution |
|---------|-------------|
| [gpt_mobile](https://github.com/Taewan-P/gpt_mobile) | AI Chat UI reference |
| [CodeAssist](https://github.com/tyron12233/CodeAssist/) | On-device full Android IDE, inspired v1's hand-rolled pipeline |
| [Shadow](https://github.com/Tencent/Shadow) | Tencent's plugin framework — powers v2's in-process run path so generated apps boot inside VibeApp without a system install |
| [Android Gradle Plugin](https://developer.android.com/build) | AGP 9.1.0 runs unmodified on-device via the Gradle Tooling API |

---

## Contributing

We welcome contributions of any kind! Please read [CONTRIBUTING.md](CONTRIBUTING.md) for details.

**Ways to contribute:**

- Bug reports and feature suggestions
- Improve AI code generation prompt templates
- Extend supported app types and Compose components
- Improve build chain stability and speed
- Improve documentation and examples

---

## License

This project is licensed under the [GPL-3.0 License](LICENSE).

---

## About the Name

**VibeApp** — the Chinese name is **Yi Zao** (意造).

"Vibe" comes from Vibe Coding — using natural language to drive AI to write code.
"Yi Zao" means "creating with intention" — two characters conveying idea (意) and creation (造).

> The moment an ordinary person feels "I built a real app" — that's the entire reason VibeApp exists.

---

<p align="center">
  Made with love for everyone who ever had an app idea but didn't know how to build it.
</p>
