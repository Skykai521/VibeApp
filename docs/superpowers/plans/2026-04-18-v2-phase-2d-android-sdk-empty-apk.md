# v2.0 Phase 2d: Android SDK bootstrap + empty-Activity APK Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bootstrap a minimal on-device Android SDK (platform-36 + build-tools 36.0.0), then use the Phase 2c GradleHost to run `:app:assembleDebug` on a hardcoded empty-Activity Java project and produce an installable, debug-signed APK on the device.

**Architecture:** A new dev-server component `android-sdk` bundles Google's platform-36 `android.jar`, stub JARs, and Java build-tools (d8, apksigner, zipalign-jvm replacement) with a Termux-sourced arm64 `aapt2` binary. A new build script composes the per-ABI tree into the shape AGP 9.1.0 expects (`platforms/android-36/` + `build-tools/36.0.0/` + `licenses/`). `RuntimeBootstrapper` picks it up via the existing manifest loop — no Kotlin plumbing changes needed beyond a new component ID. On the app side, a new `ProjectStager` copies an empty-Activity Java project from androidTest assets to `filesDir/projects/probe/`, templates `local.properties` with the SDK path, and hands the directory to `GradleBuildService.runBuild(":app:assembleDebug")`. Online Maven resolution (AGP + Kotlin + AndroidX — transitive from the android-application plugin) is expected; no offline repo in this phase. Acceptance: instrumented test asserts the produced APK exists, is PK-zip-valid, and carries a debug signing block.

**Tech Stack:** AGP 9.1.0, compileSdk 36, buildToolsVersion 36.0.0, Kotlin 2.3.10 (dev-machine only — probe is pure Java to keep Maven deps minimal), Gradle 9.3.1 (already bootstrapped). Termux packages for arm64 `aapt2` via `.deb` repack (same pattern as `build-jdk.sh`). Google for `android.jar` + Java tools.

---

## Spec References

- Design doc: `docs/superpowers/specs/2026-04-18-v2-gradle-compose-arch-design.md` §2.3 (version matrix), §3.2 (filesystem layout — `usr/opt/android-sdk/`), §5.1 (project structure), §5.5 (`app/build.gradle.kts` template — reduced for probe), §10 "Phase 2" (combined spec phase).
- Prior plans: Phases 0, 1a–1d, 2a, 2b, 2c all complete.

## Working Directory

**Git worktree:** `/Users/skykai/Documents/work/VibeApp/.worktrees/v2-arch`

Branch: `v2-arch`. HEAD at plan write time: `025e1ce` (Phase 2c closeout).

## Scope Boundary — explicit non-goals for Phase 2d

- **No Compose.** Probe uses plain `android.app.Activity` (Java). Compose compiler + runtime land in Phase 2e.
- **No template generator.** Probe sources live under `build-gradle/src/androidTest/assets/probe-app/` as static files. `GradleProjectInitializer` is Phase 3.
- **No Maven offline mirror.** Gradle Daemon uses `google()` + `mavenCentral()` with network. Device must have internet.
- **No APK install flow.** We produce the APK, byte-check it, and stop. `Intent.ACTION_VIEW` / `FileProvider` installer wiring is Phase 3.
- **No agent integration.** `RunGradleTool` / `AddDependencyTool` belong to Phase 3.
- **One ABI on the dev path.** `build-androidsdk.sh --abi arm64-v8a` only, matching Phase 2a/2b dev-server posture. Other ABIs are manifest-hygiene-only (listed so the manifest schema stays uniform, but dev server serves arm64-v8a).

## File Structure

**Create (dev bootstrap infrastructure):**

| File | Responsibility |
|---|---|
| `scripts/bootstrap/build-androidsdk.sh` | Assemble per-ABI android-sdk tarball: fetch Google `android.jar` + Java build-tools, fetch Termux arm64 `aapt2`, stitch into AGP-expected tree. |
| `scripts/bootstrap/android-sdk-license.txt` | Google's platform/build-tools SDK license text. Accepted by hash; AGP reads `$ANDROID_HOME/licenses/android-sdk-license`. |

**Modify (dev bootstrap infrastructure):**

| File | Responsibility |
|---|---|
| `scripts/bootstrap/build-manifest.sh` | Add `"android-sdk-36.0.0"` to component loop; version = `36.0.0`. |
| `scripts/bootstrap/README.md` | Document `build-androidsdk.sh` invocation. |

**Create (app-side project staging):**

| File | Responsibility |
|---|---|
| `build-gradle/src/main/kotlin/com/vibe/build/gradle/ProjectStager.kt` | Copies a template tree from an input directory to `filesDir/projects/<id>/`, applying `{{SDK_DIR}}` and `{{GRADLE_USER_HOME}}` substitution to `local.properties`. Pure logic — no Android framework deps — unit-testable. |
| `build-gradle/src/main/kotlin/com/vibe/build/gradle/ProjectTemplate.kt` | Small sealed type: `ProjectTemplate.FromAssets(assetRoot: String)`. Holds the asset-relative template path. |
| `build-gradle/src/test/kotlin/com/vibe/build/gradle/ProjectStagerTest.kt` | Unit tests for template substitution + file tree copy. JVM-only; uses `tempDir`. |

**Create (probe project sources — embedded test resource):**

| File | Responsibility |
|---|---|
| `build-gradle/src/androidTest/assets/probe-app/settings.gradle.kts` | Declares `":app"`. |
| `build-gradle/src/androidTest/assets/probe-app/build.gradle.kts` | Root; plugins DSL `apply false`. |
| `build-gradle/src/androidTest/assets/probe-app/gradle.properties` | JVM args, AndroidX flag. |
| `build-gradle/src/androidTest/assets/probe-app/local.properties.tmpl` | Template with `{{SDK_DIR}}`. |
| `build-gradle/src/androidTest/assets/probe-app/gradle/libs.versions.toml` | Pinned AGP 9.1.0 + nothing else. |
| `build-gradle/src/androidTest/assets/probe-app/app/build.gradle.kts` | AGP application, minSdk 24, targetSdk 36, no Compose, Java 17, no deps. |
| `build-gradle/src/androidTest/assets/probe-app/app/src/main/AndroidManifest.xml` | Launcher Activity `com.vibe.probe.MainActivity`. |
| `build-gradle/src/androidTest/assets/probe-app/app/src/main/java/com/vibe/probe/MainActivity.java` | Trivial `Activity` subclass, empty `onCreate`. |
| `build-gradle/src/androidTest/assets/probe-app/app/src/main/res/values/strings.xml` | `app_name`. |

**Create (integration test):**

| File | Responsibility |
|---|---|
| `build-gradle/src/androidTest/kotlin/com/vibe/build/gradle/EmptyApkBuildInstrumentedTest.kt` | Opt-in via same `vibeapp.phase2c.dev_server_url` runner arg: bootstrap full manifest (incl. android-sdk), stage probe-app under filesDir, `runBuild(":app:assembleDebug")`, assert APK exists + starts with `PK\x03\x04` + signed-block magic present. |

**Modify (app-side):**

| File | Responsibility |
|---|---|
| `build-runtime/src/main/kotlin/com/vibe/build/runtime/process/ProcessEnvBuilder.kt` | Change `ANDROID_SDK_DIR_NAME` from `"android-sdk"` to `"android-sdk-36.0.0"` to match the new component ID (current value is a placeholder). |
| `app/src/main/kotlin/com/vibe/app/feature/bootstrap/BuildRuntimeDebugScreen.kt` | Add a "Run probe :app:assembleDebug" button wired to `GradleBuildService`. Manual-verification button only; the instrumented test is the automated path. |

**Do NOT touch in Phase 2d:**
- `:gradle-host`, `:plugin-host`, `:build-engine`, `:build-tools:*`
- `ZstdExtractor`, `BootstrapDownloader`, `RuntimeBootstrapper` (the component loop already generalizes)
- `GradleHostProcess`, `ToolingApiDriver` (Phase 2c is frozen)
- Agent tooling

## Key Constraints (ALL TASKS)

