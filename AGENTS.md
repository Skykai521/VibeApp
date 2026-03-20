# AGENTS.md

> This file provides current, high-signal context for AI coding assistants working on this repository.

## Source of Truth

If this summary conflicts with the codebase, follow these files first:

- `app/build.gradle.kts` and `build-engine/build.gradle.kts` for SDK / Java config
- `docs/architecture.md` for module boundaries and runtime flow
- `docs/build-engine.md` and `docs/build-chain.md` for the real on-device build pipeline
- `CONTRIBUTING.md` for branch and review workflow

## Project Overview

VibeApp (意造) is an Android app that lets users generate, compile, sign, and install native Android APKs directly on their phone from natural-language prompts. The build runs on-device inside the app workspace; model inference may use cloud APIs or a local OpenAI-compatible endpoint such as Ollama.

## Current Tech Stack

- **Language**: Kotlin for the app, Java + XML for generated projects
- **UI**: Jetpack Compose + Material 3
- **Architecture**: MVVM + UDF
- **DI**: Hilt
- **Persistence**: Room + DataStore
- **Async**: Coroutines + Flow
- **Network providers**: OpenAI, Anthropic, Google, Qwen, Ollama/OpenAI-compatible endpoints
- **Build chain**: AAPT2 + `JavacCompiler`/`JavacTool` + D8 + `AndroidApkBuilder` + `DebugApkSigner`
- **App SDK**: `minSdk = 29`, `targetSdk = 36`, `compileSdk = 36`
- **Build-engine defaults**: `CompileInput` now defaults to `minSdk = 29`, `targetSdk = 36`
- **Java levels**: app/build-engine code targets Java 11; generated template apps stay on a conservative Java 8 source level

## Current Project Structure

```text
app/src/main/kotlin/com/vibe/app/
├── presentation/   # Compose UI, navigation, ViewModel, theme
│   ├── common/
│   ├── icons/
│   ├── theme/
│   └── ui/
│       ├── chat/
│       ├── home/
│       ├── main/
│       ├── migrate/
│       ├── setting/
│       ├── setup/
│       └── startscreen/
├── feature/        # Agent loop, project workspace/init, icon generation
│   ├── agent/
│   │   ├── loop/
│   │   └── tool/
│   ├── project/
│   ├── projecticon/
│   └── projectinit/
├── data/           # Room, DataStore, DTO, repository, network clients
│   ├── database/
│   ├── datastore/
│   ├── dto/
│   ├── model/
│   ├── network/
│   └── repository/
├── di/
└── util/

build-engine/src/main/java/com/vibe/build/engine/
├── apk/
├── compiler/
├── dex/
├── internal/
├── model/
├── pipeline/
├── resource/
└── sign/
```

## Key Implementation Facts

1. **Generated apps are Java + XML, not Kotlin + Compose.** The current production path is optimized for Java/XML generation and on-device compilation success.
2. **The real compiler path is Javac-based.** `EcjCompiler` still exists only as a deprecated compatibility wrapper; do not describe ECJ as the primary compiler.
3. **AAPT2 runs before Java compilation.** The pipeline is `RESOURCE -> COMPILE -> DEX -> PACKAGE -> SIGN`.
4. **Project workspaces live under app-private storage.** The typical runtime workspace is `files/projects/{projectId}/app`.
5. **Agent tooling is workspace-centric.** Tools read/write/list project files, run the build pipeline, rename projects, and update launcher icons.
6. **Provider support is not uniform.** Platform settings support multiple providers, but agent-loop gateway behavior is implemented in `feature/agent/loop` and should be checked before assuming identical tool-calling capability across providers.

## Common Tasks

### Adding or changing model/provider support

1. Update the relevant types under `app/src/main/kotlin/com/vibe/app/data/model/`.
2. Add or update API clients in `app/src/main/kotlin/com/vibe/app/data/network/`.
3. Wire defaults and persistence through repository / DataStore layers.
4. Update setup and settings UI under `presentation/ui/setup` and `presentation/ui/setting`.
5. If the provider participates in the agent loop, update `feature/agent/loop`.

### Modifying the build pipeline

1. Treat `build-engine` as the source of truth for compile/package/sign behavior.
2. Keep `docs/build-engine.md` and `docs/build-chain.md` aligned with any pipeline changes.
3. Check template defaults in `ProjectInitializer` when SDK or generated project assumptions change.

## Files To Treat As Prebuilt Inputs

- `build-tools/**/libs/*.jar`
- `build-engine/src/main/assets/*.zip`
- Template assets under `app/src/main/assets/templates/`

Do not rewrite or replace these casually unless the task is explicitly about updating bundled toolchain artifacts or template assets.

## Testing

- Unit tests: `./gradlew test`
- Build engine tests: `./gradlew :build-engine:test`
- App build sanity check: `./gradlew assembleDebug`
- UI/device validation when flows change: manual verification on an Android 10+ device or emulator

## Known Product Limits

- Generated apps are still Java/XML-first
- No general third-party dependency resolution pipeline yet
- Single-project workspace/build flow is the primary path
- Prompts and templates intentionally stay conservative to maximize device-side build success
