# v2.0 Phase 2b: `gradle --version` Acceptance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove that VibeApp's bootstrap pipeline can download a real Gradle 8.10.2 distribution and that `java -cp gradle-launcher.jar org.gradle.launcher.GradleMain --version` returns `Gradle 8.10.2` on device. This directly validates the call pattern Phase 2c's `GradleHost` + Tooling API will use.

**Architecture:** No new modules. Gradle is bootstrapped as an ABI-independent (`common`) artifact under `$PREFIX/opt/gradle-8.10.2/`. The JDK from Phase 2a runs it via `java -cp <launcher-jar> org.gradle.launcher.GradleMain --version`. No Gradle shell wrapper involved — bypassing it sidesteps the Phase 1d-documented first-level-exec shebang issue and maps exactly to what the Tooling API does internally.

**Tech Stack:** Existing Phase 1 + Phase 2a substrate. New shell script `scripts/bootstrap/build-gradle.sh`. One new ViewModel action + button in `BuildRuntimeDebugScreen`. One new opt-in instrumented test. No new Gradle module dependencies. Artifact format is `.tar.gz` (same as Phase 2a, same extractor).

---

## Spec References

- Design doc: `docs/superpowers/specs/2026-04-18-v2-gradle-compose-arch-design.md` §4 (`:build-gradle` module + GradleHost topology). This plan is the precursor: prove the *launch pattern* works before wrapping it in `GradleHost`.
- Prior plans (completed): Phase 0, 1a–1d, 2a.

## Working Directory

**Git worktree:** `/Users/skykai/Documents/work/VibeApp/.worktrees/v2-arch`

Branch: `v2-arch`. HEAD at plan write time: `91fb4b8` (Phase 2a acceptance landed).

## File Structure

**Create:**

| File | Responsibility |
|---|---|
| `scripts/bootstrap/build-gradle.sh` | Download `gradle-8.10.2-bin.zip` from `https://services.gradle.org/distributions/`, unzip, repack as `gradle-8.10.2-common.tar.gz` |
| `build-runtime/src/androidTest/kotlin/com/vibe/build/runtime/GradleVersionInstrumentedTest.kt` | Opt-in: bootstrap gradle + JDK, exec `java -cp gradle-launcher GradleMain --version`, assert output contains `Gradle 8.10.2` |

**Modify:**

| File | Responsibility |
|---|---|
| `scripts/bootstrap/README.md` | Mention `build-gradle.sh` in the workflow section |
| `scripts/bootstrap/build-manifest.sh` | Add `gradle-8.10.2` to the `for id in ...` component loop |
| `app/src/main/kotlin/com/vibe/app/feature/bootstrap/BuildRuntimeDebugViewModel.kt` | Add a `runGradleVersion()` action |
| `app/src/main/kotlin/com/vibe/app/feature/bootstrap/BuildRuntimeDebugScreen.kt` | Add a "Run gradle --version" button |

**Do NOT touch in Phase 2b:**
- Anything under `build-gradle/`, `plugin-host/`, `build-engine/`, `build-tools/`, `shadow-runtime/`. Phase 2b is still all in `build-runtime` + `app` (for the debug UI).
- `ProcessEnvBuilder` — the Phase 2a env var set is already sufficient for launching gradle via java.
- `ZstdExtractor` — gzip + symlink handling are already correct.

## Key Constraints (ALL TASKS)

1. **Gradle's `gradle-launcher.jar` is what we exec**, not the `bin/gradle` shell script. The jar has `Class-Path` entries in its manifest that pull in the rest of Gradle's libs at startup. Its main class is `org.gradle.launcher.GradleMain`.
2. **Gradle artifact is ABI-independent**, so its manifest key is `"common"`:
   ```
   "artifacts": { "common": { "fileName": "gradle-8.10.2-common.tar.gz", ... } }
   ```
   This is exactly the pattern Phase 1c's `BootstrapEndToEndInstrumentedTest` already exercised — no changes to orchestrator.