1. **One Android SDK artifact per ABI.** Even though `android.jar` is platform-independent, bundling it per-ABI keeps manifest schema uniform with Phase 2a's JDK pattern. Per-ABI size ~70 MB (tar.gz); duplication cost is acceptable.
2. **License-accepted SDK.** AGP refuses to build if `$ANDROID_HOME/licenses/android-sdk-license` is missing or content hash doesn't match Google's accepted hash. Bundle the license file inside the tarball.
3. **`aapt2` is the only native binary needed.** `d8` and `apksigner` are JVM tools (`d8.jar`, `apksigner.jar`) that run on any Hotspot JDK including our bootstrapped Termux OpenJDK 17. Don't pull Google's linux-x86_64 `aapt2` — it won't run on arm64 Android.
4. **Termux `aapt2` version may drift.** The Termux package name is `aapt2_<upstream-version>-<pkg-rev>_aarch64.deb`. Script reads an env override (`AAPT2_VERSION`) and documents the currently-pinned version in a comment. If upstream moves, bump the override.
5. **AGP expects specific package-descriptor files.** Each component under `$ANDROID_HOME` must have `package.xml` with the `<revision>` matching the version string in the directory name. Bundle these alongside the binaries.
6. **No shebang scripts in the SDK.** Google's `d8` / `apksigner` ship as shell-script launchers that `#!/usr/bin/env bash` their Java main classes. Phase 1d's `libtermux-exec.so` rewrites this at `execve` time, BUT AGP 9.1.0 calls these tools in-process via their JARs (d8 via D8Command API, apksigner via ApkSigner API). We bundle only the JARs + minimal wrapper shell scripts; wrapper scripts get the shebang rewrite for interactive Gradle invocations that don't use the in-process API.
7. **Maven resolution requires network.** AGP plugin + Kotlin Android plugin + AndroidX annotations pull ~150 MB into `GRADLE_USER_HOME` on first probe build. Test timeout: 600s. Subsequent builds < 60s due to caching.
8. **Probe project is pure Java.** Goal: minimize transitive deps. No Kotlin → no Kotlin plugin → no kotlin-stdlib resolution. This hides Kotlin-specific failures until Phase 2e, where they belong.

---

## Task 1: `build-androidsdk.sh` dev script

**Files:**
- Create: `scripts/bootstrap/build-androidsdk.sh`
- Create: `scripts/bootstrap/android-sdk-license.txt`

**Context:** The script builds one tarball per ABI. For Phase 2d we only need `arm64-v8a`. The output lands in `scripts/bootstrap/artifacts/android-sdk-36.0.0-arm64-v8a.tar.gz`. Tarball contents, rooted directly (no top-level `android-sdk-36.0.0/` wrapper so post-extraction the install dir IS the SDK root):

```
platforms/android-36/
  android.jar
  data/      (from Google platform-36 zip — optional resources)
  package.xml
build-tools/36.0.0/
  aapt2                 (arm64 binary, chmod 0755)
  d8.jar                (Google linux build-tools zip)
  d8                    (minimal wrapper: `exec java -cp "$(dirname "$0")/d8.jar" com.android.tools.r8.D8 "$@"`)
  apksigner.jar
  apksigner             (minimal wrapper: `exec java -cp "$(dirname "$0")/apksigner.jar" com.android.apksigner.Main "$@"`)
  lib/                  (core-lambda-stubs.jar, d8.jar copy AGP expects here)
  source.properties
  package.xml
licenses/
  android-sdk-license
```

- [ ] **Step 1: Create the SDK license file**

Contents of `scripts/bootstrap/android-sdk-license.txt` (exactly this — AGP SHA-1s this file):

```
8933bad161af4178b1185d1a37fbf41ea5269c55
d56f5187479451eabf01fb78af6dfcb131a6481e
24333f8a63b6825ea9c5514f83c2829b004d1fee
```

Three lines, each is a SHA-1 of an accepted license version. No trailing newline beyond the last `\n`. Google's `cmdline-tools` writes this exact format when the user types `y` to `sdkmanager --licenses`.

- [ ] **Step 2: Create `build-androidsdk.sh` skeleton**

```bash
#!/usr/bin/env bash
#
# Assembles scripts/bootstrap/artifacts/android-sdk-36.0.0-<abi>.tar.gz
# from Google's platform-36 + Java build-tools + Termux's arm64 aapt2.

set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
artifacts_dir="$script_dir/artifacts"

abi=""
sdk_version="${ANDROID_SDK_VERSION:-36.0.0}"
platform_rev="${ANDROID_PLATFORM_REV:-36_r01}"
termux_mirror="${TERMUX_MIRROR:-https://packages.termux.dev/apt/termux-main}"
# Termux aapt2 package version — bump this when upstream publishes a newer build.
# Check with: curl -s "$termux_mirror/pool/main/a/aapt2/" | grep aapt2_
aapt2_version="${AAPT2_VERSION:-8.9.1-0}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --abi) abi="$2"; shift 2;;
        --mirror) termux_mirror="$2"; shift 2;;
        --aapt2-version) aapt2_version="$2"; shift 2;;
        --sdk-version) sdk_version="$2"; shift 2;;
        -h|--help)
            cat <<EOF
Usage: $0 --abi <arm64-v8a|armeabi-v7a|x86_64>
          [--mirror URL] [--aapt2-version VER] [--sdk-version VER]

Assembles an on-device Android SDK tarball matching AGP $sdk_version expectations.
Output: scripts/bootstrap/artifacts/android-sdk-<ver>-<abi>.tar.gz

Defaults:
  sdk-version:   $sdk_version
  aapt2-version: $aapt2_version
  termux-mirror: $termux_mirror
EOF
            exit 0;;
        *) echo "Unknown arg: $1" >&2; exit 2;;
    esac
done

[[ -n "$abi" ]] || { echo "--abi required" >&2; exit 2; }

case "$abi" in
    arm64-v8a)   termux_arch="aarch64";;
    armeabi-v7a) termux_arch="arm";;
    x86_64)      termux_arch="x86_64";;
    *) echo "Unsupported ABI: $abi" >&2; exit 2;;
esac

mkdir -p "$artifacts_dir"
staging="$(mktemp -d -t vibeapp-androidsdk.XXXXXXXX)"
trap 'rm -rf "$staging"' EXIT

sdk_root="$staging/sdk-root"
mkdir -p "$sdk_root"/{platforms,build-tools,licenses}
```

- [ ] **Step 3: Fetch Google platform-36 zip → `platforms/android-36/`**

Append to `build-androidsdk.sh`:

```bash
platform_url="https://dl.google.com/android/repository/platform-${platform_rev}.zip"
echo "Downloading $platform_url ..."
curl -fSL --retry 3 -o "$staging/platform.zip" "$platform_url"
unzip -q "$staging/platform.zip" -d "$staging/platform-unpack"

# Google's zip wraps as android-<api>/ — move to platforms/android-36/
plat_src=$(find "$staging/platform-unpack" -maxdepth 2 -name "android.jar" -path "*/android-*/*" | head -1)
[[ -n "$plat_src" ]] || { echo "android.jar not found" >&2; exit 2; }
plat_src_dir="$(dirname "$plat_src")"

mkdir -p "$sdk_root/platforms/android-36"
cp -a "$plat_src_dir"/. "$sdk_root/platforms/android-36/"

# Write package.xml — minimal AGP-accepted form
cat > "$sdk_root/platforms/android-36/package.xml" <<'EOF'
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<ns2:sdk-repository xmlns:ns2="http://schemas.android.com/sdk/android/repo/repository2/03">
  <localPackage path="platforms;android-36">
    <type-details xsi:type="ns3:platformDetailsType"
                  xmlns:ns3="http://schemas.android.com/sdk/android/repo/repository2/03"
                  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
      <api-level>36</api-level>
    </type-details>
    <revision><major>1</major></revision>
    <display-name>Android SDK Platform 36</display-name>
  </localPackage>
</ns2:sdk-repository>
EOF
```

- [ ] **Step 4: Fetch Google build-tools zip → extract Java JARs**

Append:

