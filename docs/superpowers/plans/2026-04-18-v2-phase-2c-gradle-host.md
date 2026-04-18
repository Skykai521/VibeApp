# v2.0 Phase 2c: `GradleHost` + Tooling API + JSON IPC Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove the full three-process topology from design doc §4.2 works on device: VibeApp (ART) spawns a `GradleHost` JVM subprocess on the bootstrapped OpenJDK 17, `GradleHost` embeds Gradle Tooling API 8.10.2 and drives a Gradle Daemon to run a `:help` task on an empty project, and the full build event stream arrives back in VibeApp as structured Kotlin data over a JSON-line IPC channel.

**Architecture:** A new pure-JVM Gradle subproject `:gradle-host` produces a shaded fat JAR (`vibeapp-gradle-host.jar`, ~15 MB including Tooling API + kotlinx.serialization). The JAR is bundled into `app/src/main/assets/` and extracted on first use to `filesDir/usr/opt/vibeapp-gradle-host/`. `GradleBuildService` in `:build-gradle` (Kotlin/Android) launches GradleHost via the existing `ProcessLauncher`, writes JSON-line requests to stdin, reads JSON-line events from stdout. Tooling API is not pinned to our synthetic dev manifest's gradle-8.10.2 artifact — it uses its own protocol handshake against whatever Gradle distribution we point it at.

**Tech Stack:** Kotlin 2.3.10 (aligned with app module), pure JVM (no Android in `:gradle-host`), `org.gradle:gradle-tooling-api:8.10.2`, `com.gradleup.shadow:shadow-gradle-plugin:9.1.0` for fat-jar shading, kotlinx-serialization for IPC. Tests: MockK for IPC unit tests, instrumented tests for on-device end-to-end.

---

## Spec References

- Design doc: `docs/superpowers/specs/2026-04-18-v2-gradle-compose-arch-design.md` §4 (entire chapter on `:build-gradle`). Phase 2c implements §4.1–4.3, §4.6 (GradleBuildService API). Deferred: §4.4 (`init.gradle.kts`), §4.5 (smoke test), §4.7 (diagnostic clean-up).
- Prior plans: Phases 0, 1a–1d, 2a, 2b all complete.

## Working Directory

**Git worktree:** `/Users/skykai/Documents/work/VibeApp/.worktrees/v2-arch`

Branch: `v2-arch`. HEAD at plan write time: `34fc058`.

## File Structure

**Create (new module `:gradle-host`):**

| File | Responsibility |
|---|---|
| `gradle-host/build.gradle.kts` | Pure JVM (`kotlin("jvm")`) + shadow plugin; produces `vibeapp-gradle-host-all.jar` |
| `gradle-host/src/main/kotlin/com/vibe/gradle/host/Main.kt` | `main()` entry: reads JSON-line requests from stdin, dispatches, writes events to stdout. |
| `gradle-host/src/main/kotlin/com/vibe/gradle/host/IpcProtocol.kt` | `@Serializable` request/event data classes. **Mirrored** in `:build-gradle` so the app side can deserialize the same wire format. |
| `gradle-host/src/main/kotlin/com/vibe/gradle/host/ToolingApiDriver.kt` | Thin wrapper around `org.gradle.tooling.GradleConnector`; handles `RunBuild` request by forking a `ProjectConnection` + `BuildLauncher` + `ProgressListener`. |

**Create (additions to `:build-gradle`):**

| File | Responsibility |
|---|---|
| `build-gradle/src/main/kotlin/com/vibe/build/gradle/IpcProtocol.kt` | Mirror of the same `@Serializable` classes as in `:gradle-host` (see note below on duplication). |
| `build-gradle/src/main/kotlin/com/vibe/build/gradle/GradleHostExtractor.kt` | Extracts `vibeapp-gradle-host-all.jar` from `assets/` into `filesDir/usr/opt/vibeapp-gradle-host/` on first use. |
| `build-gradle/src/main/kotlin/com/vibe/build/gradle/GradleHostProcess.kt` | Wraps a running `GradleHost` JVM child: NativeProcess + JSON-line writer/reader on stdin/stdout. Single-request-at-a-time. |
| `build-gradle/src/main/kotlin/com/vibe/build/gradle/GradleBuildServiceImpl.kt` | Implements the existing `GradleBuildService` stub (Phase 0) as a real facade over `GradleHostProcess`. |

**Modify:**

| File | Responsibility |
|---|---|
| `settings.gradle.kts` | `include(":gradle-host")` |
| `gradle/libs.versions.toml` | Add `gradle-tooling-api`, `shadow-plugin` entries + version refs |
| `build-gradle/build.gradle.kts` | Add kotlinx-serialization plugin + dep, coroutines, dep on `:build-runtime` (already there) |
| `app/build.gradle.kts` | Register a task that copies `gradle-host/build/libs/vibeapp-gradle-host-all.jar` into `app/src/main/assets/` before `mergeDebugAssets` |
| `app/src/main/kotlin/com/vibe/app/di/BuildGradleModule.kt` | Wire `GradleBuildServiceImpl` |

**Test files:**

| File | Scope |
|---|---|
| `gradle-host/src/test/kotlin/com/vibe/gradle/host/IpcProtocolTest.kt` | JSON roundtrip tests for all request/event types |
| `build-gradle/src/test/kotlin/com/vibe/build/gradle/IpcProtocolTest.kt` | Same roundtrips on the app-side mirror class — proves wire compatibility by asserting the JSON strings are byte-identical |
| `build-gradle/src/androidTest/kotlin/com/vibe/build/gradle/GradleHostInstrumentedTest.kt` | Opt-in (same `vibeapp.phase2b.dev_server_url` runner arg): extracts JAR, starts GradleHost, sends `RunBuild(":help")`, asserts `BuildFinish(success=true)` |

**Do NOT touch in Phase 2c:**
- `:plugin-host`, `:build-engine`, `:build-tools:*`, `:shadow-runtime`
- `ZstdExtractor`, `ProcessEnvBuilder` — they're already correct
- Agent tooling

## Key Constraints (ALL TASKS)

1. **`:gradle-host` is PURE JVM** — `kotlin("jvm")` NOT `com.android.library`. Its output is a plain JAR intended to run on Hotspot JDK 17 on device. It must NOT depend on any Android SDK class.
2. **IPC protocol is duplicated** between `:gradle-host` (producer) and `:build-gradle` (consumer) because Gradle can't cleanly share a module between Android and pure-JVM variants without gymnastics. The protocol is small (~30 lines of data classes); the duplication is less costly than the build complexity of sharing. Tests assert byte-equivalence of serialized output to catch drift.
3. **Tooling API protocol version** MUST match the Gradle distribution on device. `gradle-tooling-api:8.10.2` drives `gradle-8.10.2`. If these disagree, Tooling API fails with a clear "protocol version mismatch" error.
4. **Fat-jar shading**: use `com.gradleup.shadow` (the maintained fork of johnrengelman). Shade `org.slf4j` and any other transitive libs to avoid conflicts with Gradle's own SLF4J when the GradleHost JVM later loads Gradle classes. **Do NOT shade `org.gradle`** — Tooling API classes must remain at their original package so the protocol handshake works.
5. **JSON-line framing**: each request and event is a single JSON object on a single line terminated by `\n`. No embedded newlines within the JSON. kotlinx.serialization's default output is compact (no pretty-printing) — perfect.
6. **Tooling API uses its own "distribution" discovery**: `GradleConnector.useInstallation(File)` points at `$PREFIX/opt/gradle-8.10.2/`. We don't try to force-use the daemon; `--no-daemon` equivalent is `setJvmArguments(...)` if we need it, but we'll let Tooling API's default behavior stand for the minimum viable test.
7. **Acceptance test uses an empty synthetic project**: `settings.gradle.kts` with just `rootProject.name = "probe"`, no `build.gradle.kts`. Running `:help` on this returns immediately with the Gradle help output — proves the full stack without any project-specific complexity.
8. **`vibeapp-gradle-host-all.jar` is built as part of the Gradle build** before `:app:mergeDebugAssets`. The task graph wires `:app:mergeDebugAssets` → `:gradle-host:shadowJar` automatically via our task dependency hook. The asset copy step is idempotent: same hash skips rebuild.