3. **Installed layout**: `filesDir/usr/opt/gradle-8.10.2/` contains `bin/` + `lib/` + `init.d/` + license files, with NO wrapping directory. `build-gradle.sh` tar's the CONTENTS of `gradle-8.10.2/` (same "flat" pattern Phase 2a's `build-jdk.sh` uses after the plan bug fix).
4. **Gradle's classpath for `--version`**: just one jar — `lib/gradle-launcher-8.10.2.jar`. Its manifest declares transitive classpath entries via `Class-Path:`, which the JVM resolves automatically. **Do NOT add `-cp lib/*`** — it picks up Gradle's bundled native-launcher JARs that break on ART.
5. **`gradle --version` needs a writable `GRADLE_USER_HOME`**. `ProcessEnvBuilder` already emits `GRADLE_USER_HOME=filesDir/.gradle`. The directory may need to exist first; `RuntimeBootstrapper.fs.ensureDirectories()` does NOT create it. The test or viewmodel must `mkdir`.
6. **Gradle may try to start a daemon**. For `--version` this is unnecessary. Add `--no-daemon` to the args.
7. **No new Termux lib deps for Gradle**. Gradle is pure JVM code; its native-library helpers (`gradle-native-*.jar`) are for Linux/macOS/Windows host toolchains, not Android. Our Phase 2a JDK with libc++_shared / libandroid-shmem / etc. is enough.

---

## Task 1: `scripts/bootstrap/build-gradle.sh`

**Files:**
- Create: `scripts/bootstrap/build-gradle.sh` (executable)

### Step 1: Create the script

```bash
#!/usr/bin/env bash
#
# Downloads Gradle's official -bin distribution and repacks as
# gradle-8.10.2-common.tar.gz for VibeApp's bootstrap. ABI-independent:
# one artifact covers all devices.

set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
artifacts_dir="$script_dir/artifacts"

gradle_version="${GRADLE_VERSION:-8.10.2}"
gradle_dist_url="${GRADLE_DIST_URL:-https://services.gradle.org/distributions/gradle-${gradle_version}-bin.zip}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --gradle-version) gradle_version="$2"; shift 2;;
        --dist-url) gradle_dist_url="$2"; shift 2;;
        -h|--help)
            cat <<EOF
Usage: $0 [--gradle-version VER] [--dist-url URL]

Downloads the Gradle '-bin' distribution zip and repacks as
scripts/bootstrap/artifacts/gradle-<version>-common.tar.gz.

Defaults:
  gradle-version: $gradle_version
  dist-url:       $gradle_dist_url
EOF
            exit 0;;
        *) echo "Unknown arg: $1" >&2; exit 2;;
    esac
done

mkdir -p "$artifacts_dir"
staging="$(mktemp -d -t vibeapp-gradle.XXXXXXXX)"
trap 'rm -rf "$staging"' EXIT

echo "Downloading $gradle_dist_url ..."
curl -fSL --retry 3 -o "$staging/gradle.zip" "$gradle_dist_url"

echo "Unzipping ..."
unzip -q "$staging/gradle.zip" -d "$staging/unpack"

gradle_src="$staging/unpack/gradle-$gradle_version"
[[ -d "$gradle_src" ]] || {
    echo "Expected $gradle_src not found in the unzipped dist." >&2
    find "$staging/unpack" -maxdepth 2 -type d | head
    exit 2
}

# Strip bits not needed at runtime. init.d/ and lib/plugins/ are empty
# until a user stages custom init scripts / plugins, so harmless to keep.
# Remove the shell wrappers — we launch via `java -cp` in Phase 2b+,
# and gradle-<version>/bin/gradle's '#!/usr/bin/env sh' shebang would
# hit the Phase 1d first-exec issue anyway.
rm -rf "$gradle_src/bin"

out="$artifacts_dir/gradle-${gradle_version}-common.tar.gz"
# Tar the CONTENTS at root (not wrapped in gradle-<version>/) so
# post-extraction layout is $PREFIX/opt/gradle-8.10.2/lib/... directly.
(cd "$gradle_src" && tar -cf - .) | gzip -9 -c > "$out"
sha=$(shasum -a 256 "$out" | awk '{print $1}')
size=$(stat -f %z "$out" 2>/dev/null || stat -c %s "$out")
echo "$out"
echo "  size=$size sha256=$sha"
```

### Step 2: `chmod +x` + syntax check

```bash
cd /Users/skykai/Documents/work/VibeApp/.worktrees/v2-arch
chmod +x scripts/bootstrap/build-gradle.sh
bash -n scripts/bootstrap/build-gradle.sh
```

Expected: no output.

### Step 3: Commit