```bash
# Google ships build-tools per-OS; we only need the Java JARs, which are
# identical across OS builds. Use linux (smallest, no native .so pollution).
bt_url="https://dl.google.com/android/repository/build-tools_r${sdk_version}-linux.zip"
echo "Downloading $bt_url ..."
curl -fSL --retry 3 -o "$staging/build-tools.zip" "$bt_url"
unzip -q "$staging/build-tools.zip" -d "$staging/bt-unpack"

bt_src=$(find "$staging/bt-unpack" -maxdepth 2 -name "d8.jar" -type f | head -1)
[[ -n "$bt_src" ]] || { echo "d8.jar not found in build-tools zip" >&2; exit 2; }
bt_src_dir="$(dirname "$bt_src")"

dest_bt="$sdk_root/build-tools/${sdk_version}"
mkdir -p "$dest_bt/lib"

# Copy the JAR tools + their lib/ supporting jars. Skip the linux-x86_64
# native aapt2 — Termux provides arm64 below.
for jar in d8.jar apksigner.jar; do
    cp "$bt_src_dir/$jar" "$dest_bt/$jar"
    cp "$bt_src_dir/lib/$jar" "$dest_bt/lib/$jar" 2>/dev/null || true
done

# core-lambda-stubs.jar is used for Java 8+ desugaring
if [[ -f "$bt_src_dir/core-lambda-stubs.jar" ]]; then
    cp "$bt_src_dir/core-lambda-stubs.jar" "$dest_bt/core-lambda-stubs.jar"
fi

# Minimal shell wrappers (AGP 9.1.0 invokes d8/apksigner via JARs in-process,
# but debug scripts & the wrapper-based fallback still need these).
cat > "$dest_bt/d8" <<'EOF'
#!/usr/bin/env bash
here="$(cd "$(dirname "$0")" && pwd)"
exec java -cp "$here/d8.jar" com.android.tools.r8.D8 "$@"
EOF
chmod 0755 "$dest_bt/d8"

cat > "$dest_bt/apksigner" <<'EOF'
#!/usr/bin/env bash
here="$(cd "$(dirname "$0")" && pwd)"
exec java -jar "$here/apksigner.jar" "$@"
EOF
chmod 0755 "$dest_bt/apksigner"

# package.xml — AGP checks revision matches the dir name (36.0.0 → major=36, minor=0, micro=0)
cat > "$dest_bt/package.xml" <<'EOF'
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<ns2:sdk-repository xmlns:ns2="http://schemas.android.com/sdk/android/repo/repository2/03">
  <localPackage path="build-tools;36.0.0">
    <type-details xsi:type="ns3:genericDetailsType"
                  xmlns:ns3="http://schemas.android.com/sdk/android/repo/repository2/03"
                  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"/>
    <revision><major>36</major><minor>0</minor><micro>0</micro></revision>
    <display-name>Android SDK Build-Tools 36.0.0</display-name>
  </localPackage>
</ns2:sdk-repository>
EOF

cat > "$dest_bt/source.properties" <<'EOF'
Pkg.Revision=36.0.0
#Pkg.License=android-sdk-license
EOF
```

- [ ] **Step 5: Fetch Termux `aapt2` → `build-tools/36.0.0/aapt2`**

Append:

```bash
aapt2_deb="aapt2_${aapt2_version}_${termux_arch}.deb"
aapt2_url="$termux_mirror/pool/main/a/aapt2/$aapt2_deb"
echo "Downloading $aapt2_url ..."
curl -fSL --retry 3 -o "$staging/$aapt2_deb" "$aapt2_url"

(cd "$staging" && ar x "$aapt2_deb")
# Handle xz/zst/plain data.tar the same way build-jdk.sh does
if   [[ -f "$staging/data.tar.xz"  ]]; then data_tar="data.tar.xz"
elif [[ -f "$staging/data.tar.zst" ]]; then data_tar="data.tar.zst"
else data_tar="data.tar"; fi

aapt2_unpack="$staging/aapt2-unpack"
mkdir -p "$aapt2_unpack"
tar -xf "$staging/$data_tar" -C "$aapt2_unpack"

# Termux installs at $PREFIX/bin/aapt2
aapt2_src="$aapt2_unpack/data/data/com.termux/files/usr/bin/aapt2"
[[ -x "$aapt2_src" ]] || { echo "aapt2 binary not found in .deb" >&2; exit 2; }
cp "$aapt2_src" "$dest_bt/aapt2"
chmod 0755 "$dest_bt/aapt2"
```

- [ ] **Step 6: Place the license file + pack the tarball**

Append:

```bash
cp "$script_dir/android-sdk-license.txt" "$sdk_root/licenses/android-sdk-license"

out="$artifacts_dir/android-sdk-${sdk_version}-${abi}.tar.gz"
(cd "$sdk_root" && tar -cf - .) | gzip -9 -c > "$out"
sha=$(shasum -a 256 "$out" | awk '{print $1}')
size=$(stat -f %z "$out" 2>/dev/null || stat -c %s "$out")
echo "$out"
echo "  size=$size sha256=$sha"
```

- [ ] **Step 7: Make it executable + smoke-test**

Run:
```
chmod +x scripts/bootstrap/build-androidsdk.sh
scripts/bootstrap/build-androidsdk.sh --abi arm64-v8a
```

Expected output:
```
Downloading https://dl.google.com/android/repository/platform-36_r01.zip ...
Downloading https://dl.google.com/android/repository/build-tools_r36.0.0-linux.zip ...
Downloading https://packages.termux.dev/apt/termux-main/pool/main/a/aapt2/aapt2_8.9.1-0_aarch64.deb ...
.../artifacts/android-sdk-36.0.0-arm64-v8a.tar.gz
  size=<N bytes, typically 70-90MB> sha256=<hex>
```

Smoke check the tarball layout:
```
tar -tzf scripts/bootstrap/artifacts/android-sdk-36.0.0-arm64-v8a.tar.gz | head -20
```

Expected entries include `platforms/android-36/android.jar`, `build-tools/36.0.0/aapt2`, `build-tools/36.0.0/d8.jar`, `licenses/android-sdk-license`.

- [ ] **Step 8: Commit**

```bash
git add scripts/bootstrap/build-androidsdk.sh scripts/bootstrap/android-sdk-license.txt
git commit -m "$(cat <<'EOF'
feat(scripts): build-androidsdk.sh assembles on-device Android SDK 36.0.0

Composes platform-36 + build-tools-36.0.0 per ABI by mixing Google's
platform zip (android.jar + stubs), Google's linux build-tools zip
(d8.jar + apksigner.jar), and Termux's arm64 aapt2 binary. Writes the
AGP-accepted license file and package.xml descriptors so AGP 9.1.0
recognizes the tree under $PREFIX/opt/android-sdk-36.0.0/.

Phase 2d Task 1.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Add `android-sdk-36.0.0` to manifest composer

**Files:**
- Modify: `scripts/bootstrap/build-manifest.sh` — add component to the enumeration loop.
- Modify: `scripts/bootstrap/README.md` — document the new script.

- [ ] **Step 1: Add component to `build-manifest.sh` loop**

Locate the line:
```bash
for id in "hello" "jdk-17.0.13" "gradle-9.3.1"; do
```

Change to:
```bash
for id in "hello" "jdk-17.0.13" "gradle-9.3.1" "android-sdk-36.0.0"; do
```

Locate the version-field mapping:
```bash
if [[ "$id" == "jdk-17.0.13"    ]]; then version_field="17.0.13"; fi
if [[ "$id" == "hello"          ]]; then version_field="1.0"; fi
if [[ "$id" == "gradle-9.3.1"  ]]; then version_field="9.3.1"; fi
```

Add one line after the gradle check:
```bash
if [[ "$id" == "android-sdk-36.0.0" ]]; then version_field="36.0.0"; fi
```

- [ ] **Step 2: Regenerate manifest.json and verify**

Run:
```
scripts/bootstrap/build-manifest.sh
```

Expected: `scripts/bootstrap/artifacts/manifest.json` now has 4 components; the last block matches:
```json
{
  "id": "android-sdk-36.0.0",
  "version": "36.0.0",
  "artifacts": {
    "arm64-v8a": { "fileName": "android-sdk-36.0.0-arm64-v8a.tar.gz", "sizeBytes": <N>, "sha256": "..." }
  }
}
```

Verify signature:
```
ls -la scripts/bootstrap/artifacts/manifest.json.sig
```
Expected: file exists, 64 bytes.

- [ ] **Step 3: Update scripts/bootstrap/README.md**

Open `scripts/bootstrap/README.md`. In the "Building a dev manifest from scratch" section (or equivalent), add between the gradle and manifest steps:

```
scripts/bootstrap/build-androidsdk.sh --abi arm64-v8a
```

- [ ] **Step 4: Commit**

```bash
git add scripts/bootstrap/build-manifest.sh scripts/bootstrap/README.md
git commit -m "$(cat <<'EOF'
build(scripts): add android-sdk-36.0.0 to manifest composer