---

## Task 1: `:gradle-host` module scaffold

**Files:**
- Create: `gradle-host/build.gradle.kts`
- Create: `gradle-host/src/main/kotlin/com/vibe/gradle/host/.gitkeep`
- Modify: `settings.gradle.kts`
- Modify: `gradle/libs.versions.toml`

### Step 1: Add versions + libraries to `libs.versions.toml`

Read the current file. In `[versions]`, append:

```toml
gradleToolingApi = "8.10.2"
shadowPlugin = "9.1.0"
```

In `[libraries]`, append:

```toml
gradle-tooling-api = { group = "org.gradle", name = "gradle-tooling-api", version.ref = "gradleToolingApi" }
slf4j-simple = { group = "org.slf4j", name = "slf4j-simple", version = "2.0.16" }
```

In `[plugins]`, append:

```toml
shadow = { id = "com.gradleup.shadow", version.ref = "shadowPlugin" }
```

### Step 2: Create `gradle-host/build.gradle.kts`

```kotlin
plugins {
    kotlin("jvm") version "2.3.10"
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow)
    application
}

kotlin {
    jvmToolchain(17)
}

repositories {
    // Gradle Tooling API is published to Gradle's own maven-style repo.
    maven("https://repo.gradle.org/gradle/libs-releases")
    mavenCentral()
}

dependencies {
    implementation(libs.gradle.tooling.api)
    implementation(libs.slf4j.simple)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

application {
    mainClass.set("com.vibe.gradle.host.MainKt")
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveClassifier.set("all")
    // Slim the fat JAR: merge service files, drop module-info, don't touch
    // org.gradle.* (Tooling API protocol classes must stay at their
    // original package).
    mergeServiceFiles()
    exclude("module-info.class")
}
```

### Step 3: Create an empty stub source file to keep the compiler happy

```bash
cd /Users/skykai/Documents/work/VibeApp/.worktrees/v2-arch
mkdir -p gradle-host/src/main/kotlin/com/vibe/gradle/host
touch gradle-host/src/main/kotlin/com/vibe/gradle/host/.gitkeep
```

The Main.kt, IpcProtocol.kt, ToolingApiDriver.kt land in Tasks 2-4.

### Step 4: Register the module in `settings.gradle.kts`

Read the file. After the last existing `include(":...")` line, append:

```kotlin
include(":gradle-host")
```

### Step 5: Verify the module is recognized + can configure

```bash
./gradlew --no-daemon :gradle-host:projects
```

Expected: no errors (just a "no configured projects" notice — the module is empty). If Gradle fails with "Plugin [id: 'org.jetbrains.kotlin.jvm', version: '2.3.10'] was not found", upgrade or adjust the plugin DSL.

### Step 6: Commit

```bash
git add settings.gradle.kts gradle/libs.versions.toml gradle-host/
git commit -m "build(gradle-host): scaffold pure-JVM :gradle-host module

New module will host the GradleHost JVM program that embeds
Gradle Tooling API 8.10.2. Uses com.gradleup.shadow 9.1.0 for fat-jar
shading (Tooling API + kotlinx-serialization + slf4j-simple), with
org.gradle.* explicitly NOT shaded so protocol handshake with the
bootstrapped Gradle Daemon works.

Phase 2c Task 1. Source files land in subsequent tasks.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: IPC protocol data classes (shared wire format)

**Files:**
- Create: `gradle-host/src/main/kotlin/com/vibe/gradle/host/IpcProtocol.kt`
- Create: `gradle-host/src/test/kotlin/com/vibe/gradle/host/IpcProtocolTest.kt`

### Step 1: Create `IpcProtocol.kt` in `:gradle-host`

```kotlin
package com.vibe.gradle.host

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * JSON-line IPC between VibeApp and GradleHost.
 *
 * Wire format: each request + each event is one JSON object on one
 * line, \n-terminated. kotlinx.serialization emits compact JSON
 * (no whitespace), so the framing is unambiguous.
 *
 * Mirrored in `:build-gradle/.../IpcProtocol.kt` on the app side.
 * Changes to one side MUST be mirrored in the other — the tests in
 * both modules assert byte-equivalent serialization.
 */

@Serializable
sealed class HostRequest {
    abstract val requestId: String

    @Serializable
    @SerialName("Ping")
    data class Ping(override val requestId: String) : HostRequest()

    @Serializable
    @SerialName("RunBuild")
    data class RunBuild(
        override val requestId: String,
        val projectPath: String,
        val tasks: List<String>,
        val args: List<String> = emptyList(),
    ) : HostRequest()

    @Serializable
    @SerialName("Shutdown")
    data class Shutdown(override val requestId: String) : HostRequest()
}

@Serializable
sealed class HostEvent {
    abstract val requestId: String

    @Serializable
    @SerialName("Ready")
    data class Ready(
        override val requestId: String,
        val hostVersion: String,
        val toolingApiVersion: String,
    ) : HostEvent()

    @Serializable
    @SerialName("Pong")
    data class Pong(override val requestId: String) : HostEvent()

    @Serializable
    @SerialName("BuildStart")
    data class BuildStart(override val requestId: String, val ts: Long) : HostEvent()

    @Serializable
    @SerialName("BuildProgress")
    data class BuildProgress(
        override val requestId: String,
        val message: String,
    ) : HostEvent()

    @Serializable
    @SerialName("BuildFinish")
    data class BuildFinish(
        override val requestId: String,
        val success: Boolean,
        val durationMs: Long,
        val failureSummary: String?,
    ) : HostEvent()

    @Serializable
    @SerialName("Log")
    data class Log(
        override val requestId: String,
        val level: String,      // "LIFECYCLE" | "INFO" | "DEBUG" | "WARN" | "ERROR"
        val text: String,
    ) : HostEvent()

    @Serializable
    @SerialName("Error")
    data class Error(
        override val requestId: String,
        val exceptionClass: String,
        val message: String,
    ) : HostEvent()
}

object IpcProtocol {
    val json: Json = Json {
        encodeDefaults = false
        classDiscriminator = "type"
        ignoreUnknownKeys = true
    }

    fun encodeRequest(request: HostRequest): String =
        json.encodeToString(HostRequest.serializer(), request)

    fun decodeRequest(line: String): HostRequest =
        json.decodeFromString(HostRequest.serializer(), line)

    fun encodeEvent(event: HostEvent): String =
        json.encodeToString(HostEvent.serializer(), event)

    fun decodeEvent(line: String): HostEvent =
        json.decodeFromString(HostEvent.serializer(), line)
}
```

### Step 2: Create `IpcProtocolTest.kt` in `:gradle-host`

```kotlin
package com.vibe.gradle.host

import org.junit.Assert.assertEquals
import org.junit.Test

class IpcProtocolTest {