```bash
git add scripts/bootstrap/build-gradle.sh
git commit -m "feat(scripts): build-gradle.sh repacks Gradle 8.10.2 bin dist

Downloads services.gradle.org's official gradle-<ver>-bin.zip,
strips the bin/ shell wrappers (Phase 2 launches Gradle via
'java -cp gradle-launcher.jar org.gradle.launcher.GradleMain'
— the shell wrappers' '#!/usr/bin/env sh' shebangs aren't
invoke-able from our first-level ProcessLauncher.launch anyway),
and repacks the remaining lib/init.d tree as
gradle-<ver>-common.tar.gz with content at tarball root (matching
the Phase 2a JDK tar layout convention).

Phase 2b Task 1.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Extend `build-manifest.sh` to enumerate the gradle component

**Files:**
- Modify: `scripts/bootstrap/build-manifest.sh`

### Step 1: Add `gradle-8.10.2` to the component loop

Read the existing script. Find the line:

```bash
for id in "hello" "jdk-17.0.13"; do
```

Replace with:

```bash
for id in "hello" "jdk-17.0.13" "gradle-8.10.2"; do
```

And below, the per-id `version_field` assignment block:

```bash
    version_field=""
    if [[ "$id" == "jdk-17.0.13" ]]; then version_field="17.0.13"; fi
    if [[ "$id" == "hello"       ]]; then version_field="1.0"; fi
```

Add a gradle entry:

```bash
    version_field=""
    if [[ "$id" == "jdk-17.0.13"    ]]; then version_field="17.0.13"; fi
    if [[ "$id" == "hello"          ]]; then version_field="1.0"; fi
    if [[ "$id" == "gradle-8.10.2"  ]]; then version_field="8.10.2"; fi
```

### Step 2: Syntax check

```bash
bash -n scripts/bootstrap/build-manifest.sh
```

Expected: no output.

### Step 3: Update the `scripts/bootstrap/README.md` workflow section

Find the section showing build commands. Before `./build-manifest.sh`, add:

```bash
./build-gradle.sh       # gradle-8.10.2-common.tar.gz, ~100MB
```

And in the "Output layout" list, add:

```
└── gradle-8.10.2-common.tar.gz
```

### Step 4: Commit

```bash
git add scripts/bootstrap/build-manifest.sh scripts/bootstrap/README.md
git commit -m "build(scripts): add gradle-8.10.2 to manifest composer

build-manifest.sh now enumerates 3 components (hello, jdk-17.0.13,
gradle-8.10.2). The gradle artifact is ABI-independent ('common'
arch key), matching what the orchestrator already handles.

README updated with the new build step.

Phase 2b Task 2.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: Debug UI — "Run gradle --version"

**Files:**
- Modify: `app/src/main/kotlin/com/vibe/app/feature/bootstrap/BuildRuntimeDebugViewModel.kt`
- Modify: `app/src/main/kotlin/com/vibe/app/feature/bootstrap/BuildRuntimeDebugScreen.kt`

### Step 1: Add `runGradleVersion()` to the ViewModel

Read the existing ViewModel. Add this method after `runJavaVersion()`:

```kotlin
    fun runGradleVersion() {
        viewModelScope.launch {
            _uiState.update { it.copy(launchRunning = true, launchLog = "") }
            val javaBinary = File(fs.componentInstallDir("jdk-17.0.13"), "bin/java")
            val launcherJar = File(
                fs.componentInstallDir("gradle-8.10.2"),
                "lib/gradle-launcher-8.10.2.jar",
            )
            val log = StringBuilder()
            if (!javaBinary.isFile) {
                log.append("[error] $javaBinary not found — run Trigger bootstrap first")
            } else if (!launcherJar.isFile) {
                log.append("[error] $launcherJar not found — run Trigger bootstrap first")
            } else {
                // Gradle wants GRADLE_USER_HOME to exist.
                val gradleUserHome = File(fs.usrRoot.parentFile, ".gradle")
                gradleUserHome.mkdirs()
                try {
                    val proc = launcher.launch(
                        executable = javaBinary.absolutePath,
                        args = listOf(
                            "-cp",
                            launcherJar.absolutePath,
                            "org.gradle.launcher.GradleMain",
                            "--version",
                            "--no-daemon",
                        ),
                        cwd = cacheDir,
                    )
                    proc.events.collect { appendEvent(log, it) }
                } catch (t: Throwable) {
                    log.append("[error] ${t.message}")
                }
            }
            _uiState.update { it.copy(launchRunning = false, launchLog = log.toString()) }
        }
    }
```