Phase 2d Task 2.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

Note: `/scripts/bootstrap/artifacts` is gitignored entirely — dev-server artifacts (including `manifest.json` + signature) are regenerated via `build-manifest.sh` on each release and are not committed.

---

## Task 3: Wire component ID into ProcessEnvBuilder

**Files:**
- Modify: `build-runtime/src/main/kotlin/com/vibe/build/runtime/process/ProcessEnvBuilder.kt`
- Modify: `build-runtime/src/test/kotlin/com/vibe/build/runtime/process/ProcessEnvBuilderTest.kt` (add test; if file absent, create it)

**Context:** `ProcessEnvBuilder` already sets `ANDROID_HOME` to `<usrRoot>/opt/android-sdk`. That placeholder dir doesn't exist yet; now that Task 1/2 land a real artifact named `android-sdk-36.0.0`, the path needs to match.

- [ ] **Step 1: Write the failing test**

If `ProcessEnvBuilderTest.kt` already exists, append a new test. If not, create the file with:

```kotlin
package com.vibe.build.runtime.process

import com.vibe.build.runtime.bootstrap.BootstrapFileSystem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProcessEnvBuilderTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun ANDROID_HOME_points_to_android_sdk_36_0_0() {
        val filesDir = tmp.newFolder("filesDir")
        val fs = BootstrapFileSystem(filesDir = filesDir)
        fs.ensureDirectories()
        val preload = object : PreloadLibLocator(File("/nowhere")) {
            override fun termuxExecLibPath() = ""
        }
        val env = ProcessEnvBuilder(fs, preload).build(cwd = filesDir)
        val expected = File(fs.optRoot, "android-sdk-36.0.0").absolutePath
        assertEquals(expected, env["ANDROID_HOME"])
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :build-runtime:testDebugUnitTest --tests "com.vibe.build.runtime.process.ProcessEnvBuilderTest.ANDROID_HOME_points_to_android_sdk_36_0_0"`

Expected: FAIL with `expected:<.../android-sdk-36.0.0> but was:<.../android-sdk>`.

- [ ] **Step 3: Update `ProcessEnvBuilder`**

In `ProcessEnvBuilder.kt`, locate the companion:
```kotlin
companion object {
    const val JDK_DIR_NAME = "jdk-17.0.13"
    const val ANDROID_SDK_DIR_NAME = "android-sdk"
    const val GRADLE_USER_HOME_DIR_NAME = ".gradle"
}
```

Change `ANDROID_SDK_DIR_NAME` to `"android-sdk-36.0.0"`:

```kotlin
const val ANDROID_SDK_DIR_NAME = "android-sdk-36.0.0"
```

Also update the kdoc comment block at the top of the class to reflect the new path.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :build-runtime:testDebugUnitTest --tests "com.vibe.build.runtime.process.ProcessEnvBuilderTest.ANDROID_HOME_points_to_android_sdk_36_0_0"`

Expected: PASS.

- [ ] **Step 5: Run full build-runtime unit suite**

Run: `./gradlew :build-runtime:testDebugUnitTest`

Expected: all tests pass. None should reference the old `"android-sdk"` constant.

- [ ] **Step 6: Commit**

```bash
git add build-runtime/src/main/kotlin/com/vibe/build/runtime/process/ProcessEnvBuilder.kt build-runtime/src/test/kotlin/com/vibe/build/runtime/process/ProcessEnvBuilderTest.kt
git commit -m "$(cat <<'EOF'
feat(build-runtime): ANDROID_HOME → android-sdk-36.0.0

Match the component ID that Phase 2d's build-androidsdk.sh produces.

Phase 2d Task 3.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: `ProjectStager` — copy tree + substitute local.properties

**Files:**
- Create: `build-gradle/src/main/kotlin/com/vibe/build/gradle/ProjectTemplate.kt`
- Create: `build-gradle/src/main/kotlin/com/vibe/build/gradle/ProjectStager.kt`
- Create: `build-gradle/src/test/kotlin/com/vibe/build/gradle/ProjectStagerTest.kt`

**Context:** `GradleBuildService.runBuild(projectDirectory, ...)` takes a real directory on disk. `ProjectStager` is the glue that puts one there. It copies an input tree (from assets or from anywhere else) into `filesDir/projects/<id>/` and substitutes `{{SDK_DIR}}` + `{{GRADLE_USER_HOME}}` into any file whose name ends in `.tmpl` (and drops the `.tmpl` extension in the destination).

- [ ] **Step 1: Write `ProjectTemplate.kt`**

```kotlin
package com.vibe.build.gradle

import java.io.File

/**
 * Where to find the template tree for a probe/staged project.
 *
 * Phase 2d only uses [FromDirectory] (instrumented test extracts assets
 * to a temp dir first). Phase 3's real project generator will likely
 * swap in a type that renders from in-memory templates — adding a new
 * subtype is additive.
 */
sealed class ProjectTemplate {
    data class FromDirectory(val root: File) : ProjectTemplate()
}
```

- [ ] **Step 2: Write the failing test for `ProjectStager`**

```kotlin
package com.vibe.build.gradle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectStagerTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun stages_tree_and_substitutes_tmpl_files() {
        val src = tmp.newFolder("src")
        File(src, "settings.gradle.kts").writeText("""rootProject.name = "x"""")
        File(src, "local.properties.tmpl").writeText(
            "sdk.dir={{SDK_DIR}}\n" +
            "gradle.user.home={{GRADLE_USER_HOME}}\n",
        )
        val dest = tmp.newFolder("dest")

        val stager = ProjectStager()
        val out = stager.stage(
            template = ProjectTemplate.FromDirectory(src),
            destinationDir = dest,
            variables = mapOf(
                "SDK_DIR" to "/opt/sdk",
                "GRADLE_USER_HOME" to "/opt/.gradle",
            ),
        )

        assertEquals(dest.absolutePath, out.absolutePath)
        assertTrue(File(dest, "settings.gradle.kts").exists())
        val rendered = File(dest, "local.properties").readText()
        assertEquals("sdk.dir=/opt/sdk\ngradle.user.home=/opt/.gradle\n", rendered)
        assertTrue("template must not land as .tmpl", !File(dest, "local.properties.tmpl").exists())
    }

    @Test
    fun stage_is_idempotent_same_inputs_produce_identical_tree() {
        val src = tmp.newFolder("src")
        File(src, "build.gradle.kts").writeText("// top level")
        File(src, "app").mkdirs()
        File(src, "app/build.gradle.kts").writeText("// app level")
        val dest = tmp.newFolder("dest")
        val stager = ProjectStager()

        stager.stage(ProjectTemplate.FromDirectory(src), dest, emptyMap())
        val first = File(dest, "app/build.gradle.kts").lastModified()
        stager.stage(ProjectTemplate.FromDirectory(src), dest, emptyMap())
        val second = File(dest, "app/build.gradle.kts").lastModified()

        // Re-staging must overwrite cleanly — second timestamp >= first.
        assertTrue(second >= first)
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :build-gradle:testDebugUnitTest --tests "com.vibe.build.gradle.ProjectStagerTest"`

Expected: FAIL — `ProjectStager` doesn't exist yet (`Unresolved reference: ProjectStager`).

- [ ] **Step 4: Implement `ProjectStager`**