    @Test
    fun `roundtrip Ping request`() {
        val request: HostRequest = HostRequest.Ping(requestId = "r1")
        val line = IpcProtocol.encodeRequest(request)
        assertEquals("""{"type":"Ping","requestId":"r1"}""", line)
        assertEquals(request, IpcProtocol.decodeRequest(line))
    }

    @Test
    fun `roundtrip RunBuild request with defaults`() {
        val request: HostRequest = HostRequest.RunBuild(
            requestId = "r2",
            projectPath = "/tmp/foo",
            tasks = listOf(":help"),
        )
        val line = IpcProtocol.encodeRequest(request)
        // args defaults to empty; encodeDefaults=false → omitted from JSON
        assertEquals(
            """{"type":"RunBuild","requestId":"r2","projectPath":"/tmp/foo","tasks":[":help"]}""",
            line,
        )
        assertEquals(request, IpcProtocol.decodeRequest(line))
    }

    @Test
    fun `roundtrip BuildFinish event with null failure`() {
        val event: HostEvent = HostEvent.BuildFinish(
            requestId = "r2",
            success = true,
            durationMs = 1234L,
            failureSummary = null,
        )
        val line = IpcProtocol.encodeEvent(event)
        // failureSummary is nullable; encodeDefaults=false does NOT drop
        // explicit nulls, but serializer treats null distinctly from default.
        // With explicit null we expect it in output.
        assertEquals(
            """{"type":"BuildFinish","requestId":"r2","success":true,"durationMs":1234,"failureSummary":null}""",
            line,
        )
        assertEquals(event, IpcProtocol.decodeEvent(line))
    }

    @Test
    fun `all event subtypes roundtrip`() {
        val events = listOf<HostEvent>(
            HostEvent.Ready("r1", "1.0.0", "8.10.2"),
            HostEvent.Pong("r1"),
            HostEvent.BuildStart("r1", 1_700_000_000L),
            HostEvent.BuildProgress("r1", "> Task :help"),
            HostEvent.BuildFinish("r1", true, 1000L, null),
            HostEvent.BuildFinish("r1", false, 2000L, "compile error"),
            HostEvent.Log("r1", "LIFECYCLE", "hello"),
            HostEvent.Error("r1", "java.lang.RuntimeException", "boom"),
        )
        for (event in events) {
            val line = IpcProtocol.encodeEvent(event)
            assertEquals(event, IpcProtocol.decodeEvent(line))
        }
    }
}
```

### Step 3: Verify + commit

```bash
./gradlew --no-daemon :gradle-host:test
```

Expected: 4 tests, 0 failures.

```bash
git add gradle-host/src/main/kotlin/com/vibe/gradle/host/IpcProtocol.kt \
        gradle-host/src/test/kotlin/com/vibe/gradle/host/IpcProtocolTest.kt
git commit -m "feat(gradle-host): IPC protocol data classes + roundtrip tests

Wire format is JSON-line with classDiscriminator='type'. Each
HostRequest and HostEvent subtype has stable serial names; default
values are omitted to keep lines short. 4 JUnit tests assert the
byte-exact JSON representations for a few canonical subtypes + one
'all subtypes roundtrip' test.

This same schema is mirrored in :build-gradle in the next task.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: Mirror IPC protocol in `:build-gradle`

**Files:**
- Create: `build-gradle/src/main/kotlin/com/vibe/build/gradle/IpcProtocol.kt`
- Create: `build-gradle/src/test/kotlin/com/vibe/build/gradle/IpcProtocolTest.kt`
- Modify: `build-gradle/build.gradle.kts` — add `kotlin.serialization` plugin + deps

### Step 1: Update `build-gradle/build.gradle.kts`

Replace the `plugins { ... }` block and `dependencies { ... }` block. Keep everything else unchanged.

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.android.hilt)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.kotlin.serialization)
}

// android { ... } block unchanged

dependencies {
    implementation(project(":build-runtime"))
    implementation(libs.hilt)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
}
```

### Step 2: Create the mirror `IpcProtocol.kt` in `:build-gradle`

**This file is an exact copy of `:gradle-host/.../IpcProtocol.kt` except the package declaration.** Duplication is intentional — see the Key Constraints note on why we don't share a module.

```kotlin
package com.vibe.build.gradle

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * JSON-line IPC between VibeApp and GradleHost.
 *
 * Wire format: each request + each event is one JSON object on one
 * line, \n-terminated. Mirror of `:gradle-host/.../IpcProtocol.kt`.
 * Byte-compatibility between the two mirrors is asserted in both
 * modules' unit tests.
 */

@Serializable
sealed class HostRequest {
    abstract val requestId: String

    @Serializable
    @SerialName("Ping")
    data class Ping(override val requestId: String) : HostRequest()

    @Serializable
    @SerialName("RunBuild")
    data class RunBuild(
        override val requestId: String,
        val projectPath: String,
        val tasks: List<String>,
        val args: List<String> = emptyList(),
    ) : HostRequest()

    @Serializable
    @SerialName("Shutdown")
    data class Shutdown(override val requestId: String) : HostRequest()
}

@Serializable
sealed class HostEvent {
    abstract val requestId: String

    @Serializable
    @SerialName("Ready")
    data class Ready(
        override val requestId: String,
        val hostVersion: String,
        val toolingApiVersion: String,
    ) : HostEvent()

    @Serializable
    @SerialName("Pong")
    data class Pong(override val requestId: String) : HostEvent()

    @Serializable
    @SerialName("BuildStart")
    data class BuildStart(override val requestId: String, val ts: Long) : HostEvent()

    @Serializable
    @SerialName("BuildProgress")
    data class BuildProgress(
        override val requestId: String,
        val message: String,
    ) : HostEvent()

    @Serializable
    @SerialName("BuildFinish")
    data class BuildFinish(
        override val requestId: String,
        val success: Boolean,
        val durationMs: Long,
        val failureSummary: String?,
    ) : HostEvent()

    @Serializable
    @SerialName("Log")
    data class Log(
        override val requestId: String,
        val level: String,
        val text: String,
    ) : HostEvent()

    @Serializable
    @SerialName("Error")
    data class Error(
        override val requestId: String,
        val exceptionClass: String,
        val message: String,
    ) : HostEvent()
}

object IpcProtocol {
    val json: Json = Json {
        encodeDefaults = false
        classDiscriminator = "type"
        ignoreUnknownKeys = true
    }

    fun encodeRequest(request: HostRequest): String =
        json.encodeToString(HostRequest.serializer(), request)

    fun decodeRequest(line: String): HostRequest =
        json.decodeFromString(HostRequest.serializer(), line)

    fun encodeEvent(event: HostEvent): String =
        json.encodeToString(HostEvent.serializer(), event)

    fun decodeEvent(line: String): HostEvent =
        json.decodeFromString(HostEvent.serializer(), line)
}
```

### Step 3: Create the mirror test (byte-equivalence assertions)

```kotlin
package com.vibe.build.gradle

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Mirror of :gradle-host's IpcProtocolTest. Asserts the same wire
 * bytes — if these diverge, the IPC breaks.
 */
class IpcProtocolTest {

    @Test
    fun `roundtrip Ping request`() {
        val request: HostRequest = HostRequest.Ping(requestId = "r1")
        val line = IpcProtocol.encodeRequest(request)
        assertEquals("""{"type":"Ping","requestId":"r1"}""", line)
        assertEquals(request, IpcProtocol.decodeRequest(line))
    }