### Step 2: Add the Compose button

Read the existing screen. Locate the "Run java -version" Button. Insert this Button immediately after it:

```kotlin
            Button(
                onClick = viewModel::runGradleVersion,
                enabled = !ui.launchRunning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Run gradle --version")
            }
```

### Step 3: Verify the graph compiles

```bash
./gradlew --no-daemon :app:kspDebugKotlin :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

### Step 4: Commit

```bash
git add app/src/main/kotlin/com/vibe/app/feature/bootstrap/
git commit -m "feat(debug-ui): 'Run gradle --version' button

Adds a third launch action next to 'Run hello' / 'Run java -version'
in BuildRuntimeDebugScreen. Invokes the bootstrapped JDK's java binary
with '-cp gradle-launcher-8.10.2.jar org.gradle.launcher.GradleMain
--version --no-daemon'. GRADLE_USER_HOME dir is mkdirs'd on demand
since RuntimeBootstrapper doesn't create it.

Phase 2b Task 3.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Opt-in `GradleVersionInstrumentedTest`

**Files:**
- Create: `build-runtime/src/androidTest/kotlin/com/vibe/build/runtime/GradleVersionInstrumentedTest.kt`

### Step 1: Create the test file

```kotlin
package com.vibe.build.runtime

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
import com.vibe.build.runtime.process.ProcessEvent
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.fold
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
 * Phase 2b acceptance. Opt-in via
 * `-Pandroid.testInstrumentationRunnerArguments.vibeapp.phase2b.dev_server_url=http://localhost:8000`.
 * Requires the dev server to be serving a manifest that lists BOTH
 * the `jdk-17.0.13` AND `gradle-8.10.2` components (produce via
 * `scripts/bootstrap/build-{jdk,gradle}.sh` + `build-manifest.sh`).
 *
 * Skipped when the runner argument is absent — CI stays green.
 */
@RunWith(AndroidJUnit4::class)
class GradleVersionInstrumentedTest {

    private lateinit var ctx: Context
    private lateinit var scratchDir: File
    private lateinit var fs: BootstrapFileSystem
    private lateinit var launcher: NativeProcessLauncher
    private lateinit var bootstrapper: RuntimeBootstrapper

    private fun devServerUrlOrSkip(): String {
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("vibeapp.phase2b.dev_server_url")
        assumeTrue(
            "vibeapp.phase2b.dev_server_url not provided; skipping Phase 2b acceptance",
            !url.isNullOrBlank(),
        )
        return url!!.trimEnd('/')
    }

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        scratchDir = File(ctx.cacheDir, "phase2b-${System.nanoTime()}")
        require(scratchDir.mkdirs())

        fs = BootstrapFileSystem(filesDir = scratchDir)
        fs.ensureDirectories()