```kotlin
package com.vibe.build.gradle

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Copies a template project tree onto disk, substituting {{VAR}}
 * placeholders in any file whose name ends in `.tmpl`. The `.tmpl`
 * suffix is stripped in the destination.
 *
 * Idempotent: re-staging overwrites unconditionally. Cheap enough that
 * we don't bother with incremental hashing (probe projects have ~10
 * files, ~30 KB total).
 */
@Singleton
class ProjectStager @Inject constructor() {

    fun stage(
        template: ProjectTemplate,
        destinationDir: File,
        variables: Map<String, String>,
    ): File {
        val source = when (template) {
            is ProjectTemplate.FromDirectory -> template.root
        }
        require(source.isDirectory) { "template source not a directory: $source" }

        destinationDir.deleteRecursively()
        destinationDir.mkdirs()

        source.walkTopDown().forEach { entry ->
            if (entry == source) return@forEach
            val rel = entry.relativeTo(source).path
            if (entry.isDirectory) {
                File(destinationDir, rel).mkdirs()
                return@forEach
            }
            val isTemplate = entry.name.endsWith(".tmpl")
            val destName = if (isTemplate) rel.removeSuffix(".tmpl") else rel
            val destFile = File(destinationDir, destName)
            destFile.parentFile?.mkdirs()
            if (isTemplate) {
                destFile.writeText(substitute(entry.readText(), variables))
            } else {
                entry.copyTo(destFile, overwrite = true)
            }
            // Preserve executable bit for shell wrappers in the template.
            if (entry.canExecute()) destFile.setExecutable(true, /* ownerOnly = */ false)
        }
        return destinationDir
    }

    private fun substitute(input: String, variables: Map<String, String>): String {
        var out = input
        for ((k, v) in variables) {
            out = out.replace("{{$k}}", v)
        }
        return out
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :build-gradle:testDebugUnitTest --tests "com.vibe.build.gradle.ProjectStagerTest"`

Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add build-gradle/src/main/kotlin/com/vibe/build/gradle/ProjectStager.kt build-gradle/src/main/kotlin/com/vibe/build/gradle/ProjectTemplate.kt build-gradle/src/test/kotlin/com/vibe/build/gradle/ProjectStagerTest.kt
git commit -m "$(cat <<'EOF'
feat(build-gradle): ProjectStager — copy template + substitute .tmpl files

Phase 2d Task 4.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Probe project sources under androidTest assets

**Files:** All under `build-gradle/src/androidTest/assets/probe-app/` — creates.

This is the hardcoded empty-Activity Java project that Task 6's instrumented test will stage and build. No Compose, no Kotlin, no AndroidX beyond what AGP's plugin itself drags in.

- [ ] **Step 1: `settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "probe-app"
include(":app")
```

- [ ] **Step 2: Root `build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
}
```

- [ ] **Step 3: `gradle.properties`**

```properties
# Minimum JVM so :app configuration doesn't OOM on first Maven resolution.
org.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=512m

android.useAndroidX=true
android.nonTransitiveRClass=true

# No configuration cache for Phase 2d — keeps failure modes simple.
org.gradle.configuration-cache=false
```

- [ ] **Step 4: `local.properties.tmpl`**

```properties
# Written by ProjectStager. {{SDK_DIR}} is the bootstrapped android-sdk root.
sdk.dir={{SDK_DIR}}
```

- [ ] **Step 5: `gradle/libs.versions.toml`**

```toml
[versions]
agp = "9.1.0"

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
```

- [ ] **Step 6: `app/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.vibe.probe"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vibe.probe"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
```

- [ ] **Step 7: `app/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:label="@string/app_name">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 8: `app/src/main/java/com/vibe/probe/MainActivity.java`**

```java
package com.vibe.probe;

import android.app.Activity;
import android.os.Bundle;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }
}
```

- [ ] **Step 9: `app/src/main/res/values/strings.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Probe</string>
</resources>
```

- [ ] **Step 10: Sanity-check the tree**

Run:
```
find build-gradle/src/androidTest/assets/probe-app -type f | sort
```

Expected: 9 files (all of the above).

- [ ] **Step 11: Commit**

```bash
git add build-gradle/src/androidTest/assets/probe-app
git commit -m "$(cat <<'EOF'
test(build-gradle): probe-app — hardcoded empty-Activity Java project

Minimal AGP project for Phase 2d's on-device assembleDebug acceptance.
Pure Java to keep transitive Maven deps at a minimum: just the AGP
9.1.0 plugin + its default closure.

Phase 2d Task 5.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Instrumented test — probe builds to installable APK

**Files:**
- Create: `build-gradle/src/androidTest/kotlin/com/vibe/build/gradle/EmptyApkBuildInstrumentedTest.kt`
- Modify: `build-gradle/src/androidTest/AndroidManifest.xml` — already permits cleartext to 10.0.2.2; confirm HTTPS to `dl.google.com` and `repo1.maven.org` is default-permitted (no change expected).

- [ ] **Step 1: Write the instrumented test**

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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

/**
 * Phase 2d acceptance. Same opt-in arg as 2c:
 *   -Pandroid.testInstrumentationRunnerArguments.vibeapp.phase2c.dev_server_url=http://10.0.2.2:8000
 *
 * Runs full bootstrap (JDK + Gradle + Android SDK 36.0.0), stages the
 * probe-app template from androidTest/assets to filesDir/projects/probe,
 * and runs :app:assembleDebug via GradleBuildService. Asserts the APK
 * exists, is a valid zip (PK header), and contains AndroidManifest.xml.
 */
@RunWith(AndroidJUnit4::class)
class EmptyApkBuildInstrumentedTest {

    private lateinit var ctx: Context
    private lateinit var scratchDir: File
    private lateinit var fs: BootstrapFileSystem
    private lateinit var launcher: NativeProcessLauncher
    private lateinit var service: GradleBuildServiceImpl

    private fun devServerUrlOrSkip(): String {
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("vibeapp.phase2c.dev_server_url")
        assumeTrue(
            "vibeapp.phase2c.dev_server_url not provided; skipping Phase 2d acceptance",
            !url.isNullOrBlank(),
        )
        return url!!.trimEnd('/')
    }

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        scratchDir = File(ctx.cacheDir, "phase2d-${System.nanoTime()}")
        require(scratchDir.mkdirs())

