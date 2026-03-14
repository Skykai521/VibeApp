# AGENTS.md

> This file provides context for AI coding assistants (Codex, Cursor, Copilot, etc.) working on this project.

## Project Overview

VibeApp (意造) is an Android app that lets users generate, compile, and install native Android APKs directly on their phone using natural language. The entire build pipeline runs on-device without any cloud services.

## Tech Stack

- **Language**: Kotlin (app code), Java (generated user code)
- **UI**: Jetpack Compose + Material 3
- **Architecture**: MVVM + UDF (Unidirectional Data Flow)
- **DI**: Hilt
- **Database**: Room
- **Preferences**: DataStore
- **Async**: Kotlin Coroutines + Flow
- **Build Chain**: ECJ (Java compiler) + D8 (DEX) + AAPT2 (resources) + ApkSigner
- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 35

## Project Structure

```
app/src/main/java/.../vibeapp/
├── ui/           # Compose UI screens (chat, preview, project list, settings)
├── feature/      # Business logic (codegen, fileops, dependency, fixloop)
├── build/        # Build pipeline (compiler, dex, resource, sign, pipeline)
├── ai/           # AI provider abstraction (Codex, GPT, DeepSeek, Ollama)
├── project/      # Project management (model, storage, snapshot)
├── data/         # Data layer (Room DB, DataStore)
└── di/           # Hilt modules

build-engine/     # Standalone build engine module
docs/             # Project documentation
```

## Key Design Decisions

1. **AI generates Java (not Kotlin)** — ECJ is a pure-Java compiler that runs on Android. Kotlin compilation requires kotlinc which is harder to embed on-device.

2. **Template-based generation** — AI fills in predefined skeletons rather than generating from scratch. This dramatically improves compilation success rate.

3. **No third-party libraries (Phase 1)** — Generated code can only use standard Android SDK APIs (defined in whitelist.json). This avoids dependency resolution complexity.

4. **AAPT2 as native binary** — Unlike ECJ and D8 (pure Java), AAPT2 is a C++ binary. We ship prebuilt binaries for arm64-v8a, armeabi-v7a, and x86_64.

5. **Error log sanitization** — Before feeding compilation errors to AI for fixing, we strip absolute paths, aggregate duplicate errors, and limit total length to save tokens.

## Coding Conventions

- Kotlin code follows official Kotlin Coding Conventions
- Use `ktlint` for formatting
- Compose functions: PascalCase (e.g., `ChatScreen`, `ProjectListItem`)
- ViewModels: suffix with `ViewModel` (e.g., `ChatViewModel`)
- Repositories: suffix with `Repository` (e.g., `ProjectRepository`)
- Use interfaces for all build pipeline components (for testability)
- Coroutines: use structured concurrency, avoid GlobalScope
- Error handling: use sealed classes for Result types, not exceptions

## Common Tasks

### Adding a new AI provider
1. Implement `AiProvider` interface in `ai/provider/`
2. Add configuration UI in `ui/settings/`
3. Register in `AiProviderFactory`
4. Test with standard prompt templates

### Modifying build pipeline
1. All build components are in `build-engine/` module
2. Each step implements a clean interface
3. Test each step independently before integration

## Files to Never Modify Directly

- `build-engine/libs/*.jar` — Pre-built ECJ and D8 JARs
- `build-engine/src/main/jniLibs/` — Pre-built AAPT2 binaries
- `app/src/main/assets/android.jar` — Android SDK stub

## Testing

- Unit tests: `./gradlew test`
- Build pipeline tests: `./gradlew :build-engine:test`
- UI tests: `./gradlew connectedAndroidTest`
- Prompt quality: Manual testing with 10+ diverse user inputs

## Known Limitations

- No Kotlin/Compose support in generated code (Phase 1)
- No third-party library support (Phase 1)
- Single Activity apps only (Phase 1)
- Java 8 syntax only (no lambdas for max compatibility)