        val preloadLib = PreloadLibLocator(File(ctx.applicationInfo.nativeLibraryDir))
        launcher = NativeProcessLauncher(ProcessEnvBuilder(fs, preloadLib))
    }

    @After
    fun tearDown() {
        scratchDir.deleteRecursively()
    }

    private fun buildBootstrapper(devBaseUrl: String): RuntimeBootstrapper {
        val store = InMemoryBootstrapStateStore()
        val mirrors = MirrorSelector(
            primaryBase = devBaseUrl,
            fallbackBase = "https://unused.test",
        )
        // Same dev pubkey as Phase 2a; see docs/bootstrap/dev-keypair-setup.md.
        val devPubkeyHex = "5ce0c624f59a72ee8eb6f72c25ad905a27afcd0392998f353ef86f3247725f40"
        val signature = ManifestSignature(publicKeyHex = devPubkeyHex)
        return RuntimeBootstrapper(
            fs = fs,
            store = store,
            parser = ManifestParser(),
            signature = signature,
            mirrors = mirrors,
            downloader = BootstrapDownloader(),
            extractor = ZstdExtractor(),
            abi = Abi.pickPreferred(Build.SUPPORTED_ABIS) ?: Abi.ARM64,
            fetcher = ManifestFetcher(mirrors),
            parsedManifestOverride = null,
        )
    }

    @Test
    fun gradle_version_after_bootstrap() = runBlocking {
        val devUrl = devServerUrlOrSkip()
        bootstrapper = buildBootstrapper(devUrl)

        val seen = mutableListOf<BootstrapState>()
        bootstrapper.bootstrap(manifestUrl = "manifest.json") { seen += it }
        val terminal = seen.last()
        assertTrue("expected Ready, got $terminal", terminal is BootstrapState.Ready)

        val javaBinary = File(fs.componentInstallDir("jdk-17.0.13"), "bin/java")
        val launcherJar = File(
            fs.componentInstallDir("gradle-8.10.2"),
            "lib/gradle-launcher-8.10.2.jar",
        )
        assertTrue("java missing: $javaBinary", javaBinary.isFile)
        assertTrue("gradle launcher missing: $launcherJar", launcherJar.isFile)

        // GRADLE_USER_HOME needs to exist for gradle --version.
        File(fs.usrRoot.parentFile, ".gradle").mkdirs()

        val process = launcher.launch(
            executable = javaBinary.absolutePath,
            args = listOf(
                "-cp",
                launcherJar.absolutePath,
                "org.gradle.launcher.GradleMain",
                "--version",
                "--no-daemon",
            ),
            cwd = scratchDir,
        )
        // Gradle's first startup on an unfamiliar device (class loading,
        // native image caches) can take >30s; allow 60s.
        val events = withTimeout(60_000) { process.events.toList() }

        val stdout = events.filterIsInstance<ProcessEvent.Stdout>()
            .fold(ByteArray(0)) { acc, e -> acc + e.bytes }
        val stderr = events.filterIsInstance<ProcessEvent.Stderr>()
            .fold(ByteArray(0)) { acc, e -> acc + e.bytes }
        val exit = events.filterIsInstance<ProcessEvent.Exited>().first()
        val combined = String(stdout, Charsets.UTF_8) + String(stderr, Charsets.UTF_8)

        assertEquals(
            "gradle --version exit=$exit combined=$combined",
            0, exit.code,
        )
        assertTrue(
            "expected 'Gradle 8.10.2' in output:\n$combined",
            combined.contains("Gradle 8.10.2"),
        )
    }
}
```

### Step 2: Compile-only sanity check

```bash
./gradlew --no-daemon :build-runtime:compileDebugAndroidTestKotlin
```

Expected: BUILD SUCCESSFUL.

### Step 3: Commit (test run deferred to Task 5 manual validation)

```bash
git add build-runtime/src/androidTest/kotlin/com/vibe/build/runtime/GradleVersionInstrumentedTest.kt
git commit -m "test(build-runtime): Phase 2b acceptance for gradle --version

Opt-in instrumented test, skipped when
vibeapp.phase2b.dev_server_url is absent. Bootstraps JDK +
Gradle dist from the dev server, then execs
  java -cp gradle-launcher-8.10.2.jar
       org.gradle.launcher.GradleMain --version --no-daemon
and asserts 'Gradle 8.10.2' in combined stdout+stderr + exit 0.

This is the exact launch pattern Phase 2c's GradleHost will use
internally — we validate the plumbing end-to-end before wrapping
it in Tooling API code.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: Manual e2e validation + Phase 2b exit log

Same shape as Phase 2a Task 5 — run the pipeline, capture output, append an exit log to this plan file. Reuses the dev server setup.

### Step 1: Produce the new gradle artifact

```bash
cd /Users/skykai/Documents/work/VibeApp/.worktrees/v2-arch
scripts/bootstrap/build-gradle.sh
```

Expected: `scripts/bootstrap/artifacts/gradle-8.10.2-common.tar.gz` (~100MB).

### Step 2: Rebuild the manifest (now 3 components)

```bash
# Ensure JDK artifact from Phase 2a is still present; rebuild if absent.
ls scripts/bootstrap/artifacts/jdk-17.0.13-*.tar.gz || \
    scripts/bootstrap/build-jdk.sh --abi arm64-v8a

scripts/bootstrap/build-manifest.sh
```

Expected: `manifest.json` now lists 3 components (hello, jdk, gradle), and `manifest.json.sig` is regenerated.

### Step 3: Start dev server + verify