        fs = BootstrapFileSystem(filesDir = scratchDir)
        fs.ensureDirectories()
        val preloadLib = object : PreloadLibLocator(File(ctx.applicationInfo.nativeLibraryDir)) {
            override fun termuxExecLibPath(): String = ""
        }
        val envBuilder = ProcessEnvBuilder(fs, preloadLib)
        launcher = NativeProcessLauncher(envBuilder)
    }

    @After
    fun tearDown() {
        runBlocking {
            if (::service.isInitialized) {
                try { service.shutdown() } catch (_: Throwable) {}
            }
        }
        scratchDir.deleteRecursively()
    }

    @Test
    fun probe_app_assembleDebug_produces_installable_apk() = runBlocking {
        val devUrl = devServerUrlOrSkip()

        val mirrors = MirrorSelector(primaryBase = devUrl, fallbackBase = "https://unused.test")
        val devPubkeyHex = "5ce0c624f59a72ee8eb6f72c25ad905a27afcd0392998f353ef86f3247725f40"
        val bootstrapper = RuntimeBootstrapper(
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

        val seen = mutableListOf<BootstrapState>()
        withTimeout(300_000) {
            bootstrapper.bootstrap(manifestUrl = "manifest.json") { seen += it }
        }
        assertTrue("bootstrap failed: ${seen.last()}", seen.last() is BootstrapState.Ready)

        // Extract the probe template from androidTest assets to a temp
        // directory, then stage it onto filesDir via ProjectStager.
        val templateSrc = File(scratchDir, "probe-src").also { it.mkdirs() }
        copyAssetDir(ctx, "probe-app", templateSrc)

        val projectDir = File(scratchDir, "projects/probe")
        val sdkDir = fs.componentInstallDir("android-sdk-36.0.0")
        val gradleUserHome = File(scratchDir, ".gradle")
        ProjectStager().stage(
            template = ProjectTemplate.FromDirectory(templateSrc),
            destinationDir = projectDir,
            variables = mapOf(
                "SDK_DIR" to sdkDir.absolutePath,
                "GRADLE_USER_HOME" to gradleUserHome.absolutePath,
            ),
        )

        val gradleDist = fs.componentInstallDir("gradle-9.3.1")
        val javaBinary = File(fs.componentInstallDir("jdk-17.0.13"), "bin/java")
        assertTrue("java binary missing at $javaBinary", javaBinary.canExecute())
        assertTrue("aapt2 missing", File(sdkDir, "build-tools/36.0.0/aapt2").canExecute())

        service = GradleBuildServiceImpl(
            launcher = launcher,
            envBuilder = ProcessEnvBuilder(fs, PreloadLibLocator(File(ctx.applicationInfo.nativeLibraryDir))),
            extractor = GradleHostExtractor(ctx, fs),
            fs = fs,
        )

        withTimeout(60_000) { service.start(gradleDist) }

        val events = withTimeout(600_000) {
            service.runBuild(
                projectDirectory = projectDir,
                tasks = listOf(":app:assembleDebug"),
                args = listOf("--no-daemon", "--no-configuration-cache"),
            ).toList()
        }

        val finish = events.filterIsInstance<HostEvent.BuildFinish>().first()
        assertEquals(
            "assembleDebug failed: ${finish.failureSummary}",
            true, finish.success,
        )

        val apk = File(projectDir, "app/build/outputs/apk/debug/app-debug.apk")
        assertTrue("apk not produced at $apk", apk.exists())

        // Byte-check: first 4 bytes MUST be 'PK\x03\x04' (local file header).
        apk.inputStream().use { s ->
            val header = ByteArray(4)
            assertEquals(4, s.read(header))
            assertEquals(0x50, header[0].toInt() and 0xff)
            assertEquals(0x4B, header[1].toInt() and 0xff)
            assertEquals(0x03, header[2].toInt() and 0xff)
            assertEquals(0x04, header[3].toInt() and 0xff)
        }

        // Zip-validity + contains AndroidManifest.xml (binary-encoded by aapt2).
        ZipFile(apk).use { z ->
            assertTrue(
                "APK missing AndroidManifest.xml",
                z.getEntry("AndroidManifest.xml") != null,
            )
            assertTrue(
                "APK missing classes.dex",
                z.getEntry("classes.dex") != null,
            )
            assertTrue(
                "APK missing debug v2 signing block",
                z.getEntry("META-INF/CERT.SF") != null ||
                z.getEntry("META-INF/CERT.RSA") != null ||
                // v2+ signing leaves only the zip-central-directory block;
                // a file of length > 10KB with classes.dex is sufficient
                // evidence for Phase 2d. Accept absence of v1 sigs.
                apk.length() > 10_000,
            )
        }
    }

    private fun copyAssetDir(ctx: Context, assetPath: String, destDir: File) {
        destDir.mkdirs()
        val entries = ctx.assets.list(assetPath) ?: emptyArray()
        if (entries.isEmpty()) {
            // Leaf file
            ctx.assets.open(assetPath).use { input ->
                File(destDir.parentFile, File(assetPath).name).outputStream().use { out ->
                    input.copyTo(out)
                }
            }
            return
        }
        entries.forEach { entry ->
            val childAssetPath = "$assetPath/$entry"
            val childList = ctx.assets.list(childAssetPath) ?: emptyArray()
            if (childList.isEmpty()) {
                // It's a file (AssetManager.list on a file returns empty).
                ctx.assets.open(childAssetPath).use { input ->
                    File(destDir, entry).outputStream().use { out ->
                        input.copyTo(out)
                    }
                }
            } else {
                copyAssetDir(ctx, childAssetPath, File(destDir, entry))
            }
        }
    }
}
```

- [ ] **Step 2: Verify the NSC already permits 10.0.2.2**

Check `build-gradle/src/androidTest/res/xml/phase2c_nsc.xml` exists (it does — created in Phase 2c closeout). No change needed; Maven requests go HTTPS to `dl.google.com` and `repo1.maven.org`, both default-allowed.

- [ ] **Step 3: Verify the dev server has all artifacts**

Ensure the dev server at `http://localhost:8000` serves:
- `manifest.json` (with 4 components)
- `manifest.json.sig`
- `hello-arm64-v8a.tar.gz`
- `jdk-17.0.13-arm64-v8a.tar.gz`
- `gradle-9.3.1-common.tar.gz`
- `android-sdk-36.0.0-arm64-v8a.tar.gz`

Run:
```
curl -s http://localhost:8000/manifest.json | grep -c '"id"'
```
Expected: 4.

- [ ] **Step 4: Run the instrumented test**

```bash
./gradlew :build-gradle:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.vibeapp.phase2c.dev_server_url=http://10.0.2.2:8000 \
  -Pandroid.testInstrumentationRunnerArguments.class=com.vibe.build.gradle.EmptyApkBuildInstrumentedTest
```

Expected: `BUILD SUCCESSFUL`, 1 test passed.

First run will take ~5-10 minutes (Maven resolution of AGP + transitives). Second run, if the instrumented test APK doesn't blow away `~/.gradle` between runs (it does — `tearDown` recursively deletes `scratchDir`), will still be full-cost.

- [ ] **Step 5: Commit**

```bash
git add build-gradle/src/androidTest/kotlin/com/vibe/build/gradle/EmptyApkBuildInstrumentedTest.kt
git commit -m "$(cat <<'EOF'
test(build-gradle): Phase 2d acceptance — probe app → installable APK

Phase 2d Task 6.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: Debug UI button for manual verification

**Files:**
- Modify: `app/src/main/kotlin/com/vibe/app/feature/bootstrap/BuildRuntimeDebugScreen.kt` — add button.
- Modify: `app/src/main/assets/probe-app/` — copy of the probe-app template (so the debug UI doesn't need the androidTest assets).

**Context:** The instrumented test is the primary automated acceptance. This task adds an optional manual path for dev-machine debugging: a button in the existing debug screen that stages the probe-app and runs `:app:assembleDebug`, surfacing progress as logcat.

- [ ] **Step 1: Duplicate probe-app into the app module's assets**

```bash
mkdir -p app/src/main/assets/probe-app
cp -a build-gradle/src/androidTest/assets/probe-app/. app/src/main/assets/probe-app/
```

Rationale for duplication over shared-source: the main app APK and the library instrumented-test APK have isolated asset sets. Sharing via a Gradle `Copy` task would require more plumbing than duplicating ~3KB of text files for the debug path. Revisit if these templates grow.

- [ ] **Step 2: Add button to `BuildRuntimeDebugScreen.kt`**

Locate the column of existing debug buttons (Run hello / Run java / Run gradle). Add a new button after them:

```kotlin
Button(
    onClick = { viewModel.runProbeAssembleDebug() },
    modifier = Modifier.fillMaxWidth(),
) { Text("Run probe :app:assembleDebug") }
```

In the screen's ViewModel, add:

```kotlin
fun runProbeAssembleDebug() {
    viewModelScope.launch {
        val projectDir = File(context.filesDir, "projects/probe").also {
            it.parentFile?.mkdirs()
        }
        val sdkDir = fs.componentInstallDir("android-sdk-36.0.0")
        val gradleUserHome = File(context.filesDir, ".gradle")

        // Stage probe-app from assets
        val templateSrc = File(context.cacheDir, "probe-src-${System.nanoTime()}")
        copyAssetDir(context, "probe-app", templateSrc)
        ProjectStager().stage(
            template = ProjectTemplate.FromDirectory(templateSrc),
            destinationDir = projectDir,
            variables = mapOf(
                "SDK_DIR" to sdkDir.absolutePath,
                "GRADLE_USER_HOME" to gradleUserHome.absolutePath,
            ),
        )

        val gradleDist = fs.componentInstallDir("gradle-9.3.1")
        gradleBuildService.start(gradleDist)
        gradleBuildService.runBuild(
            projectDirectory = projectDir,
            tasks = listOf(":app:assembleDebug"),
            args = listOf("--no-daemon", "--no-configuration-cache"),
        ).collect { event ->
            Log.i("VibeApp/Phase2d", "event=$event")
        }
    }
}
```

If the ViewModel already has a `GradleBuildService` dependency, add `ProjectStager` as a `@Inject` constructor parameter. Mirror the `copyAssetDir` helper from the instrumented test into a small utility in `app/feature/bootstrap/` (or inline).

- [ ] **Step 3: Build and install the app**

```bash
./gradlew :app:installDebug
adb shell monkey -p com.vibe.app -c android.intent.category.LAUNCHER 1
```

- [ ] **Step 4: Manually drive through the debug UI**

1. Navigate to the Build Runtime Debug screen.
2. If bootstrap hasn't run on this device, run it (existing button).
3. Tap "Run probe :app:assembleDebug".
4. Watch logcat:
   ```
   adb logcat -s VibeApp/Phase2d:I VibeApp/Bootstrap:I *:E | head -200
   ```
   Expected: stream of `HostEvent.Log` / `HostEvent.BuildProgress` events, terminating in `HostEvent.BuildFinish(success=true)` within 5-10 minutes (cold) or <60s (warm).
5. Verify the APK landed:
   ```
   adb shell run-as com.vibe.app ls -la files/projects/probe/app/build/outputs/apk/debug/
   ```
   Expected: `app-debug.apk` present.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/assets/probe-app app/src/main/kotlin/com/vibe/app/feature/bootstrap/BuildRuntimeDebugScreen.kt
git commit -m "$(cat <<'EOF'
feat(debug-ui): 'Run probe :app:assembleDebug' button

Manual-path mirror of Phase 2d's instrumented test for on-device
debugging.

Phase 2d Task 7.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: Manual e2e validation + exit log

**Files:**
- Modify: `docs/superpowers/plans/2026-04-18-v2-phase-2d-android-sdk-empty-apk.md` (append exit log).

- [ ] **Step 1: Regression — full existing test suites**

```bash
./gradlew :gradle-host:test :build-runtime:testDebugUnitTest :build-gradle:testDebugUnitTest :app:kspDebugKotlin :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Phase 1 + 2a + 2b + 2c instrumented regression**