    @Test
    fun `roundtrip RunBuild request with defaults`() {
        val request: HostRequest = HostRequest.RunBuild(
            requestId = "r2",
            projectPath = "/tmp/foo",
            tasks = listOf(":help"),
        )
        val line = IpcProtocol.encodeRequest(request)
        assertEquals(
            """{"type":"RunBuild","requestId":"r2","projectPath":"/tmp/foo","tasks":[":help"]}""",
            line,
        )
        assertEquals(request, IpcProtocol.decodeRequest(line))
    }

    @Test
    fun `roundtrip BuildFinish event with null failure`() {
        val event: HostEvent = HostEvent.BuildFinish(
            requestId = "r2",
            success = true,
            durationMs = 1234L,
            failureSummary = null,
        )
        val line = IpcProtocol.encodeEvent(event)
        assertEquals(
            """{"type":"BuildFinish","requestId":"r2","success":true,"durationMs":1234,"failureSummary":null}""",
            line,
        )
        assertEquals(event, IpcProtocol.decodeEvent(line))
    }

    @Test
    fun `all event subtypes roundtrip`() {
        val events = listOf<HostEvent>(
            HostEvent.Ready("r1", "1.0.0", "8.10.2"),
            HostEvent.Pong("r1"),
            HostEvent.BuildStart("r1", 1_700_000_000L),
            HostEvent.BuildProgress("r1", "> Task :help"),
            HostEvent.BuildFinish("r1", true, 1000L, null),
            HostEvent.BuildFinish("r1", false, 2000L, "compile error"),
            HostEvent.Log("r1", "LIFECYCLE", "hello"),
            HostEvent.Error("r1", "java.lang.RuntimeException", "boom"),
        )
        for (event in events) {
            val line = IpcProtocol.encodeEvent(event)
            assertEquals(event, IpcProtocol.decodeEvent(line))
        }
    }
}
```

### Step 4: Verify + commit

```bash
./gradlew --no-daemon :build-gradle:testDebugUnitTest :gradle-host:test
```

Expected: both test suites pass. 4 tests each.

```bash
git add build-gradle/build.gradle.kts \
        build-gradle/src/main/kotlin/com/vibe/build/gradle/IpcProtocol.kt \
        build-gradle/src/test/kotlin/com/vibe/build/gradle/IpcProtocolTest.kt
git commit -m "feat(build-gradle): IPC protocol mirror + kotlin-serialization

App-side copy of :gradle-host's IpcProtocol.kt (kotlinx-serialization
@Serializable data classes). Tests assert byte-identical JSON output
vs the host side. Duplication is deliberate — sharing a module between
Android and pure-JVM variants would require testFixtures or a
multi-variant setup that's heavier than the ~30-line copy.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: `GradleHost` main + ToolingApiDriver (the JVM program)

**Files:**
- Create: `gradle-host/src/main/kotlin/com/vibe/gradle/host/Main.kt`
- Create: `gradle-host/src/main/kotlin/com/vibe/gradle/host/ToolingApiDriver.kt`

### Step 1: Create `ToolingApiDriver.kt`

```kotlin
package com.vibe.gradle.host

import org.gradle.tooling.BuildException
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.events.OperationDescriptor
import org.gradle.tooling.events.OperationType
import org.gradle.tooling.events.ProgressEvent
import org.gradle.tooling.events.ProgressListener
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

/**
 * Runs a single Gradle build via Tooling API and reports the lifecycle as
 * structured HostEvent instances.
 *
 * Caller supplies the path to the bootstrapped Gradle distribution
 * (typically `$PREFIX/opt/gradle-8.10.2`). This class does not manage
 * daemon lifecycle explicitly — Tooling API handles that.
 */
internal class ToolingApiDriver(
    private val gradleDistribution: File,
) {
    init {
        require(gradleDistribution.isDirectory) {
            "Gradle distribution not found: $gradleDistribution"
        }
    }

    fun runBuild(request: HostRequest.RunBuild, emit: (HostEvent) -> Unit) {
        val projectDir = File(request.projectPath)
        require(projectDir.isDirectory) {
            "project path not a directory: $projectDir"
        }

        val started = System.currentTimeMillis()
        emit(HostEvent.BuildStart(request.requestId, started))

        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()

        val connector = GradleConnector.newConnector()
            .forProjectDirectory(projectDir)
            .useInstallation(gradleDistribution)

        try {
            connector.connect().use { connection ->
                val launcher = connection.newBuild()
                    .forTasks(*request.tasks.toTypedArray())
                    .withArguments(request.args)
                    .setStandardOutput(PrintStream(stdout))
                    .setStandardError(PrintStream(stderr))

                launcher.addProgressListener(
                    ProgressListener { event ->
                        emit(HostEvent.BuildProgress(request.requestId, event.displayName))
                    },
                    setOf(OperationType.TASK, OperationType.GENERIC),
                )
                launcher.run()
            }

            // Emit captured output as Log events
            emitCapturedStream(stdout.toByteArray(), "LIFECYCLE", request.requestId, emit)
            emitCapturedStream(stderr.toByteArray(), "ERROR", request.requestId, emit)

            val durationMs = System.currentTimeMillis() - started
            emit(HostEvent.BuildFinish(request.requestId, success = true, durationMs = durationMs, failureSummary = null))
        } catch (t: BuildException) {
            emitCapturedStream(stdout.toByteArray(), "LIFECYCLE", request.requestId, emit)
            emitCapturedStream(stderr.toByteArray(), "ERROR", request.requestId, emit)
            val durationMs = System.currentTimeMillis() - started
            emit(
                HostEvent.BuildFinish(
                    requestId = request.requestId,
                    success = false,
                    durationMs = durationMs,
                    failureSummary = t.message ?: t.javaClass.name,
                ),
            )
        } catch (t: Throwable) {
            emit(
                HostEvent.Error(
                    requestId = request.requestId,
                    exceptionClass = t.javaClass.name,
                    message = t.message ?: "(no message)",
                ),
            )
        }
    }

    private fun emitCapturedStream(
        bytes: ByteArray,
        level: String,
        requestId: String,
        emit: (HostEvent) -> Unit,
    ) {
        if (bytes.isEmpty()) return
        val text = String(bytes, Charsets.UTF_8)
        text.lineSequence().filter { it.isNotEmpty() }.forEach { line ->
            emit(HostEvent.Log(requestId = requestId, level = level, text = line))
        }
    }
}
```

### Step 2: Create `Main.kt`