```bash
# Kill any old server listening on 8000.
pkill -f 'python3 -m http.server 8000' 2>/dev/null || true
scripts/bootstrap/dev-serve.sh &
sleep 1
curl -s --noproxy '*' http://127.0.0.1:8000/manifest.json | grep gradle-8.10.2
```

Expected: one line mentioning `gradle-8.10.2`.

### Step 4: Run the Phase 2b instrumented test

```bash
./gradlew --no-daemon :build-runtime:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.vibe.build.runtime.GradleVersionInstrumentedTest \
    -Pandroid.testInstrumentationRunnerArguments.vibeapp.phase2b.dev_server_url=http://localhost:8000
```

Expected: 1 test, 0 failures. Runtime: ~30-90s (JDK + Gradle startup on emulator is not fast).

If the test fails, read logcat:

```bash
/Users/skykai/Library/Android/sdk/platform-tools/adb logcat -d -b all -t 3000 \
    | grep -iE "FATAL|JNI|dlopen|gradle|exec_no_trans" | tail -30
```

Most likely failure modes, in order of likelihood:

1. **`Could not determine the dependencies of null`-style Gradle error**: `GRADLE_USER_HOME` dir not created or not writable. Check the `mkdirs()` call.
2. **`UnsatisfiedLinkError` for a gradle-bundled native lib**: Gradle ships `gradle-native-*.jar` with Linux/macOS/Windows .dylib/.dll/.so; they fail to load on Android. Should be lazy-loaded only on specific features (not `--version`). If `--version` triggers one, we'd need to find which and skip it. Capture the exact library name from the stack trace.
3. **`NoClassDefFoundError: sun.nio.fs.DefaultFileSystemProvider` or similar**: JDK's module resolution differs between Linux and Android. Usually resolved by not setting `JAVA_TOOL_OPTIONS` or similar env vars we don't set.
4. **Gradle hangs for >60s**: first-run class loading. Extend timeout locally to diagnose, then report.

### Step 5: Run the full instrumented suite to confirm no Phase 1/2a regression

```bash
./gradlew --no-daemon :build-runtime:connectedDebugAndroidTest 2>&1 | tail -20
```

Expected: 10 (Phase 1+2a) + 1 (this phase) = **11 instrumented tests**, 0 failures. Other tests that require opt-in runner arguments should report as "ignored" / "skipped" cleanly.

### Step 6: Append the exit log

Append to this plan file (below the Self-Review Notes section):