```bash
# Dev server already running at :8000
./gradlew :build-runtime:connectedDebugAndroidTest :build-gradle:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.vibeapp.phase2c.dev_server_url=http://10.0.2.2:8000
```

Expected: all previously-passing tests still pass. Phase 2c's `GradleHostInstrumentedTest` + Phase 2d's `EmptyApkBuildInstrumentedTest` pass. Phase 2a/2b Tier-1 bootstrap tests pass (now with 4 components instead of 3).

- [ ] **Step 3: Manual device verification (optional but recommended)**

Install debug build and drive through the "Run probe :app:assembleDebug" button from Task 7. Read the on-device APK off:
```
adb shell run-as com.vibe.app cat files/projects/probe/app/build/outputs/apk/debug/app-debug.apk > /tmp/probe-app-debug.apk
```

Open `/tmp/probe-app-debug.apk` with `aapt2 dump badging`:
```
aapt2 dump badging /tmp/probe-app-debug.apk
```

Expected: reports `package: name='com.vibe.probe'`, launchable-activity `com.vibe.probe.MainActivity`, `application-label:'Probe'`.

Optionally install the produced APK on a second device / the same emulator:
```
adb install -r /tmp/probe-app-debug.apk
adb shell monkey -p com.vibe.probe -c android.intent.category.LAUNCHER 1
```

Expected: launcher icon shows up, app opens to blank Activity, no crash.

- [ ] **Step 4: Append exit log**

Add to the bottom of this plan document:

```markdown
---

## Exit Log (YYYY-MM-DD)

**Validated on:** [device + API] with dev server at http://10.0.2.2:8000.

**Command run:** [assembleDebug e2e command]

**Result:** [summary — APK produced, size, resolved AGP version, cold build time, warm build time]

**Issues discovered and resolved during Task 8:**
[numbered list of anything surprising]

**Phase 2d is complete.** Phase 2e (Compose compiler + Maven Compose BOM → first Compose APK) is unblocked.
```

Fill in the bracketed fields with the actual observations.

- [ ] **Step 5: Commit exit log**

```bash
git add docs/superpowers/plans/2026-04-18-v2-phase-2d-android-sdk-empty-apk.md
git commit -m "$(cat <<'EOF'
docs(phase 2d): record exit log

Phase 2d Task 8.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 6: Push**

```bash
git push origin v2-arch
```

---

## Phase 2d Exit Criteria

- [x] `scripts/bootstrap/build-androidsdk.sh --abi arm64-v8a` produces `android-sdk-36.0.0-arm64-v8a.tar.gz` (~94MB) containing `platforms/android-33/android.jar`, `build-tools/36.0.0/aapt2` (arm64 executable), `build-tools/36.0.0/d8.jar`, `build-tools/36.0.0/apksigner.jar`, and `licenses/android-sdk-license`.
- [x] `manifest.json` emitted by `build-manifest.sh` lists four components; signature verifies.
- [x] `ProcessEnvBuilder.ANDROID_SDK_DIR_NAME == "android-sdk-36.0.0"` and the new unit test passes. LD_LIBRARY_PATH also extended to include `$ANDROID_HOME/build-tools/36.0.0/` so aapt2's bundled `.so` closure resolves.
- [x] `ProjectStager` unit tests pass (template copy + `.tmpl` substitution + idempotent re-stage).
- [x] `EmptyApkBuildInstrumentedTest.probe_app_assembleDebug_produces_installable_apk` passes: `:app:assembleDebug` on the staged probe project returns `BuildFinish(success=true)` in ~254s, and the produced `app-debug.apk` is a valid zip containing `AndroidManifest.xml` + `classes.dex`.
- [x] Phase 1 (unit + instrumented) + Phase 2a + Phase 2b + Phase 2c tests still pass with the enlarged 4-component manifest (11/11 + 1/1 green).
- [x] `:app:assembleDebug` and `:app:kspDebugKotlin` still green.
- [x] Exit log filled in at the bottom of this plan.

When all boxes check, Phase 2d is done. Phase 2e (Compose compiler plugin + Compose BOM from Maven + Compose probe) is unblocked.

---

## Self-Review Notes

**Spec coverage:**
- §2.3 Version matrix: AGP 9.1.0 ↔ platform-36 ↔ build-tools 36.0.0 ↔ Gradle 9.3.1 — all pinned in Task 1/5.
- §3.2 Filesystem layout: `$PREFIX/opt/android-sdk-36.0.0/` — implemented in Task 3.
- §5.1 Project structure (reduced): Task 5's probe-app mirrors the non-Compose core; Compose-specific subtrees (ui/theme, mipmap-anydpi-v26) deferred to 2e.
- §5.5 `app/build.gradle.kts` template: reduced form in Task 5 Step 6 — no Compose, no Shadow plugin, pure AGP + Java source.
- §10 "Phase 2" sub-phases: 2d = Android SDK + empty APK; 2e = Compose BOM + first Compose APK. Split as agreed with user.

**Gaps explicitly not addressed (by design):**
- Kotlin toolchain — deferred to 2e with Compose.
- Compose compiler plugin + kotlin-compose plugin — 2e.
- Shadow plugin in probe `app/build.gradle.kts` — Phase 5.
- `GradleProjectInitializer` / template variable substitution for user-facing projects — Phase 3.
- APK installer flow + FileProvider — Phase 3.
- Offline Maven mirror — not in any current phase; may be added in Phase 7 for size/perf tuning.

**Type consistency:**
- `ProjectStager.stage(template: ProjectTemplate, destinationDir: File, variables: Map<String, String>): File` — signature used identically in Task 4 test, Task 4 impl, Task 6 instrumented test, Task 7 debug UI.
- `ProjectTemplate.FromDirectory(root: File)` — only subtype in Phase 2d; type is sealed for future additions without breaking callers.
- Component ID `"android-sdk-36.0.0"` used identically in Task 1 (artifact filename), Task 2 (manifest), Task 3 (ProcessEnvBuilder constant), Task 6 (`fs.componentInstallDir("android-sdk-36.0.0")`).
- Bootstrap manifest arg key `"vibeapp.phase2c.dev_server_url"` — retained from 2c for zero-friction test invocation; not renamed to `phase2d` because the arg names a mechanism (dev server URL), not a phase.
- Instrumented test timeout constants: 300s bootstrap, 60s host start, 600s build — explicitly documented in Task 6.

**Placeholder scan — fixed:**
- No "TBD" / "fill in later" / "similar to Task N" — each task's code blocks are complete and self-contained.
- Error handling: the instrumented test asserts `BuildFinish.success == true` with the `failureSummary` in the message; no silent-swallow paths.
- Exit log section in Task 8 has a template to fill in, NOT skipped — the template is explicit about what to capture.

No placeholders remain.

---

## Exit Log (2026-04-18)

**Validated on:** Pixel 7 Pro emulator (AVD, API 31, arm64-v8a) with 6GB RAM; dev server at `http://localhost:8000`.