```kotlin
package com.vibe.gradle.host

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintStream

/**
 * GradleHost entry point.
 *
 * Reads JSON-line HostRequest objects from stdin and writes JSON-line
 * HostEvent objects to stdout. The very first line written is a
 * `Ready` event announcing versions. On `Shutdown` or stdin EOF, the
 * process exits 0.
 *
 * Usage (typically from VibeApp's GradleHostProcess):
 *   java -jar vibeapp-gradle-host-all.jar --gradle-distribution <path>
 */
private const val HOST_VERSION = "2.0.0-phase-2c"

fun main(args: Array<String>) {
    val gradleDistPath = parseGradleDistArg(args)
        ?: die("missing --gradle-distribution <path>; run with --help for usage")
    val gradleDist = File(gradleDistPath)
    if (!gradleDist.isDirectory) {
        die("Gradle distribution not found or not a directory: $gradleDist")
    }

    // Important: all stdout is the IPC channel. Don't println() anywhere
    // else. Use stderr for any internal logging.
    val out = PrintStream(System.out.buffered(), /* autoFlush = */ true, Charsets.UTF_8)
    val err = System.err

    val emit: (HostEvent) -> Unit = { ev ->
        out.println(IpcProtocol.encodeEvent(ev))
    }

    // Initial Ready signal
    emit(
        HostEvent.Ready(
            requestId = "",
            hostVersion = HOST_VERSION,
            toolingApiVersion = toolingApiVersion(),
        ),
    )

    val driver = ToolingApiDriver(gradleDistribution = gradleDist)

    BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8)).use { reader ->
        while (true) {
            val line = reader.readLine() ?: break  // stdin closed → exit
            if (line.isBlank()) continue

            val request = try {
                IpcProtocol.decodeRequest(line)
            } catch (t: Throwable) {
                err.println("[gradle-host] malformed request dropped: ${t.message}")
                continue
            }

            when (request) {
                is HostRequest.Ping -> emit(HostEvent.Pong(request.requestId))
                is HostRequest.Shutdown -> {
                    emit(HostEvent.Log(request.requestId, "LIFECYCLE", "shutting down"))
                    return
                }
                is HostRequest.RunBuild -> driver.runBuild(request, emit)
            }
        }
    }
}

private fun parseGradleDistArg(args: Array<String>): String? {
    var i = 0
    while (i < args.size) {
        if (args[i] == "--gradle-distribution" && i + 1 < args.size) {
            return args[i + 1]
        }
        if (args[i] == "--help") {
            println("Usage: vibeapp-gradle-host --gradle-distribution <path>")
            kotlin.system.exitProcess(0)
        }
        i++
    }
    return null
}

private fun toolingApiVersion(): String {
    // Read from GradleConnector package metadata at runtime. This matches
    // the version baked into the shaded JAR; avoids hardcoding.
    return org.gradle.tooling.GradleConnector::class.java.`package`.implementationVersion
        ?: "unknown"
}

private fun die(message: String): Nothing {
    System.err.println("[gradle-host] error: $message")
    kotlin.system.exitProcess(2)
}
```

### Step 3: Produce the fat jar locally + sanity check

```bash
./gradlew --no-daemon :gradle-host:shadowJar
ls -la gradle-host/build/libs/vibeapp-gradle-host-*-all.jar
```

Expected: a JAR in the ~15-20 MB range. Try running it with `--help`:

```bash
java -jar gradle-host/build/libs/vibeapp-gradle-host-*-all.jar --help
```

Expected output: `Usage: vibeapp-gradle-host --gradle-distribution <path>`. Exit 0.

### Step 4: Commit

```bash
git add gradle-host/src/main/kotlin/com/vibe/gradle/host/Main.kt \
        gradle-host/src/main/kotlin/com/vibe/gradle/host/ToolingApiDriver.kt
git commit -m "feat(gradle-host): Main entry + ToolingApiDriver

Main reads JSON-line HostRequests from stdin, dispatches to the
driver, and writes JSON-line HostEvents to stdout. The first line
emitted is a Ready event announcing host + Tooling API versions. On
Shutdown or stdin EOF the process exits 0.

ToolingApiDriver wraps GradleConnector to run a single build:
forProjectDirectory + useInstallation + forTasks + withArguments +
addProgressListener. Captured stdout/stderr is re-emitted as Log
events after the build finishes. BuildException → BuildFinish with
success=false + failureSummary; any other Throwable → Error event.

No unit tests for Main / Driver at this point — exercised end-to-end
in Task 7's instrumented test.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: Wire `gradle-host` fat JAR into the app APK

**Files:**
- Modify: `app/build.gradle.kts` — add a task that copies the fat jar into assets
- Create: `build-gradle/src/main/kotlin/com/vibe/build/gradle/GradleHostExtractor.kt`

### Step 1: Add the asset-copy task to `app/build.gradle.kts`

Read the current file. After the `android { ... }` block and before `configurations.all { ... }`, add:

```kotlin
val copyGradleHostJar by tasks.registering(Copy::class) {
    dependsOn(":gradle-host:shadowJar")
    from(project(":gradle-host").layout.buildDirectory.file("libs"))
    into(layout.projectDirectory.dir("src/main/assets"))
    include("vibeapp-gradle-host-*-all.jar")
    rename { "vibeapp-gradle-host.jar" }
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(copyGradleHostJar)
}
```

Also add `app/src/main/assets/vibeapp-gradle-host.jar` to `.gitignore` (committed JAR is noise; it's rebuilt each time):

Open `.gitignore`, append:

```
# GradleHost shaded JAR, copied from gradle-host/build/libs at build time.
/app/src/main/assets/vibeapp-gradle-host.jar
```

### Step 2: Create `GradleHostExtractor.kt`

```kotlin
package com.vibe.build.gradle

import android.content.Context
import com.vibe.build.runtime.bootstrap.BootstrapFileSystem
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Extracts the shaded GradleHost fat JAR from the APK assets into
 * `$PREFIX/opt/vibeapp-gradle-host/vibeapp-gradle-host.jar` on first
 * use. Subsequent calls are a no-op unless the asset's SHA-256 has
 * changed (e.g. after a VibeApp upgrade), in which case the cached
 * copy is replaced.
 *
 * On-disk layout (matches other bootstrap components, even though
 * this one is NOT downloaded):
 *   $PREFIX/opt/vibeapp-gradle-host/
 *     vibeapp-gradle-host.jar
 *     .sha256
 */