```markdown
---

## Phase 2b Exit Log

**Completed:** YYYY-MM-DD

**Test device:** [e.g. Pixel_7_Pro_API_31 AVD, API 31, arm64-v8a]

**Instrumented test result:** PASS / FAIL — 1 test, <time>s
**`gradle --version` stdout+stderr:**
```
[paste here, e.g.:
Welcome to Gradle 8.10.2!
...
Gradle 8.10.2
...
Kotlin:       1.9.24
...
```

**Deviations from the plan (plan bugs caught during execution):**
[Numbered list — may be empty]

**Follow-ups:**
[Anything to track before Phase 2c]
```

### Step 7: Commit the exit log

```bash
git add docs/superpowers/plans/2026-04-18-v2-phase-2b-gradle-version.md
git commit -m "docs(phase 2b): record exit log

Phase 2b acceptance: Gradle 8.10.2 runs on [device] via
'java -cp gradle-launcher.jar GradleMain --version'. Exit 0,
'Gradle 8.10.2' present in output.

Phase 2c (GradleHost + Tooling API) is now unblocked: we know
the launch pattern works end-to-end with the real bootstrapped
JDK + Gradle.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Phase 2b Exit Criteria

- [ ] `scripts/bootstrap/build-gradle.sh` produces a working `gradle-8.10.2-common.tar.gz` from Gradle's official bin dist.
- [ ] `scripts/bootstrap/build-manifest.sh` composes a 3-component manifest (hello, jdk, gradle) and signs it with the dev key.
- [ ] `BuildRuntimeDebugScreen` has a "Run gradle --version" button behind the debug-mode flag.
- [ ] `GradleVersionInstrumentedTest` compiles, skips cleanly when no dev URL, passes with `Gradle 8.10.2` in output when a dev server is running.
- [ ] 60 unit tests + 11 instrumented tests pass (10 prior + 1 new).
- [ ] `:app:assembleDebug` still succeeds.
- [ ] Exit log section filled in with observed stdout.

When all checks are true, Phase 2b is complete. Phase 2c (GradleHost + IPC + Tooling API) is unblocked — we now know `java -cp gradle-launcher.jar` is a working launch pattern on the bootstrapped stack.

---

## Self-Review Notes

**Spec coverage:**
- Design doc §4 "`:build-gradle` module": Phase 2c will implement; Phase 2b proves the primitive `java -cp gradle-launcher.jar GradleMain` launch pattern on which that module will be built.
- §3.8 Gradle daemon management: deferred to Phase 2c. `--no-daemon` keeps this phase simple.
- §4.2 three-layer process topology: Phase 2b is "two layers" (VibeApp → java+gradle). The GradleHost middle process (spawns in Phase 2c) also invokes gradle via the Tooling API, which internally does the same `java -cp` that we prove works here.

**Placeholders / gaps:**
- Gradle shell wrappers (`bin/gradle`) are stripped from the artifact. If a future phase needs them (e.g., to invoke `gradlew` from a generated user project), we'd restore them and handle the shebang either via the fully-loaded java descendant (libtermux-exec.so works there) or via explicit `toybox sh <script>` wrapping.
- `gradle-native-*.jar` may produce warnings at startup about missing native libs; as long as `--version` succeeds, these are cosmetic. Phase 2c's acceptance tests should scan logs for ERROR-level warnings.
- No coverage of Gradle's daemon mode in Phase 2b. First real daemon usage is Phase 2c.

**Type consistency:**
- Component `id = "gradle-8.10.2"` consistent across `build-manifest.sh`, `BuildRuntimeDebugViewModel.runGradleVersion` (via `fs.componentInstallDir("gradle-8.10.2")`), and the instrumented test.
- Launcher jar filename `gradle-launcher-8.10.2.jar` matches what Gradle's bin dist places under `lib/`. If the Gradle version in `--gradle-version` is overridden, the Kotlin hardcoded path will need updating too — documented implicitly by sharing the version constant.
- Dev pubkey hex identical to Phase 2a's inline value.

No "TBD", "TODO", "similar to Task N", or "add error handling" placeholders.

---

## Phase 2b Exit Log

**Completed:** 2026-04-18

**Test device:** Pixel_7_Pro_API_31 AVD (Android 12, arm64-v8a)

**Artifacts produced:**
- `gradle-8.10.2-common.tar.gz` — 136.7 MB (compressed from ~195 MB unpacked distribution minus `bin/`)
- Manifest signed with same dev Ed25519 key as Phase 2a (`~/.vibeapp/dev-bootstrap-privseed.hex`).

**Instrumented test result:** PASS — 1 test, 6.27 s — `gradle_version_after_bootstrap`.

Full instrumented suite: 11 tests, 0 failures (10 prior + 1 new). Unit suite: 60 tests, 0 failures. `:app:assembleDebug` green.

**Exec pattern that worked:**
```
$PREFIX/opt/jdk-17.0.13/bin/java \
    -cp $PREFIX/opt/gradle-8.10.2/lib/gradle-launcher-8.10.2.jar \
    org.gradle.launcher.GradleMain \
    --version --no-daemon
```
Exit 0. Combined stdout+stderr contained `Gradle 8.10.2` (test assertion passed).

**Deviations from the plan:** None. The plan's exact commands, env setup, and assertions all worked on first attempt — Phase 2a's substrate (targetSdk 28, bundled Termux runtime libs, LD_LIBRARY_PATH spanning JDK lib dirs, symlink-aware extractor) carried Gradle through without new workarounds.

**Follow-ups (for Phase 2c):**
- Gradle daemon lifecycle: `--no-daemon` bypassed the daemon fork in 2b; 2c should exercise a proper `--daemon` flow with idle timeout.
- Warnings/diagnostics: Gradle prints some lifecycle messages to stderr on startup. None errored; 2c's diagnostic pipeline should sort/filter these by severity.
- Build classpath: `--version` only loads `gradle-launcher-*.jar`. Real build invocation loads the full Gradle runtime plus project-specific plugins; Tooling API is the idiomatic way to drive this and will be embedded in GradleHost.

Phase 2b complete. Phase 2c (`GradleHost` + Tooling API + structured build events) is unblocked.