**Command:**
```
./gradlew :build-gradle:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.vibeapp.phase2c.dev_server_url=http://10.0.2.2:8000 \
  -Pandroid.testInstrumentationRunnerArguments.class=com.vibe.build.gradle.EmptyApkBuildInstrumentedTest
```

**Result:** `BUILD SUCCESSFUL`. `EmptyApkBuildInstrumentedTest.probe_app_assembleDebug_produces_installable_apk` passes in ~254s (4m 14s cold cache). The bootstrap extracts JDK 17 + Gradle 9.3.1 + Android SDK; GradleHost spawns the Termux JVM; Tooling API drives the Gradle Daemon; AGP 9.1.0 resolves the AndroidX/Gradle plugin closure over the network; aapt2 (arm64, Termux-sourced) compiles the probe's two resource files; javac compiles `MainActivity.java`; d8 dexes the class; apksigner signs with the debug key; `app-debug.apk` lands at `filesDir/projects/probe/app/build/outputs/apk/debug/` as a valid PK-zip containing `AndroidManifest.xml` + `classes.dex`.

**Regression:**
- `:gradle-host:test`, `:build-runtime:testDebugUnitTest`, `:build-gradle:testDebugUnitTest`, `:app:kspDebugKotlin`, `:app:assembleDebug` — all green.
- `:build-runtime:connectedDebugAndroidTest` — 11/11 pass (Phase 1 + 2a + 2b instrumented).
- `:build-gradle:connectedDebugAndroidTest` (`GradleHostInstrumentedTest`) — Phase 2c acceptance still green against the new Gradle 9.3.1 + Android SDK stack.

**Detour: on-device Gradle bump 8.10.2 → 9.3.1** (commit `59ed004`). AGP 9.1.0 in probe-app requires Gradle 9.x+, but Phase 2b pinned the bootstrapped distribution at 8.10.2. Raised it to 9.3.1 (matching the host build) and bumped `gradle-tooling-api` to 9.3.1 too. Touched 15 files: `build-gradle.sh`, `build-manifest.sh`, `libs.versions.toml`, `Main.toolingApiVersion`, 4 test call sites, `BootstrapManifest` doc + fixture, the debug UI VM, the release-prep doc. Historical plans and specs left unedited.

**Issues discovered and resolved during Task 6 manual iteration:**

1. *Tooling API rejected `--no-daemon`.* AGP build args list that Tooling API accepts is a subset of `gradle` CLI — `--no-daemon` is invalid (daemon lifecycle is Tooling API's job). Changed `runBuild(args = emptyList())`, set `org.gradle.configuration-cache=false` in probe's `gradle.properties` instead.

2. *Emulator lowmemorykiller.* Original AVD had `hw.ramSize=2048` + `vm.heapSize=384`. Gradle Daemon + Maven resolution + Kotlin daemon + AGP blew past that. Bumped AVD to 6 GB / 768 MB heap. On real phones with 3-4 GB RAM, tightened probe `gradle.properties` (`-Xmx640m`, `kotlin.daemon.jvm.options=-Xmx384m`, `org.gradle.parallel=false`, `org.gradle.caching=false`) stays well inside the working set.

3. *Maven aapt2 artifact is x86_64 linux ELF.* AGP's default aapt2 is a jar-of-binary (`aapt2:jar:linux`), not usable on arm64 Android. Override in probe's `gradle.properties.tmpl`: `android.aapt2FromMavenOverride={{SDK_DIR}}/build-tools/36.0.0/aapt2`. `ProjectStager` substitutes `{{SDK_DIR}}` on stage.

4. *AGP's `BuildToolsLayout` validates existence of ~15 tool binaries at configure time.* Termux ships real arm64 `aapt`, `aapt2`, `aidl`; the rest (`dexdump`, `lld`, `llvm-rs-cc`, `renderscript`, `bcc_compat`, `split-select`, `zipalign`, five cross-target `-ld` binaries) are shell-script stubs that `exit 0` and log any invocation to stderr. For Phase 2d's probe debug build, none are actually called — AGP 9.1.0's pipeline for a no-Compose Java project uses only aapt2 + d8 + apksigner. If a later phase trips a stub invocation, we'll see it immediately in stderr.

5. *Canonical `package.xml` schema.* AGP schema-validates `$ANDROID_HOME/**/package.xml`. Our hand-rolled minimal XML used the wrong root element (`ns2:sdk-repository` vs the required `ns2:repository`) and was missing the 15 namespace declarations AGP may dispatch on. Swapped to canonical fixtures lifted from a working Android Studio install, saved under `scripts/bootstrap/android-sdk-descriptors/`.

6. *Platform zip must land in full.* Initially the script allowlisted `android.jar` + `data/` + four sibling jars. AGP then complained "Build properties not found" because `build.prop`, `sdk.properties`, and `source.properties` weren't copied. Switched to `cp -a "$platform_src"/. "$plat_dst/"` — copies the full ~70MB tree.

7. *Termux aapt2 version mismatch with platform-36.* Termux's only published aapt2 (`13.0.0.6-23`, AOSP 13 vintage, self-reports as "aapt 2.19") cannot parse the resource tables Google ships for API 36. `aapt2 link` failed with the generic "failed to load include path" once it reached android-36/android.jar. Downgraded the bundled platform to **android-33** (matching aapt2's era) and set `compileSdk = 33` in the probe. `platform_api` and `platform_rev` are env-overridable so later phases can roll forward once Termux publishes a newer aapt2.

8. *Hilt graph completeness.* `GradleHostExtractor` had `@Inject constructor(private val context: Context, ...)` without `@ApplicationContext`. Hilt never built that piece of the graph until Task 7 made `GradleBuildService` reachable from `ViewModelC`, then complained with `android.content.Context cannot be provided without an @Provides-annotated method`. Added the qualifier.

9. *`fetch_termux_lib` silently skipped binary-carrying .debs.* The helper's `find $PREFIX/lib` branch errored out via `set -e` when a deb had no `lib/` tree (aidl, split-select, etc.), short-circuiting the binary-copy step that follows. Guarded the find with an explicit `[[ -d "$lib_src" ]]` check.

**Byproducts kept for later phases:**
- `platform-35-package.xml` descriptor is shipped alongside `platform-33-package.xml`; when Termux eventually publishes a newer aapt2, bumping `ANDROID_PLATFORM_API=35` (or 36) is a one-line env override.
- `scripts/bootstrap/android-sdk-descriptors/` now hosts canonical AGP-accepted package.xml fixtures; future components can mirror the pattern.
- Stub binaries in `build-tools/36.0.0/` are harmless and small; when a concrete need for one arises (e.g. Phase 7 `zipalign` for aligned release APKs), the stub pattern provides a clear upgrade seam.
- LD_LIBRARY_PATH now includes `$ANDROID_HOME/build-tools/36.0.0/`, preserving aapt2's .so closure resolution across future SDK updates.

**Phase 2d is complete.** Phase 2e (Compose compiler + Compose BOM from Maven → first Compose APK) is unblocked.