@Singleton
class GradleHostExtractor @Inject constructor(
    private val context: Context,
    private val fs: BootstrapFileSystem,
) {

    /**
     * Ensures the JAR is present on disk and returns its [File].
     * Thread-safe via synchronization; the extraction is idempotent.
     */
    @Synchronized
    fun ensureExtracted(): File {
        val componentDir = fs.componentInstallDir(COMPONENT_ID)
        val jarFile = File(componentDir, JAR_NAME)
        val hashFile = File(componentDir, HASH_FILE)

        val assetHash = computeAssetHash()
        val cachedHash = if (hashFile.exists()) hashFile.readText().trim() else null

        if (jarFile.isFile && cachedHash == assetHash) {
            return jarFile
        }

        componentDir.mkdirs()
        context.assets.open(ASSET_NAME).use { input ->
            jarFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        hashFile.writeText(assetHash)
        return jarFile
    }

    private fun computeAssetHash(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        context.assets.open(ASSET_NAME).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val ASSET_NAME = "vibeapp-gradle-host.jar"
        private const val COMPONENT_ID = "vibeapp-gradle-host"
        private const val JAR_NAME = "vibeapp-gradle-host.jar"
        private const val HASH_FILE = ".sha256"
    }
}
```

### Step 3: Verify full build including asset copy

```bash
./gradlew --no-daemon :app:assembleDebug
ls -la app/src/main/assets/vibeapp-gradle-host.jar
```

Expected: the JAR exists in assets, size 15-20 MB. `:app:assembleDebug` BUILD SUCCESSFUL.

### Step 4: Commit

```bash
git add app/build.gradle.kts .gitignore \
        build-gradle/src/main/kotlin/com/vibe/build/gradle/GradleHostExtractor.kt
git commit -m "feat(app,build-gradle): wire GradleHost fat jar into APK

app/build.gradle.kts: new copyGradleHostJar task depends on
:gradle-host:shadowJar, copies the fat JAR into
app/src/main/assets/vibeapp-gradle-host.jar. Every mergeXxxAssets
task depends on it — ensures the asset is up to date before APK
packaging.

GradleHostExtractor (in :build-gradle) extracts the asset to
\$PREFIX/opt/vibeapp-gradle-host/ on first use. SHA-256 cache key
handles VibeApp upgrades that ship a new JAR.

.gitignore: committed JAR is build output; never tracked.

Phase 2c Task 5.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: `GradleHostProcess` + `GradleBuildServiceImpl`

**Files:**
- Create: `build-gradle/src/main/kotlin/com/vibe/build/gradle/GradleHostProcess.kt`
- Create: `build-gradle/src/main/kotlin/com/vibe/build/gradle/GradleBuildServiceImpl.kt`
- Modify: `build-gradle/src/main/kotlin/com/vibe/build/gradle/GradleBuildService.kt` — update signature
- Modify: `app/src/main/kotlin/com/vibe/app/di/BuildGradleModule.kt` — bind the new impl

### Step 1: Update the existing `GradleBuildService.kt` stub into an interface

Read the current file (Phase 0 placeholder with `BuildRuntime` dep). Replace with:

```kotlin
package com.vibe.build.gradle

import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * App-facing entry point for running builds via the GradleHost
 * JVM process. Implementations are responsible for spawning +
 * tearing down the host and relaying IPC.
 */
interface GradleBuildService {
    /**
     * Spawn GradleHost (if not already running) and await the
     * initial [HostEvent.Ready] event. Returns the Tooling API
     * version reported by the host.
     */
    suspend fun start(gradleDistribution: File): String

    /**
     * Run the given Gradle tasks on the given project directory.
     * Emits a stream of [HostEvent]s ending with [HostEvent.BuildFinish]
     * or [HostEvent.Error]. The caller must collect the flow to
     * completion for the request to fully drain.
     */
    fun runBuild(
        projectDirectory: File,
        tasks: List<String>,
        args: List<String> = emptyList(),
    ): Flow<HostEvent>

    /**
     * Graceful shutdown: sends a Shutdown request + waits for the
     * host process to exit. Idempotent.
     */
    suspend fun shutdown()
}
```

### Step 2: Create `GradleHostProcess.kt`

```kotlin
package com.vibe.build.gradle

import com.vibe.build.runtime.bootstrap.BootstrapFileSystem
import com.vibe.build.runtime.process.ProcessEvent
import com.vibe.build.runtime.process.ProcessLauncher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * Wraps the live GradleHost JVM subprocess.
 *
 * One instance = one host process. Requests are serialized (one at
 * a time) — for now. Phase 2d may add multiplexing.
 */
internal class GradleHostProcess(
    private val launcher: ProcessLauncher,
    private val fs: BootstrapFileSystem,
    private val hostJar: File,
    private val javaBinary: File,
) {
    private val events = MutableSharedFlow<HostEvent>(extraBufferCapacity = 1024)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var process: com.vibe.build.runtime.process.NativeProcess
    private var readerJob: Job? = null

    val eventFlow: Flow<HostEvent> = events.asSharedFlow()

    /**
     * Start the host JVM and wait for its Ready event.
     * Returns the reported Tooling API version.
     */
    suspend fun start(gradleDistribution: File): String = withContext(Dispatchers.IO) {
        check(!::process.isInitialized) { "GradleHostProcess.start called twice" }

        val cwd = fs.componentInstallDir("vibeapp-gradle-host")
        cwd.mkdirs()

        process = launcher.launch(
            executable = javaBinary.absolutePath,
            args = listOf(
                "-jar", hostJar.absolutePath,
                "--gradle-distribution", gradleDistribution.absolutePath,
            ),
            cwd = cwd,
        )

        val readyDeferred = CompletableDeferred<String>()
        val lineBuffer = StringBuilder()

        readerJob = scope.launch {
            process.events.collect { ev ->
                when (ev) {
                    is ProcessEvent.Stdout -> {
                        val chunk = String(ev.bytes, Charsets.UTF_8)
                        lineBuffer.append(chunk)
                        while (true) {
                            val newlineIdx = lineBuffer.indexOf('\n')
                            if (newlineIdx < 0) break
                            val line = lineBuffer.substring(0, newlineIdx)
                            lineBuffer.delete(0, newlineIdx + 1)
                            if (line.isBlank()) continue
                            try {
                                val hostEvent = IpcProtocol.decodeEvent(line)
                                if (hostEvent is HostEvent.Ready && !readyDeferred.isCompleted) {
                                    readyDeferred.complete(hostEvent.toolingApiVersion)
                                }
                                events.emit(hostEvent)
                            } catch (t: Throwable) {
                                // Ignore malformed lines; log to stderr-equivalent
                                // (no Android Log.e here — pure JVM + Android
                                // compatibility; see diagnostic pipeline Phase 2d).
                            }
                        }
                    }
                    is ProcessEvent.Stderr -> {
                        // stderr is informational; surface via Log events if
                        // multiple lines. For now we silently ignore.
                    }
                    is ProcessEvent.Exited -> {
                        if (!readyDeferred.isCompleted) {
                            readyDeferred.completeExceptionally(
                                IllegalStateException("GradleHost exited before Ready (code=${ev.code})"),
                            )
                        }
                    }
                }
            }
        }

        readyDeferred.await()
    }

    /**
     * Write a request to the host's stdin. Caller gets the event
     * stream from [eventFlow] and filters on [HostRequest.requestId].
     */
    fun writeRequest(request: HostRequest) {
        val line = IpcProtocol.encodeRequest(request) + "\n"
        process.writeStdin(line.toByteArray(Charsets.UTF_8))
    }

    suspend fun shutdown() {
        val id = "shutdown-${System.nanoTime()}"
        writeRequest(HostRequest.Shutdown(id))
        process.closeStdin()
        process.awaitExit()
        readerJob?.cancel()
    }
}
```

### Step 3: Create `GradleBuildServiceImpl.kt`

```kotlin
package com.vibe.build.gradle

import com.vibe.build.runtime.bootstrap.BootstrapFileSystem
import com.vibe.build.runtime.process.ProcessEnvBuilder
import com.vibe.build.runtime.process.ProcessLauncher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.transformWhile
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GradleBuildServiceImpl @Inject constructor(
    private val launcher: ProcessLauncher,
    private val envBuilder: ProcessEnvBuilder,
    private val extractor: GradleHostExtractor,
    private val fs: BootstrapFileSystem,
) : GradleBuildService {

    private var hostProcess: GradleHostProcess? = null

    override suspend fun start(gradleDistribution: File): String {
        val existing = hostProcess
        if (existing != null) {
            // Already started — return its reported version by
            // triggering a Ping; for now, just return a sentinel.
            // Phase 2d will cache the Ready value properly.
            return "already-running"
        }

        val jar = extractor.ensureExtracted()
        val javaBinary = File(fs.componentInstallDir("jdk-17.0.13"), "bin/java")

        val host = GradleHostProcess(
            launcher = launcher,
            fs = fs,
            hostJar = jar,
            javaBinary = javaBinary,
        )
        val version = host.start(gradleDistribution)
        hostProcess = host
        return version
    }

    override fun runBuild(
        projectDirectory: File,
        tasks: List<String>,
        args: List<String>,
    ): Flow<HostEvent> {
        val host = checkNotNull(hostProcess) { "GradleBuildService not started" }
        val requestId = "build-${System.nanoTime()}"
        host.writeRequest(
            HostRequest.RunBuild(
                requestId = requestId,
                projectPath = projectDirectory.absolutePath,
                tasks = tasks,
                args = args,
            ),
        )
        return host.eventFlow
            .filter { it.requestId == requestId }
            .transformWhile { event ->
                emit(event)
                event !is HostEvent.BuildFinish && event !is HostEvent.Error
            }
    }

    override suspend fun shutdown() {
        hostProcess?.shutdown()
        hostProcess = null
    }
}
```

### Step 4: Update `BuildGradleModule.kt` to bind the new impl

Read the current file. Replace with:

```kotlin
package com.vibe.app.di

import com.vibe.build.gradle.GradleBuildService
import com.vibe.build.gradle.GradleBuildServiceImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class BuildGradleModule {
    @Binds
    @Singleton
    abstract fun bindGradleBuildService(impl: GradleBuildServiceImpl): GradleBuildService
}
```

### Step 5: Verify Hilt + full assemble

```bash
./gradlew --no-daemon :app:kspDebugKotlin :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

### Step 6: Commit

```bash
git add build-gradle/src/main/kotlin/com/vibe/build/gradle/GradleHostProcess.kt \
        build-gradle/src/main/kotlin/com/vibe/build/gradle/GradleBuildServiceImpl.kt \
        build-gradle/src/main/kotlin/com/vibe/build/gradle/GradleBuildService.kt \
        app/src/main/kotlin/com/vibe/app/di/BuildGradleModule.kt
git commit -m "feat(build-gradle): GradleBuildServiceImpl + IPC client

Implements the Phase 0 stub as a real facade over GradleHostProcess.
GradleHostProcess wraps a live GradleHost JVM subprocess: spawns it
via ProcessLauncher, consumes its stdout as JSON-line HostEvents
(stateful buffer splits at \\n), broadcasts events through a
MutableSharedFlow, awaits the initial Ready event.

GradleBuildServiceImpl.runBuild() writes a RunBuild request to
stdin and returns a filtered Flow scoped to that request ID,
terminated by BuildFinish or Error.

Hilt binds the impl in BuildGradleModule.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: End-to-end instrumented test (`:help` on empty project)

**Files:**
- Create: `build-gradle/src/androidTest/kotlin/com/vibe/build/gradle/GradleHostInstrumentedTest.kt`

### Step 1: Create the test

```kotlin
package com.vibe.build.gradle

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vibe.build.runtime.bootstrap.Abi
import com.vibe.build.runtime.bootstrap.BootstrapDownloader
import com.vibe.build.runtime.bootstrap.BootstrapFileSystem
import com.vibe.build.runtime.bootstrap.BootstrapState
import com.vibe.build.runtime.bootstrap.InMemoryBootstrapStateStore
import com.vibe.build.runtime.bootstrap.ManifestFetcher
import com.vibe.build.runtime.bootstrap.ManifestParser
import com.vibe.build.runtime.bootstrap.ManifestSignature
import com.vibe.build.runtime.bootstrap.MirrorSelector
import com.vibe.build.runtime.bootstrap.RuntimeBootstrapper
import com.vibe.build.runtime.bootstrap.ZstdExtractor
import com.vibe.build.runtime.process.NativeProcessLauncher
import com.vibe.build.runtime.process.PreloadLibLocator
import com.vibe.build.runtime.process.ProcessEnvBuilder
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 2c acceptance. Opt-in via
 * `-Pandroid.testInstrumentationRunnerArguments.vibeapp.phase2c.dev_server_url=http://localhost:8000`.
 *
 * Bootstraps JDK + Gradle from dev server, extracts GradleHost JAR
 * from APK assets, spawns the JVM, asks it to run `:help` on a
 * synthetic empty project (just settings.gradle.kts). Asserts a
 * terminal BuildFinish(success=true).
 */
@RunWith(AndroidJUnit4::class)
class GradleHostInstrumentedTest {

    private lateinit var ctx: Context
    private lateinit var scratchDir: File
    private lateinit var fs: BootstrapFileSystem
    private lateinit var launcher: NativeProcessLauncher
    private lateinit var bootstrapper: RuntimeBootstrapper
    private lateinit var service: GradleBuildServiceImpl

    private fun devServerUrlOrSkip(): String {
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("vibeapp.phase2c.dev_server_url")
        assumeTrue(
            "vibeapp.phase2c.dev_server_url not provided; skipping Phase 2c acceptance",
            !url.isNullOrBlank(),
        )
        return url!!.trimEnd('/')
    }

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        scratchDir = File(ctx.cacheDir, "phase2c-${System.nanoTime()}")
        require(scratchDir.mkdirs())

        fs = BootstrapFileSystem(filesDir = scratchDir)
        fs.ensureDirectories()
        val preloadLib = PreloadLibLocator(File(ctx.applicationInfo.nativeLibraryDir))
        val envBuilder = ProcessEnvBuilder(fs, preloadLib)
        launcher = NativeProcessLauncher(envBuilder)
    }

    @After
    fun tearDown() = runBlocking {
        if (::service.isInitialized) {
            try { service.shutdown() } catch (_: Throwable) {}
        }
        scratchDir.deleteRecursively()
    }

    private fun buildBootstrapper(devBaseUrl: String): RuntimeBootstrapper {
        val mirrors = MirrorSelector(
            primaryBase = devBaseUrl,
            fallbackBase = "https://unused.test",
        )
        val devPubkeyHex = "5ce0c624f59a72ee8eb6f72c25ad905a27afcd0392998f353ef86f3247725f40"
        return RuntimeBootstrapper(
            fs = fs,
            store = InMemoryBootstrapStateStore(),
            parser = ManifestParser(),
            signature = ManifestSignature(publicKeyHex = devPubkeyHex),
            mirrors = mirrors,
            downloader = BootstrapDownloader(),
            extractor = ZstdExtractor(),
            abi = Abi.pickPreferred(Build.SUPPORTED_ABIS) ?: Abi.ARM64,
            fetcher = ManifestFetcher(mirrors),
            parsedManifestOverride = null,
        )
    }

    @Test
    fun help_task_on_empty_project_succeeds() = runBlocking {
        val devUrl = devServerUrlOrSkip()
        bootstrapper = buildBootstrapper(devUrl)

        val seen = mutableListOf<BootstrapState>()
        bootstrapper.bootstrap(manifestUrl = "manifest.json") { seen += it }
        assertTrue("bootstrap failed: ${seen.last()}", seen.last() is BootstrapState.Ready)

        // Stage a synthetic empty Gradle project. The settings file alone is
        // enough for Gradle to recognize this as a project root.
        val probeDir = File(scratchDir, "probe")
        probeDir.mkdirs()
        File(probeDir, "settings.gradle.kts").writeText("""rootProject.name = "probe"""")

        // Extract the GradleHost JAR + build the service
        val extractor = GradleHostExtractor(ctx, fs)
        service = GradleBuildServiceImpl(
            launcher = launcher,
            envBuilder = ProcessEnvBuilder(fs, PreloadLibLocator(File(ctx.applicationInfo.nativeLibraryDir))),
            extractor = extractor,
            fs = fs,
        )

        val gradleDist = fs.componentInstallDir("gradle-8.10.2")
        val toolingApiVersion = withTimeout(60_000) { service.start(gradleDist) }
        assertTrue(
            "expected Tooling API 8.10.2, got '$toolingApiVersion'",
            toolingApiVersion.contains("8.10"),
        )

        val events = withTimeout(120_000) {
            service.runBuild(projectDirectory = probeDir, tasks = listOf(":help")).toList()
        }
        val finish = events.filterIsInstance<HostEvent.BuildFinish>().first()
        assertEquals(
            "expected success, got failure: ${finish.failureSummary}",
            true, finish.success,
        )
    }
}
```

### Step 2: Ensure `:build-gradle` test classpath reaches MockWebServer and coroutines-test (probably already there; add if not)

Read `build-gradle/build.gradle.kts`. In the `dependencies { ... }` block, ensure these are present:

```kotlin
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
```

Add any that are missing.

### Step 3: Compile-only check

```bash
./gradlew --no-daemon :build-gradle:compileDebugAndroidTestKotlin
```

Expected: BUILD SUCCESSFUL.

### Step 4: Commit

```bash
git add build-gradle/src/androidTest/kotlin/com/vibe/build/gradle/GradleHostInstrumentedTest.kt \
        build-gradle/build.gradle.kts
git commit -m "test(build-gradle): Phase 2c acceptance for GradleHost :help

Opt-in instrumented test (vibeapp.phase2c.dev_server_url). Bootstraps
JDK + Gradle from dev server, extracts GradleHost JAR from assets,
spawns host on the bootstrapped JDK, asks it to run ':help' on a
synthetic empty project (settings.gradle.kts only), asserts
terminal BuildFinish(success=true) inside 2 minutes.

End-to-end proof of the three-process topology:
  VibeApp → GradleHostProcess → GradleHost JVM → Gradle Daemon

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: Manual e2e validation + exit log

Same pattern as Phase 2a/2b's Task 5.

### Step 1: Ensure all 3 artifacts still present on dev server

```bash
cd /Users/skykai/Documents/work/VibeApp/.worktrees/v2-arch
ls scripts/bootstrap/artifacts/
```

If any artifact is missing, rebuild via:

```bash
scripts/bootstrap/build-hello.sh --abi arm64-v8a
scripts/bootstrap/build-jdk.sh --abi arm64-v8a
scripts/bootstrap/build-gradle.sh
scripts/bootstrap/build-manifest.sh
```

### Step 2: Start dev server + run the Phase 2c test

```bash
pkill -f 'python3 -m http.server 8000' 2>/dev/null
scripts/bootstrap/dev-serve.sh &
sleep 2
./gradlew --no-daemon :build-gradle:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.vibe.build.gradle.GradleHostInstrumentedTest \
    -Pandroid.testInstrumentationRunnerArguments.vibeapp.phase2c.dev_server_url=http://localhost:8000
```

Expected: 1 test, 0 failures. Runtime: 30-90s (JDK boot + Gradle daemon fork + :help).

### Step 3: Full instrumented regression

```bash
./gradlew --no-daemon :build-runtime:connectedDebugAndroidTest \
    :build-gradle:connectedDebugAndroidTest
pkill -f 'python3 -m http.server 8000' 2>/dev/null
```

Expected: all Phase 1 + 2a + 2b + 2c tests pass. Running total:
- `:build-runtime`: 11 tests (unchanged)
- `:build-gradle`: 1 new test
- Total: **12 instrumented tests**

### Step 4: Append exit log to this plan file

```markdown
---

## Phase 2c Exit Log

**Completed:** YYYY-MM-DD
**Test device:** [e.g. Pixel_7_Pro_API_31 AVD, API 31, arm64-v8a]

**Instrumented test result:** PASS / FAIL
**GradleHost fat JAR size:** [from `ls -la gradle-host/build/libs/`]
**Tooling API version reported by Ready event:** [from test stdout]
**Time to first BuildFinish for :help on empty project:** [sec]

**Deviations from the plan (plan bugs caught during execution):**
[numbered list; may be empty]

**Follow-ups (for Phase 2d):**
[anything worth flagging]
```

### Step 5: Commit + push

```bash
git add docs/superpowers/plans/2026-04-18-v2-phase-2c-gradle-host.md
git commit -m "docs(phase 2c): record exit log

Phase 2c acceptance: GradleHost + Tooling API + JSON IPC working
end-to-end on [device]. ':help' task on a synthetic empty project
returns BuildFinish(success=true) through the full 3-process
topology (VibeApp → GradleHost → Gradle Daemon).

Phase 2d (first Compose APK build) unblocked.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
git push origin v2-arch
```

---

## Phase 2c Exit Criteria

- [ ] `:gradle-host` module exists and produces `vibeapp-gradle-host-*-all.jar` via `./gradlew :gradle-host:shadowJar`.
- [ ] The fat JAR is copied into `app/src/main/assets/vibeapp-gradle-host.jar` as part of `:app:assembleDebug`.
- [ ] IPC protocol roundtrip tests (`:gradle-host:test` + `:build-gradle:testDebugUnitTest`) pass with byte-identical JSON outputs on both sides.
- [ ] `GradleBuildServiceImpl.start()` on a real device returns a Tooling API version string matching 8.10.x.
- [ ] `GradleHostInstrumentedTest.help_task_on_empty_project_succeeds` passes: `:help` on synthetic empty project returns `BuildFinish(success=true)`.
- [ ] Full regression: Phase 1 (unit + instrumented) + 2a + 2b tests still pass.
- [ ] `:app:assembleDebug` and `:app:kspDebugKotlin` pass.
- [ ] Exit log filled in below.

When all boxes check, Phase 2c is done. Phase 2d (project template generator + `assembleDebug` producing an installable Compose APK) is unblocked.

---

## Self-Review Notes

**Spec coverage:**
- §4.1 Why GradleHost exists (ART ≠ Hotspot, OOM, LD_PRELOAD): validated — `GradleBuildServiceImpl` spawns the JVM on the bootstrapped JDK, not ART.
- §4.2 three-process topology: implemented and tested end-to-end.
- §4.3 IPC protocol JSON line format: `IpcProtocol.kt` mirrors the spec's example messages (Ping/Pong/BuildStart/BuildProgress/BuildFinish/Log/Error).
- §4.4 `init.gradle.kts`: deferred to Phase 2d (no real user projects yet; helpful only after a template generator exists).
- §4.5 smoke test: Phase 2d/2e concern.
- §4.6 `GradleBuildService` API: implemented as `start()` + `runBuild()` + `shutdown()`. `BuildInvocation` is folded into the `Flow<HostEvent>` return; caller collects/cancels rather than needing a separate handle type. May split if Phase 2d wants explicit cancellation.

**Placeholders / gaps:**
- Multi-request multiplexing on a single GradleHost not yet supported. Current impl is "one request at a time". Adequate for Phase 2c/2d; revisit in Phase 2e if daemon lifecycle patterns demand it.
- IPC protocol duplication between `:gradle-host` and `:build-gradle`: tested for byte-equivalence but long-term feels fragile. Possible future extract into a pure-kotlin multiplatform module or testFixtures when the module surface grows.
- `shutdown()` doesn't currently handle a hung host (e.g. process not responding to Shutdown). Acceptable for now; `SIGTERM` from Phase 1b is available to `NativeProcess` if a force-kill path is ever needed.
- No Hilt provider for `GradleHostExtractor` — it's `@Inject constructor` and Hilt auto-wires. Only `GradleBuildService` needs explicit `@Binds` (abstract interface).

**Type consistency:**
- `HostRequest` sealed subclass hierarchy identical in both `:gradle-host` and `:build-gradle` mirrors.
- `HostEvent.BuildFinish.failureSummary: String?` nullable same on both sides; tests assert the `null` serialization matches.
- `GradleBuildService.runBuild(...)` signature identical in interface + impl.
- Component ID `"vibeapp-gradle-host"` used consistently in `GradleHostExtractor.COMPONENT_ID`, `GradleHostProcess.cwd`, and the asset-copy task's target dir.
- Tooling API version string format (e.g. `"8.10.2"`) asserted identically in test + impl.

No "TBD", "TODO", "similar to Task N", or "add error handling" placeholders.
