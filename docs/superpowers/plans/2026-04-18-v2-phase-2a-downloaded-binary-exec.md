# v2.0 Phase 2a: Downloaded Binary Exec Proof Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove that VibeApp (now at `targetSdk=28`) can download an arbitrary Android-compatible binary via the Phase 1 bootstrap pipeline and `exec` it from `filesDir` with stdout streaming back to the Kotlin caller. Two targets: (1) a synthetic `hello` ELF cross-compiled against Bionic with our NDK toolchain (pure proof); (2) a real OpenJDK 17 sourced from Termux's Bionic-built `.deb` packages, exec'd as `java -version`.

**Architecture:** No new modules. We add build scripts under `scripts/bootstrap/` to produce `.tar.zst` artifacts, a local development HTTP server (`python3 -m http.server`) + `adb reverse` for on-device dev iteration, a "Run Hello" and "Run java -version" button in `BuildRuntimeDebugScreen`, and two new instrumented tests opt-in via a system property (they need the dev server running to pass, so they don't fail CI).

**Tech Stack:** Existing bootstrap + process runtime (Phases 1a-1d). Android NDK 28.2 (already installed) for cross-compiling the `hello` binary. Termux's openjdk-17 debian package for the real JDK artifact. `adb reverse tcp:8000 tcp:8000` for the dev http channel (standard Android development workflow).

---

## Spec References

- Design doc: `docs/superpowers/specs/2026-04-18-v2-gradle-compose-arch-design.md`
- This plan is the first proof point of §10 Phase 2: "`GradleHost` + first APK". Before we can embed Gradle, we need empirical proof that exec-from-filesDir works with REAL (non-`/system/bin/`) binaries on `targetSdk=28`.
- Prior plans (completed): Phase 0, 1a, 1b, 1c, 1d.

## Working Directory

**All file operations happen in the git worktree:** `/Users/skykai/Documents/work/VibeApp/.worktrees/v2-arch`

Branch: `v2-arch`. Current HEAD at plan write time: `8bfa4ed`.

## File Structure

**Create:**

| File | Responsibility |
|---|---|
| `scripts/bootstrap/README.md` | How the scripts fit together |
| `scripts/bootstrap/build-hello.sh` | Cross-compile `hello.c` with NDK → repack as `hello-{abi}.tar.zst` (one per ABI) |
| `scripts/bootstrap/build-jdk.sh` | Fetch Termux openjdk-17 `.deb` for a given ABI, extract, repack as `jdk-17.0.13-{abi}.tar.zst` |
| `scripts/bootstrap/build-manifest.sh` | Compose `manifest.json` from input artifacts + sign with the dev key |
| `scripts/bootstrap/dev-serve.sh` | Launch local http server + `adb reverse` |
| `scripts/bootstrap/hello/hello.c` | Trivial `int main(void) { puts("hello from bootstrap"); return 0; }` |
| `scripts/bootstrap/sign-manifest.kts` | Kotlin script reading the dev seed from `~/.vibeapp/dev-bootstrap-privseed.hex` and emitting `manifest.json.sig` |
| `app/src/main/kotlin/com/vibe/app/feature/bootstrap/BuildRuntimeDebugViewModel.kt` (modify) | Add two actions: "Run `hello` from bootstrap" and "Run `java -version`" |
| `app/src/main/kotlin/com/vibe/app/feature/bootstrap/BuildRuntimeDebugScreen.kt` (modify) | Add the two buttons + log display |
| `app/src/main/kotlin/com/vibe/app/di/BuildRuntimeModule.kt` (modify) | Add an overridable `@Named("bootstrapManifestUrl")` provider (e.g. via a `DataStore`-backed debug setting) so the dev UI can point at `http://localhost:8000/manifest.json` instead of the production GitHub URL |
| `build-runtime/src/androidTest/kotlin/com/vibe/build/runtime/DownloadedBinaryExecInstrumentedTest.kt` | Two `@Test`s: `hello_binary_exec_after_bootstrap` and `jdk_java_version_after_bootstrap`. Both opt-in via system property `vibeapp.phase2a.dev_server_url`. |

**Modify:**

| File | Responsibility |
|---|---|
| `.gitignore` | Add `scripts/bootstrap/artifacts/` (staging dir for large intermediate files — never committed) |
| `docs/bootstrap/release-prep-checklist.md` | Cross-reference `scripts/bootstrap/` (the real-release pipeline is a hardened version of these scripts) |

**Do NOT touch in Phase 2a:**
- `:build-gradle` — no Gradle code yet; that's Phase 2b onward
- `:plugin-host` — Phase 5
- Any existing `build-engine/` or agent code
- The current Phase 1a synthetic-manifest integration test (still valid; this plan adds alongside it)

## Key Constraints (ALL TASKS)

1. **Artifacts must match the Phase 1a manifest schema** (`BootstrapManifest` / `ArchArtifact`) — the runtime does not know these are "new" artifacts. `componentId`s and `fileName`s must match what `ProcessEnvBuilder` hardcodes where relevant (`JDK_DIR_NAME = "jdk-17.0.13"`).
2. **Dev artifacts stored outside the repo**. `scripts/bootstrap/artifacts/` is gitignored; output tarballs are large (JDK per-ABI ~85MB each). Devs who want to run Phase 2a integration tests produce them locally.
3. **Dev server on localhost** over `adb reverse tcp:8000 tcp:8000`. On the device, `http://localhost:8000/...` resolves to the dev machine's 8000. No cleartext HTTP config change needed beyond Phase 1a's existing `build-runtime/src/debug/res/xml/network_security_config.xml` (which already allows localhost cleartext).
4. **Manifest URL overridable in debug mode**. Production manifest URL stays pinned to the GitHub Release URL. A debug-only DataStore key lets the debug screen point at `http://localhost:8000/manifest.json` for dev.
5. **Sign manifests with the Phase 1c dev keypair**. The pubkey in `BuildRuntimeModule.BOOTSTRAP_PUBKEY_HEX` already matches `~/.vibeapp/dev-bootstrap-privseed.hex`. `sign-manifest.kts` reuses that seed.
6. **Instrumented tests opt-in via system property** `vibeapp.phase2a.dev_server_url` — if unset, the test is `@Ignore`d. This keeps CI green when no dev server is running.
7. **No changes to production `MirrorSelector` URLs**. The dev override piggybacks on the existing `@Named("bootstrapManifestUrl")` string provider — we just swap what that provider returns based on a debug preference.

---

## Task 1: `scripts/bootstrap/` infrastructure

**Files:**
- Create: `scripts/bootstrap/README.md`
- Create: `scripts/bootstrap/hello/hello.c`
- Create: `scripts/bootstrap/build-hello.sh`
- Create: `scripts/bootstrap/sign-manifest.kts`
- Create: `scripts/bootstrap/build-manifest.sh`
- Create: `scripts/bootstrap/dev-serve.sh`
- Modify: `.gitignore`

### Step 1: `.gitignore` — ignore the staging dir

Append at the end (after the `/.worktrees` entry):

```
# Phase 2a dev bootstrap artifact staging (large, never committed)
/scripts/bootstrap/artifacts
```

### Step 2: Create `scripts/bootstrap/README.md`

```markdown
# Dev Bootstrap Artifact Pipeline

Scripts for producing `.tar.zst` bootstrap artifacts + signed manifest
for local Phase 2a / 2b / 2c testing. None of these artifacts get
committed; they're built per-dev locally and served via a local
http server.

The equivalent production pipeline (with real keys, GitHub Release
upload, Aliyun mirror sync) is in
`docs/bootstrap/release-prep-checklist.md`.

## Prerequisites

- Android NDK 28.2+ installed at
  `$ANDROID_SDK/ndk/28.2.13676358/` (set `$ANDROID_NDK_HOME` or
  override via `--ndk-path` flag).
- `zstd` command-line tool (`brew install zstd` on macOS).
- `ar` + `tar` + `sha256sum` — standard on Linux/macOS.
- For the real JDK: `curl` + a Termux mirror (default:
  `https://packages.termux.dev/apt/termux-main`).
- For manifest signing: `~/.vibeapp/dev-bootstrap-privseed.hex`
  exists (per `docs/bootstrap/dev-keypair-setup.md`).
- For serving: `python3` and `adb` on PATH.

## Workflow

```bash
# 1. Build the artifacts into scripts/bootstrap/artifacts/
./build-hello.sh --abi arm64-v8a
./build-hello.sh --abi armeabi-v7a
./build-hello.sh --abi x86_64
./build-jdk.sh   --abi arm64-v8a
# (optionally build other ABIs if testing on those devices)

# 2. Assemble + sign the manifest. Produces manifest.json + manifest.json.sig.
./build-manifest.sh

# 3. Serve + reverse-map to the device
./dev-serve.sh          # blocks; Ctrl-C to stop

# 4. In another terminal, run the app. In Settings → Build Runtime
#    (debug), tap "Dev: use localhost manifest", then "Trigger
#    bootstrap", then "Run hello" / "Run java -version".
```

## Output layout

```
scripts/bootstrap/artifacts/
├── manifest.json
├── manifest.json.sig
├── hello-arm64-v8a.tar.zst
├── hello-armeabi-v7a.tar.zst
├── hello-x86_64.tar.zst
├── jdk-17.0.13-arm64-v8a.tar.zst
└── jdk-17.0.13-armeabi-v7a.tar.zst   (optional)
```

Artifacts are consumed by:
- `BuildRuntimeDebugScreen` → `RuntimeBootstrapper` → `ProcessLauncher`
- `DownloadedBinaryExecInstrumentedTest` (opt-in via
  `-Pandroid.testInstrumentationRunnerArguments.vibeapp.phase2a.dev_server_url=http://localhost:8000`)
```

### Step 3: Create `scripts/bootstrap/hello/hello.c`

```c
/*
 * Trivial synthetic bootstrap artifact target.
 *
 * Compiled with the Android NDK against Bionic, this proves that an
 * arbitrary ELF binary downloaded to filesDir/usr/opt/<componentId>/
 * can be exec'd by VibeApp's ProcessLauncher. Output is simple enough
 * to regex in instrumented tests.
 */

#include <stdio.h>

int main(void) {
    puts("hello from bootstrap");
    return 0;
}
```

### Step 4: Create `scripts/bootstrap/build-hello.sh`

```bash
#!/usr/bin/env bash
#
# Cross-compiles hello.c for a target Android ABI, packages the
# resulting binary as hello-<abi>.tar.zst under scripts/bootstrap/artifacts/.

set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
artifacts_dir="$script_dir/artifacts"
hello_dir="$script_dir/hello"

ndk_home="${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/28.2.13676358}"
abi=""
min_api=29

while [[ $# -gt 0 ]]; do
    case "$1" in
        --abi) abi="$2"; shift 2;;
        --ndk-path) ndk_home="$2"; shift 2;;
        --min-api) min_api="$2"; shift 2;;
        -h|--help)
            cat <<EOF
Usage: $0 --abi <arm64-v8a|armeabi-v7a|x86_64> [--ndk-path PATH] [--min-api N]

Cross-compiles scripts/bootstrap/hello/hello.c for the given Android ABI
and writes scripts/bootstrap/artifacts/hello-<abi>.tar.zst.
EOF
            exit 0;;
        *) echo "Unknown arg: $1" >&2; exit 2;;
    esac
done

[[ -n "$abi" ]] || { echo "--abi is required" >&2; exit 2; }
[[ -d "$ndk_home" ]] || { echo "NDK not found at $ndk_home" >&2; exit 2; }

host_os="$(uname -s | tr '[:upper:]' '[:lower:]')"
case "$host_os" in
    darwin) host_tag="darwin-x86_64";;
    linux)  host_tag="linux-x86_64";;
    *) echo "Unsupported host: $host_os" >&2; exit 2;;
esac

case "$abi" in
    arm64-v8a)    clang_target="aarch64-linux-android${min_api}";;
    armeabi-v7a)  clang_target="armv7a-linux-androideabi${min_api}";;
    x86_64)       clang_target="x86_64-linux-android${min_api}";;
    *) echo "Unsupported ABI: $abi" >&2; exit 2;;
esac

toolchain="$ndk_home/toolchains/llvm/prebuilt/$host_tag"
clang="$toolchain/bin/clang"
[[ -x "$clang" ]] || { echo "clang not executable at $clang" >&2; exit 2; }

mkdir -p "$artifacts_dir"
staging="$(mktemp -d -t vibeapp-hello.XXXXXXXX)"
trap 'rm -rf "$staging"' EXIT

# Component layout: bin/hello, matching how BootstrapFileSystem installs
# into usr/opt/<componentId>/. We install this component as
# componentId = "hello", so the final path is
# filesDir/usr/opt/hello/bin/hello.
mkdir -p "$staging/bin"
"$clang" --target="$clang_target" \
         -Wall -Wextra -Werror \
         -O2 -static-libstdc++ \
         -o "$staging/bin/hello" \
         "$hello_dir/hello.c"
chmod +x "$staging/bin/hello"

out="$artifacts_dir/hello-$abi.tar.zst"
(cd "$staging" && tar -cf - .) | zstd -19 -q -o "$out"
sha256=$(shasum -a 256 "$out" | awk '{print $1}')
size=$(stat -f %z "$out" 2>/dev/null || stat -c %s "$out")
echo "$out"
echo "  size=$size sha256=$sha256"
```

Make it executable:

```bash
chmod +x scripts/bootstrap/build-hello.sh
```

### Step 5: Create `scripts/bootstrap/sign-manifest.kts`

```kotlin
@file:DependsOn("net.i2p.crypto:eddsa:0.3.0")

import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import java.io.File

/**
 * Signs manifest.json with the dev Ed25519 seed at
 * ~/.vibeapp/dev-bootstrap-privseed.hex. Writes manifest.json.sig next
 * to the input file.
 *
 * Usage: kotlin sign-manifest.kts <path/to/manifest.json>
 */

fun die(msg: String): Nothing {
    System.err.println("sign-manifest: $msg")
    kotlin.system.exitProcess(2)
}

val argv = args
if (argv.size != 1) die("usage: sign-manifest.kts <manifest.json>")

val manifest = File(argv[0])
if (!manifest.isFile) die("manifest not found: ${manifest.absolutePath}")

val seedFile = File(System.getProperty("user.home"), ".vibeapp/dev-bootstrap-privseed.hex")
if (!seedFile.isFile) die("dev seed not found at $seedFile; see docs/bootstrap/dev-keypair-setup.md")

val seedHex = seedFile.readText().trim()
require(seedHex.length == 64) { "dev seed must be 64 hex chars, got ${seedHex.length}" }
val seed = ByteArray(32) { i ->
    ((Character.digit(seedHex[i * 2], 16) shl 4) or Character.digit(seedHex[i * 2 + 1], 16)).toByte()
}

val spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
val priv = EdDSAPrivateKey(EdDSAPrivateKeySpec(seed, spec))
val sig = EdDSAEngine().apply { initSign(priv) }.run {
    update(manifest.readBytes())
    sign()
}

val sigFile = File(manifest.parentFile, "${manifest.name}.sig")
sigFile.writeBytes(sig)
println("wrote ${sigFile.absolutePath} (${sig.size} bytes)")
```

### Step 6: Create `scripts/bootstrap/build-manifest.sh`

```bash
#!/usr/bin/env bash
#
# Composes scripts/bootstrap/artifacts/manifest.json from the
# .tar.zst artifacts in that directory, then signs it into manifest.json.sig
# using the dev keypair.

set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
artifacts_dir="$script_dir/artifacts"
manifest="$artifacts_dir/manifest.json"
version="v2.0.0-dev"

[[ -d "$artifacts_dir" ]] || { echo "No artifacts dir; run build-*.sh first." >&2; exit 2; }

# Emit header
{
    echo "{"
    echo "  \"schemaVersion\": 1,"
    echo "  \"manifestVersion\": \"$version\","
    echo "  \"components\": ["
} > "$manifest"

# Enumerate components (grouped by prefix in the filename).
# Supported prefixes: "hello-" -> id "hello"; "jdk-17.0.13-" -> id "jdk-17.0.13".
first=1
for id in "hello" "jdk-17.0.13"; do
    # Collect matching artifacts
    entries=""
    for artifact in "$artifacts_dir/${id}-"*.tar.zst; do
        [[ -f "$artifact" ]] || continue
        fname=$(basename "$artifact")
        abi=$(echo "$fname" | sed -E "s/^${id}-([^.]+)\.tar\.zst$/\1/")
        size=$(stat -f %z "$artifact" 2>/dev/null || stat -c %s "$artifact")
        sha=$(shasum -a 256 "$artifact" | awk '{print $1}')
        entries="$entries        \"$abi\": { \"fileName\": \"$fname\", \"sizeBytes\": $size, \"sha256\": \"$sha\" },"$'\n'
    done
    [[ -z "$entries" ]] && continue
    # Trim trailing comma + newline
    entries="$(echo -n "${entries%$'\n'}" | sed '$ s/,$//')"

    version_field=""
    if [[ "$id" == "jdk-17.0.13" ]]; then version_field="17.0.13"; fi
    if [[ "$id" == "hello"       ]]; then version_field="1.0"; fi

    [[ $first -eq 1 ]] || echo "    ," >> "$manifest"
    {
        echo "    {"
        echo "      \"id\": \"$id\","
        echo "      \"version\": \"$version_field\","
        echo "      \"artifacts\": {"
        echo "$entries"
        echo "      }"
        echo "    }"
    } >> "$manifest"
    first=0
done

{
    echo "  ]"
    echo "}"
} >> "$manifest"

echo "wrote $manifest"

# Sign
kotlin "$script_dir/sign-manifest.kts" "$manifest"
```

Make executable:

```bash
chmod +x scripts/bootstrap/build-manifest.sh
```

### Step 7: Create `scripts/bootstrap/dev-serve.sh`

```bash
#!/usr/bin/env bash
#
# Serves scripts/bootstrap/artifacts/ over http://localhost:8000 and
# maps it through to the connected Android device via
# `adb reverse tcp:8000 tcp:8000`.

set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
artifacts_dir="$script_dir/artifacts"
port="${PORT:-8000}"

[[ -d "$artifacts_dir" ]] || { echo "No artifacts; run build-*.sh first." >&2; exit 2; }

adb reverse "tcp:$port" "tcp:$port"
echo "adb reverse OK: device localhost:$port -> host :$port"
echo "Serving $artifacts_dir on http://localhost:$port ..."
echo "(Ctrl-C to stop; adb reverse is auto-removed on exit.)"
trap 'adb reverse --remove tcp:'"$port"' || true; exit' INT TERM
cd "$artifacts_dir"
python3 -m http.server "$port"
```

```bash
chmod +x scripts/bootstrap/dev-serve.sh
```

### Step 8: Verify everything is well-formed

```bash
bash -n scripts/bootstrap/build-hello.sh
bash -n scripts/bootstrap/build-manifest.sh
bash -n scripts/bootstrap/dev-serve.sh
```

Expected: no errors.

### Step 9: Commit

```bash
git add scripts/bootstrap/ .gitignore
git commit -m "feat(scripts): dev bootstrap artifact pipeline (Phase 2a Task 1)

- build-hello.sh: cross-compile a trivial hello.c with NDK 28.2 for
  arm64-v8a / armeabi-v7a / x86_64 and repack as .tar.zst.
- sign-manifest.kts: sign manifest.json with the dev Ed25519 seed
  at ~/.vibeapp/dev-bootstrap-privseed.hex.
- build-manifest.sh: compose manifest.json from staged artifacts +
  call the signer.
- dev-serve.sh: python3 http.server + adb reverse on port 8000.
- README documents the end-to-end flow.
- Staging dir scripts/bootstrap/artifacts/ gitignored.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: `build-jdk.sh` — repack Termux openjdk-17

**Files:**
- Create: `scripts/bootstrap/build-jdk.sh`

Termux publishes pre-built OpenJDK 17 debian packages for arm64-v8a,
armeabi-v7a, and x86_64. They're built against Bionic, so they load
and run on Android. We download, extract the JDK tree, strip unused
bits, and repack as `.tar.zst`.

### Step 1: Create `scripts/bootstrap/build-jdk.sh`

```bash
#!/usr/bin/env bash
#
# Downloads Termux's openjdk-17 .deb for the target ABI, extracts the
# JDK tree under termux's $PREFIX/lib/jvm/java-17-openjdk, strips
# unused pieces, and repacks as jdk-17.0.13-<abi>.tar.zst.

set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
artifacts_dir="$script_dir/artifacts"

abi=""
# Default Termux mirror; can be overridden via --mirror.
mirror="${TERMUX_MIRROR:-https://packages.termux.dev/apt/termux-main}"
# Default jdk package version. Intentionally matches
# ProcessEnvBuilder.JDK_DIR_NAME = "jdk-17.0.13".
jdk_version="${JDK_VERSION:-17.0.13_p11-0}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --abi) abi="$2"; shift 2;;
        --mirror) mirror="$2"; shift 2;;
        --jdk-version) jdk_version="$2"; shift 2;;
        -h|--help)
            cat <<EOF
Usage: $0 --abi <arm64-v8a|armeabi-v7a|x86_64>
          [--mirror URL] [--jdk-version VER]

Downloads Termux openjdk-17 .deb, repacks as
scripts/bootstrap/artifacts/jdk-17.0.13-<abi>.tar.zst.

Defaults:
  mirror:      $mirror
  jdk-version: $jdk_version  (override if upstream moves)
EOF
            exit 0;;
        *) echo "Unknown arg: $1" >&2; exit 2;;
    esac
done

[[ -n "$abi" ]] || { echo "--abi is required" >&2; exit 2; }

# Termux package architecture naming — distinct from Android's ABI codes.
case "$abi" in
    arm64-v8a)   termux_arch="aarch64";;
    armeabi-v7a) termux_arch="arm";;
    x86_64)      termux_arch="x86_64";;
    *) echo "Unsupported ABI: $abi" >&2; exit 2;;
esac

deb_name="openjdk-17_${jdk_version}_${termux_arch}.deb"
deb_url="$mirror/pool/main/o/openjdk-17/$deb_name"

mkdir -p "$artifacts_dir"
staging="$(mktemp -d -t vibeapp-jdk.XXXXXXXX)"
trap 'rm -rf "$staging"' EXIT

echo "Downloading $deb_url ..."
curl -fSL -o "$staging/$deb_name" "$deb_url"

# .deb is an ar archive: debian-binary, control.tar.*, data.tar.*
echo "Extracting .deb ..."
(cd "$staging" && ar x "$deb_name")

# Termux's data.tar is xz-compressed in recent releases; handle either.
if   [[ -f "$staging/data.tar.xz" ]]; then data_tar="data.tar.xz"
elif [[ -f "$staging/data.tar.zst" ]]; then data_tar="data.tar.zst"
elif [[ -f "$staging/data.tar" ]];    then data_tar="data.tar"
else echo ".deb contained unknown data.tar variant" >&2; exit 2; fi

unpack="$staging/unpack"
mkdir -p "$unpack"
tar -xf "$staging/$data_tar" -C "$unpack"

# Termux installs the JDK at $PREFIX/lib/jvm/java-17-openjdk where
# $PREFIX = data/data/com.termux/files/usr. We strip that prefix.
jdk_src=""
for candidate in \
    "$unpack/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk" \
    "$unpack/data/data/com.termux/files/usr/opt/openjdk"; do
    if [[ -d "$candidate" ]]; then jdk_src="$candidate"; break; fi
done
[[ -n "$jdk_src" ]] || { echo "JDK tree not found in .deb; check layout" >&2; find "$unpack" -type d -name "*openjdk*" 2>/dev/null | head; exit 2; }

# Strip docs / man / legal / sample trees that aren't needed at runtime.
for d in demo man sample src.zip legal; do
    rm -rf "$jdk_src/$d" || true
done
find "$jdk_src" -name "*.diz" -delete 2>/dev/null || true

out="$artifacts_dir/jdk-17.0.13-$abi.tar.zst"
(cd "$(dirname "$jdk_src")" && tar -cf - "$(basename "$jdk_src")") \
    | zstd -19 -q -o "$out"
sha=$(shasum -a 256 "$out" | awk '{print $1}')
size=$(stat -f %z "$out" 2>/dev/null || stat -c %s "$out")
echo "$out"
echo "  size=$size sha256=$sha"
```

Make it executable:

```bash
chmod +x scripts/bootstrap/build-jdk.sh
```

### Step 2: Smoke-test script syntax

```bash
bash -n scripts/bootstrap/build-jdk.sh
```

Expected: no errors. (We do NOT run the script here — it would download ~85MB and needs network access; the integration test in Task 5 exercises this path.)

### Step 3: Commit

```bash
git add scripts/bootstrap/build-jdk.sh
git commit -m "feat(scripts): build-jdk.sh sources Termux openjdk-17 .deb

Downloads the Termux repo's pre-built openjdk-17 .deb for a target
ABI, extracts the JDK tree under \$PREFIX/lib/jvm/java-17-openjdk,
strips unused doc/sample/man/src bits, and repacks as
jdk-17.0.13-<abi>.tar.zst.

Termux's Bionic-built OpenJDK is the only practical source for an
Android-compatible JDK — stock Temurin is built against glibc and
won't load on Android.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: Debug UI — Bootstrap URL override + "Run hello" / "Run java" buttons

**Files:**
- Modify: `app/src/main/kotlin/com/vibe/app/data/datastore/SettingDataSource.kt` + `SettingDataSourceImpl.kt`
- Modify: `app/src/main/kotlin/com/vibe/app/di/BuildRuntimeModule.kt`
- Modify: `app/src/main/kotlin/com/vibe/app/feature/bootstrap/BuildRuntimeDebugState.kt`
- Modify: `app/src/main/kotlin/com/vibe/app/feature/bootstrap/BuildRuntimeDebugViewModel.kt`
- Modify: `app/src/main/kotlin/com/vibe/app/feature/bootstrap/BuildRuntimeDebugScreen.kt`

### Step 1: Extend `SettingDataSource` with a dev manifest URL

Read `app/src/main/kotlin/com/vibe/app/data/datastore/SettingDataSource.kt`. Append two new functions:

```kotlin
    suspend fun getDevBootstrapManifestUrl(): String?
    suspend fun updateDevBootstrapManifestUrl(url: String?)
```

Read `SettingDataSourceImpl.kt`. Inside the class body (alongside `debugModeKey`), add:

```kotlin
    private val devBootstrapManifestUrlKey = stringPreferencesKey("dev_bootstrap_manifest_url")
```

Add the matching implementations at the end of the class:

```kotlin
    override suspend fun getDevBootstrapManifestUrl(): String? =
        dataStore.data.map { it[devBootstrapManifestUrlKey] }.first()

    override suspend fun updateDevBootstrapManifestUrl(url: String?) {
        dataStore.edit { prefs ->
            if (url.isNullOrBlank()) prefs.remove(devBootstrapManifestUrlKey)
            else prefs[devBootstrapManifestUrlKey] = url
        }
    }
```

(Add `import androidx.datastore.preferences.core.stringPreferencesKey` at the top if missing.)

### Step 2: Route the manifest URL provider through `SettingDataSource`

Read `app/src/main/kotlin/com/vibe/app/di/BuildRuntimeModule.kt`. Locate `provideBootstrapManifestUrl`. Replace it with a version that blocks on the DataStore read once at app startup + logs which URL won the race:

```kotlin
    @Provides
    @Singleton
    @Named("bootstrapManifestUrl")
    fun provideBootstrapManifestUrl(
        settingDataSource: com.vibe.app.data.datastore.SettingDataSource,
    ): String {
        val override = kotlinx.coroutines.runBlocking {
            settingDataSource.getDevBootstrapManifestUrl()
        }
        return override
            ?: "https://github.com/Skykai521/VibeApp/releases/download/v2.0.0/manifest.json"
    }
```

`runBlocking` on a Singleton provider is acceptable here: the call only runs once per app process, and DataStore reads of a single-string preference complete in < 5ms.

### Step 3: Extend `BuildRuntimeDebugState`

Read `app/src/main/kotlin/com/vibe/app/feature/bootstrap/BuildRuntimeDebugState.kt`. Replace with:

```kotlin
package com.vibe.app.feature.bootstrap

import com.vibe.build.runtime.bootstrap.BootstrapState

/**
 * UI-facing state for the debug Build Runtime screen.
 */
data class BuildRuntimeDebugState(
    val bootstrap: BootstrapState = BootstrapState.NotInstalled,
    val manifestUrl: String = "",
    val devOverrideUrl: String = "",
    val launchLog: String = "",
    val launchRunning: Boolean = false,
)
```

### Step 4: Extend `BuildRuntimeDebugViewModel`

Read `BuildRuntimeDebugViewModel.kt`. Replace the class body with the following (imports may need tweaks; keep the existing ones plus add):

```kotlin
package com.vibe.app.feature.bootstrap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibe.app.data.datastore.SettingDataSource
import com.vibe.build.runtime.bootstrap.BootstrapFileSystem
import com.vibe.build.runtime.bootstrap.BootstrapState
import com.vibe.build.runtime.bootstrap.BootstrapStateStore
import com.vibe.build.runtime.bootstrap.RuntimeBootstrapper
import com.vibe.build.runtime.process.ProcessEvent
import com.vibe.build.runtime.process.ProcessLauncher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Named

@HiltViewModel
class BuildRuntimeDebugViewModel @Inject constructor(
    private val bootstrapper: RuntimeBootstrapper,
    private val store: BootstrapStateStore,
    private val launcher: ProcessLauncher,
    private val fs: BootstrapFileSystem,
    private val settingDataSource: SettingDataSource,
    @Named("bootstrapManifestUrl") private val manifestUrl: String,
    @Named("appCacheDir") private val cacheDir: File,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BuildRuntimeDebugState(manifestUrl = manifestUrl))
    val uiState: StateFlow<BuildRuntimeDebugState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            store.state.collectLatest { bootstrapState ->
                _uiState.update { it.copy(bootstrap = bootstrapState) }
            }
        }
        viewModelScope.launch {
            val override = settingDataSource.getDevBootstrapManifestUrl().orEmpty()
            _uiState.update { it.copy(devOverrideUrl = override) }
        }
    }

    fun triggerBootstrap() {
        viewModelScope.launch {
            bootstrapper.bootstrap(manifestUrl = manifestUrl) { }
        }
    }

    fun setDevOverrideUrl(newUrl: String) {
        viewModelScope.launch {
            val trimmed = newUrl.trim().ifEmpty { null }
            settingDataSource.updateDevBootstrapManifestUrl(trimmed)
            _uiState.update { it.copy(devOverrideUrl = newUrl) }
        }
    }

    fun launchTestProcess() {
        viewModelScope.launch {
            _uiState.update { it.copy(launchRunning = true, launchLog = "") }
            val log = StringBuilder()
            try {
                val proc = launcher.launch(
                    executable = "/system/bin/toybox",
                    args = listOf("echo", "debug-launch OK"),
                    cwd = cacheDir,
                )
                proc.events.collect { ev ->
                    appendEvent(log, ev)
                }
            } catch (t: Throwable) {
                log.append("[error] ${t.message}")
            }
            _uiState.update { it.copy(launchRunning = false, launchLog = log.toString()) }
        }
    }

    fun runHello() {
        viewModelScope.launch {
            _uiState.update { it.copy(launchRunning = true, launchLog = "") }
            val helloBinary = File(fs.componentInstallDir("hello"), "bin/hello")
            val log = StringBuilder()
            if (!helloBinary.isFile) {
                log.append("[error] $helloBinary not found — run Trigger bootstrap first")
            } else {
                try {
                    val proc = launcher.launch(
                        executable = helloBinary.absolutePath,
                        args = emptyList(),
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

    fun runJavaVersion() {
        viewModelScope.launch {
            _uiState.update { it.copy(launchRunning = true, launchLog = "") }
            val javaBinary = File(fs.componentInstallDir("jdk-17.0.13"), "bin/java")
            val log = StringBuilder()
            if (!javaBinary.isFile) {
                log.append("[error] $javaBinary not found — run Trigger bootstrap first")
            } else {
                try {
                    val proc = launcher.launch(
                        executable = javaBinary.absolutePath,
                        args = listOf("-version"),
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

    private fun appendEvent(log: StringBuilder, ev: ProcessEvent) {
        when (ev) {
            is ProcessEvent.Stdout -> log.append(String(ev.bytes, Charsets.UTF_8))
            is ProcessEvent.Stderr -> log.append("[stderr] ").append(String(ev.bytes, Charsets.UTF_8))
            is ProcessEvent.Exited -> log.append("\n[exit ${ev.code}]")
        }
    }
}
```

Note: `BootstrapFileSystem` was not previously injected into this ViewModel — it needs to be provided. It already has `@Inject constructor` (see Phase 1a Task 3), so Hilt resolves it automatically.

### Step 5: Extend `BuildRuntimeDebugScreen`

Add a URL text field, "Run hello" button, and "Run java -version" button.

Read the existing `BuildRuntimeDebugScreen.kt`. In the `Column { ... }` body, between the "Launch toybox echo" button and the `launchLog` display, insert:

```kotlin
            // Dev manifest URL override
            OutlinedTextField(
                value = ui.devOverrideUrl,
                onValueChange = viewModel::setDevOverrideUrl,
                label = { Text("Dev manifest URL override (empty → production)") },
                placeholder = { Text("http://localhost:8000/manifest.json") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                "Active manifest URL: ${ui.manifestUrl}",
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider()

            Text("Downloaded-binary exec", style = MaterialTheme.typography.labelMedium)
            Button(
                onClick = viewModel::runHello,
                enabled = !ui.launchRunning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Run hello (from bootstrap)")
            }
            Button(
                onClick = viewModel::runJavaVersion,
                enabled = !ui.launchRunning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Run java -version")
            }
```

Add imports:

```kotlin
import androidx.compose.material3.OutlinedTextField
```

(keep any existing imports; HorizontalDivider / MaterialTheme etc. are already present).

### Step 6: Verify the graph compiles

```bash
cd /Users/skykai/Documents/work/VibeApp/.worktrees/v2-arch
./gradlew --no-daemon :app:kspDebugKotlin :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

### Step 7: Commit

```bash
git add app/src/main/kotlin/com/vibe/app/data/datastore/ \
        app/src/main/kotlin/com/vibe/app/di/BuildRuntimeModule.kt \
        app/src/main/kotlin/com/vibe/app/feature/bootstrap/
git commit -m "feat(debug-ui): manifest URL override + run hello/java buttons

BuildRuntimeDebugScreen gains:
- A DataStore-backed text field for overriding the bootstrap manifest
  URL at runtime (empty → production GitHub URL; filled → custom dev
  URL, e.g. http://localhost:8000/manifest.json for adb-reverse dev).
- 'Run hello (from bootstrap)' button — execs
  filesDir/usr/opt/hello/bin/hello and streams stdout.
- 'Run java -version' button — execs
  filesDir/usr/opt/jdk-17.0.13/bin/java with -version.

@Named('bootstrapManifestUrl') provider now reads the DataStore
override once at app startup; falls back to the production URL.

Phase 2a Task 3.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Instrumented `DownloadedBinaryExecInstrumentedTest`

**Files:**
- Create: `build-runtime/src/androidTest/kotlin/com/vibe/build/runtime/DownloadedBinaryExecInstrumentedTest.kt`

This test is **opt-in**. It requires the dev server to be running with artifacts produced by Tasks 1–2. When `-Pandroid.testInstrumentationRunnerArguments.vibeapp.phase2a.dev_server_url` is not set, tests are skipped via `Assume.assumeTrue`. When set (e.g. `http://localhost:8000`), tests download and exec real binaries.

### Step 1: Create `DownloadedBinaryExecInstrumentedTest.kt`

```kotlin
package com.vibe.build.runtime

import android.content.Context
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
import com.vibe.app.di.BuildRuntimeModule
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
 * Phase 2a acceptance. Opt-in: set the instrumentation runner argument
 * `vibeapp.phase2a.dev_server_url` to the base URL of a dev server
 * running `scripts/bootstrap/dev-serve.sh`. Example:
 *
 *     ./gradlew :build-runtime:connectedDebugAndroidTest \
 *         -Pandroid.testInstrumentationRunnerArguments.vibeapp.phase2a.dev_server_url=http://localhost:8000
 *
 * Without that argument, both tests are skipped (`assumeTrue` aborts
 * before touching the network). This keeps CI green even though no
 * dev server is accessible there.
 *
 * Signature key: the dev server's manifest must be signed with the
 * same Ed25519 seed as BuildRuntimeModule.BOOTSTRAP_PUBKEY_HEX —
 * i.e. ~/.vibeapp/dev-bootstrap-privseed.hex (see
 * docs/bootstrap/dev-keypair-setup.md). The `scripts/bootstrap/
 * sign-manifest.kts` tool guarantees this.
 */
@RunWith(AndroidJUnit4::class)
class DownloadedBinaryExecInstrumentedTest {

    private lateinit var ctx: Context
    private lateinit var scratchDir: File
    private lateinit var fs: BootstrapFileSystem
    private lateinit var launcher: NativeProcessLauncher
    private lateinit var bootstrapper: RuntimeBootstrapper

    private fun devServerUrlOrSkip(): String {
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("vibeapp.phase2a.dev_server_url")
        assumeTrue(
            "vibeapp.phase2a.dev_server_url not provided; skipping Phase 2a acceptance test",
            !url.isNullOrBlank(),
        )
        return url!!.trimEnd('/')
    }

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        scratchDir = File(ctx.cacheDir, "phase2a-${System.nanoTime()}")
        require(scratchDir.mkdirs())

        fs = BootstrapFileSystem(filesDir = scratchDir)
        fs.ensureDirectories()

        val preloadLib = PreloadLibLocator(
            File(ctx.applicationInfo.nativeLibraryDir),
        )
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
        val signature = ManifestSignature(
            publicKeyHex = BuildRuntimeModule.BOOTSTRAP_PUBKEY_HEX,
        )
        return RuntimeBootstrapper(
            fs = fs,
            store = store,
            parser = ManifestParser(),
            signature = signature,
            mirrors = mirrors,
            downloader = BootstrapDownloader(),
            extractor = ZstdExtractor(),
            abi = Abi.pickPreferred(android.os.Build.SUPPORTED_ABIS) ?: Abi.ARM64,
            fetcher = ManifestFetcher(mirrors),
            parsedManifestOverride = null,
        )
    }

    @Test
    fun hello_binary_exec_after_bootstrap() = runBlocking {
        val devUrl = devServerUrlOrSkip()
        bootstrapper = buildBootstrapper(devUrl)

        val seenStates = mutableListOf<BootstrapState>()
        bootstrapper.bootstrap(manifestUrl = "manifest.json") { seenStates += it }
        val terminal = seenStates.last()
        assertTrue("expected Ready, got $terminal", terminal is BootstrapState.Ready)

        val helloBinary = File(fs.componentInstallDir("hello"), "bin/hello")
        assertTrue("hello binary missing at $helloBinary", helloBinary.isFile)

        val process = launcher.launch(
            executable = helloBinary.absolutePath,
            args = emptyList(),
            cwd = scratchDir,
        )
        val events = withTimeout(10_000) { process.events.toList() }

        val stdout = events.filterIsInstance<ProcessEvent.Stdout>()
            .fold(ByteArray(0)) { acc, e -> acc + e.bytes }
        val exit = events.filterIsInstance<ProcessEvent.Exited>().first()
        val stdoutText = String(stdout, Charsets.UTF_8)

        assertEquals("hello exit=$exit stdout=$stdoutText", 0, exit.code)
        assertEquals("hello from bootstrap\n", stdoutText)
    }

    @Test
    fun jdk_java_version_after_bootstrap() = runBlocking {
        val devUrl = devServerUrlOrSkip()
        bootstrapper = buildBootstrapper(devUrl)

        val seenStates = mutableListOf<BootstrapState>()
        bootstrapper.bootstrap(manifestUrl = "manifest.json") { seenStates += it }
        val terminal = seenStates.last()
        assertTrue("expected Ready, got $terminal", terminal is BootstrapState.Ready)

        val javaBinary = File(fs.componentInstallDir("jdk-17.0.13"), "bin/java")
        assertTrue("java binary missing at $javaBinary", javaBinary.isFile)

        val process = launcher.launch(
            executable = javaBinary.absolutePath,
            args = listOf("-version"),
            cwd = scratchDir,
        )
        // java -version prints to STDERR, not stdout. Allow up to 30s
        // because first-run class loading is slow on the emulator.
        val events = withTimeout(30_000) { process.events.toList() }

        val stdout = events.filterIsInstance<ProcessEvent.Stdout>()
            .fold(ByteArray(0)) { acc, e -> acc + e.bytes }
        val stderr = events.filterIsInstance<ProcessEvent.Stderr>()
            .fold(ByteArray(0)) { acc, e -> acc + e.bytes }
        val exit = events.filterIsInstance<ProcessEvent.Exited>().first()

        val combined = String(stdout, Charsets.UTF_8) + String(stderr, Charsets.UTF_8)
        assertEquals("java -version exit=$exit combined=$combined", 0, exit.code)
        assertTrue(
            "expected '17.0.' in java -version output: $combined",
            combined.contains("17.0."),
        )
    }
}
```

### Step 2: Compile (sanity check)

```bash
./gradlew --no-daemon :build-runtime:compileDebugAndroidTestKotlin
```

Expected: BUILD SUCCESSFUL.

### Step 3: Commit (test runs deferred to Task 5 manual)

```bash
git add build-runtime/src/androidTest/kotlin/com/vibe/build/runtime/DownloadedBinaryExecInstrumentedTest.kt
git commit -m "test(build-runtime): Phase 2a acceptance for downloaded binaries

Two opt-in instrumented tests, skipped when the instrumentation
runner argument 'vibeapp.phase2a.dev_server_url' is not set:

 - hello_binary_exec_after_bootstrap: bootstraps a synthetic
   Bionic-linked 'hello' binary built by build-hello.sh, execs it
   from filesDir, asserts 'hello from bootstrap\\n' + exit 0.
 - jdk_java_version_after_bootstrap: bootstraps a Termux-sourced
   openjdk-17 .tar.zst, execs \$PREFIX/opt/jdk-17.0.13/bin/java
   -version, asserts '17.0.' in combined stdout+stderr + exit 0.

The tests use assumeTrue to short-circuit when no dev server URL
is given — so CI (which has no local dev server) stays green.

Phase 2a Task 4.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: Manual end-to-end validation + Phase 2a exit gate

This task is **not code** — it's running the acceptance loop manually once, capturing the output, and recording a Phase 2a exit note.

**Prerequisites:**
- `~/.vibeapp/dev-bootstrap-privseed.hex` present (Phase 1c Task 2).
- Android emulator or device connected (`adb devices`).
- `python3`, `curl`, `zstd`, `kotlin` (kotlinc 1.9+) on PATH.

### Step 1: Produce the synthetic hello artifact for the test device's ABI

Identify the emulator's ABI:

```bash
/Users/skykai/Library/Android/sdk/platform-tools/adb shell getprop ro.product.cpu.abi
```

Expected output: one of `arm64-v8a`, `armeabi-v7a`, `x86_64`. For the Pixel_7_Pro_API_31 AVD used throughout Phase 1, this is `arm64-v8a`.

Build:

```bash
cd /Users/skykai/Documents/work/VibeApp/.worktrees/v2-arch
scripts/bootstrap/build-hello.sh --abi arm64-v8a
```

Expected stdout: `scripts/bootstrap/artifacts/hello-arm64-v8a.tar.zst` path + size + sha256.

### Step 2: Produce the real JDK artifact

```bash
scripts/bootstrap/build-jdk.sh --abi arm64-v8a
```

Expected: `scripts/bootstrap/artifacts/jdk-17.0.13-arm64-v8a.tar.zst` (~80MB).

**If this step fails** with 404: Termux's archive layout may have changed. Check https://packages.termux.dev/apt/termux-main/pool/main/o/openjdk-17/ for the current available `openjdk-17_<VER>_aarch64.deb` and re-run with `--jdk-version <that VER>`. If that still fails, STOP and escalate — this indicates the Termux mirror moved and our script needs a small patch.

### Step 3: Compose + sign the manifest

```bash
scripts/bootstrap/build-manifest.sh
```

Expected: `scripts/bootstrap/artifacts/manifest.json` + `manifest.json.sig` created, and the sign step prints `wrote .../manifest.json.sig (64 bytes)`.

### Step 4: Start the dev server

In one terminal:

```bash
scripts/bootstrap/dev-serve.sh
```

Expected output:

```
adb reverse OK: device localhost:8000 -> host :8000
Serving .../artifacts on http://localhost:8000 ...
```

Leave running. Verify from another terminal:

```bash
/Users/skykai/Library/Android/sdk/platform-tools/adb shell curl -sI http://localhost:8000/manifest.json
```

Expected: `HTTP/1.0 200 OK`.

### Step 5: Run the opt-in instrumented tests

In another terminal:

```bash
./gradlew --no-daemon :build-runtime:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.vibe.build.runtime.DownloadedBinaryExecInstrumentedTest \
    -Pandroid.testInstrumentationRunnerArguments.vibeapp.phase2a.dev_server_url=http://localhost:8000
```

Expected: 2 tests, 0 failures. First test (hello) takes ~5 seconds. Second test (java) takes 30-60 seconds including JDK first-boot initialization.

### Step 6: Manual UI smoke test via the debug screen

1. Install the debug APK: `./gradlew --no-daemon :app:installDebug`
2. Open the app, navigate to Settings → Build Runtime (debug).
3. Paste `http://localhost:8000/manifest.json` into the "Dev manifest URL override" field.
4. Tap "Trigger bootstrap" — watch the state cycle through Downloading → Verifying → Unpacking → Installing → Ready.
5. Tap "Run hello (from bootstrap)" — log shows `hello from bootstrap` + `[exit 0]`.
6. Tap "Run java -version" — log shows e.g. `openjdk version "17.0.13" ...` + `[exit 0]`.

### Step 7: Record the exit

Edit `docs/superpowers/plans/2026-04-18-v2-phase-2a-downloaded-binary-exec.md` (this file) and append a final section:

```markdown
---

## Phase 2a Exit Log

Completed: YYYY-MM-DD

Test device: [e.g. Pixel_7_Pro_API_31 AVD, API 31, arm64-v8a]

Manual UI smoke: [PASS / FAIL with notes]
Instrumented tests: [PASS / FAIL with notes — include instrumented
    test count and total test time]

Real JDK version observed: [exact output of `java -version`]

Anomalies or follow-ups: [any warnings from Termux JDK, any
    permissions issues, anything to track before Phase 2b]
```

### Step 8: Commit the exit log

```bash
git add docs/superpowers/plans/2026-04-18-v2-phase-2a-downloaded-binary-exec.md
git commit -m "docs(phase 2a): record exit log + manual validation

Phase 2a acceptance: bootstrap + exec of both a synthetic NDK-built
'hello' binary AND Termux's openjdk-17 verified end-to-end on
[device]. java -version returns 17.0.x as expected. targetSdk=28
+ libtermux-exec.so LD_PRELOAD + direct filesDir exec all composed
as designed.

Phase 2a complete. Phase 2b (gradle --version) unblocked.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Phase 2a Exit Criteria

All of the following must hold:

- [ ] `scripts/bootstrap/build-hello.sh --abi <target>` produces a working `hello-<abi>.tar.zst`.
- [ ] `scripts/bootstrap/build-jdk.sh --abi <target>` produces a working `jdk-17.0.13-<abi>.tar.zst` from Termux's upstream .deb.
- [ ] `scripts/bootstrap/build-manifest.sh` produces `manifest.json` + a valid Ed25519 signature against the dev key.
- [ ] `scripts/bootstrap/dev-serve.sh` serves the artifacts over http://localhost:8000 and `adb reverse` makes them reachable from the device.
- [ ] `BuildRuntimeDebugScreen` has the URL override field + the two new buttons, all behind the existing debug-mode flag.
- [ ] `DownloadedBinaryExecInstrumentedTest` compiles, skips cleanly when no dev URL is set, and passes both cases when a real dev server is running.
- [ ] Phase 1's 60 unit + 8 instrumented tests still pass (regression check on the 8 instrumented).
- [ ] Exit log in this plan file is filled in with device + timings + anomalies.

When these boxes are checked, Phase 2a is done. Phase 2b (embed `gradle` + run `gradle --version`) is now unblocked.

---

## Self-Review Notes

**Spec coverage:**
- Phase 2 goal: run a real Java/Gradle process from `filesDir`. Phase 2a proves the Java half directly.
- §2.3 `targetSdk=28` — referenced in the test's acceptance preconditions.
- §3.2 filesystem layout — exercised in both artifacts (`usr/opt/hello/bin/hello`, `usr/opt/jdk-17.0.13/bin/java`).
- §3.5 mirror strategy — manifest URL override piggybacks on it; `MirrorSelector` code unchanged.

**Placeholders / gaps:**
- Termux upstream .deb filename `openjdk-17_17.0.13_p11-0_aarch64.deb` is inferred; if Termux moves it, the `--jdk-version` flag lets the dev patch without touching code.
- `BuildRuntimeModule.BOOTSTRAP_PUBKEY_HEX` is internal to the module — the instrumented test reaches into it directly. Normally this would be injected, but the test stays outside the Hilt graph for simplicity and this is the pragmatic shortcut. Phase 2b+ refactors can improve if desired.
- The "java -version" integration test is best-effort on timing: 30s timeout for first JDK initialization on emulator. If the emulator is slow, bump in local patches only.

**Type consistency:**
- `BootstrapComponent.id = "hello"` aligned across `build-manifest.sh`, `BuildRuntimeDebugViewModel.runHello`, and the instrumented test.
- `BootstrapComponent.id = "jdk-17.0.13"` aligned with `ProcessEnvBuilder.JDK_DIR_NAME` and `componentInstallDir("jdk-17.0.13")`.
- `@Named("bootstrapManifestUrl")` identifier identical across `BuildRuntimeModule`, `BuildRuntimeDebugViewModel`.

No "TBD", "TODO", "similar to Task N", or "add error handling" placeholders.

---

## Phase 2a Exit Log

**Completed:** 2026-04-18

**Test device:** Pixel_7_Pro_API_31 AVD (Android 12, arm64-v8a)

**Instrumented tests:** PASS — 2 of 2, 5.9s total runtime
- `hello_binary_exec_after_bootstrap`: 2.62s — NDK-compiled `hello` binary downloaded, exec'd, stdout `"hello from bootstrap\n"` + exit 0.
- `jdk_java_version_after_bootstrap`: 2.81s — real Termux-sourced OpenJDK 17.0.18 downloaded (112 MB .tar.gz, ~280 MB installed), `$PREFIX/opt/jdk-17.0.13/bin/java -version` returned:
  ```
  openjdk version "17.0.18" 2025-01-21
  OpenJDK Runtime Environment (build 17.0.18+8)
  OpenJDK 64-Bit Server VM (build 17.0.18+8, mixed mode)
  ```

**Unit tests:** 60 tests, 0 failures (same as pre-Phase-2a).
**Full instrumented suite:** 10 tests, 0 failures (2 new Phase 2a + 8 from Phase 1a-1d).
**`:app:assembleDebug`:** PASS.

**Deviations from the plan (plan bugs caught during execution):**

1. `build-hello.sh` had `-static-libstdc++` left over from a C++ context; triggers `-Werror` for pure C. Removed.
2. Termux's upstream moved `openjdk-17_17.0.13_p11-0_aarch64.deb` → `openjdk-17_17.0.18_aarch64.deb`. Default `--jdk-version` bumped to 17.0.18.
3. `build-jdk.sh` originally tar'd with `java-17-openjdk/` wrapper at the root, so extraction produced `$PREFIX/opt/jdk-17.0.13/java-17-openjdk/bin/java` instead of `$PREFIX/opt/jdk-17.0.13/bin/java`. Fixed to tar contents directly.
4. **`sign-manifest.kts` replaced with `SignManifest.java`** — `@file:DependsOn` isn't resolved by the plain `kotlin` CLI. Single-file JEP 330 Java using stdlib `java.security` Ed25519 (JDK 15+) removes the dependency on Kotlin + eddsa on the dev machine.
5. **zstd → gzip bootstrap format**. `com.github.luben:zstd-jni` only ships Linux/glibc `.so`; `io.airlift:aircompressor` relies on `sun.misc.Unsafe.ARRAY_BYTE_BASE_OFFSET` absent from ART. Neither works on Android. Switched artifact format to `.tar.gz` with stdlib `java.util.zip.GZIPInputStream`. Size penalty: ~15% (95 MB → 112 MB for the JDK). Component `id`s unchanged.
6. **`ZstdExtractor` now handles tar symlink entries** (was writing 0-byte regular files instead of creating symlinks) — surfaced by Termux's `libz.so.1` → `libz.so.1.3.2` symlink chain.
7. **`ProcessEnvBuilder.LD_LIBRARY_PATH` extended to include `$JAVA_HOME/lib/server:$JAVA_HOME/lib:$PREFIX/lib`**. Termux's `libjvm.so` has a hard-coded `DT_RUNPATH` pointing at `/data/data/com.termux/...` paths that don't exist in our install — `LD_LIBRARY_PATH` overrides, avoiding any need to patch ELF RUNPATH. Unit test updated to match.
8. **`build-jdk.sh` now bundles 5 Termux runtime lib dependencies + NDK's `libc++_shared.so`** into the JDK tree: `zlib`, `libandroid-shmem`, `libandroid-spawn`, `libiconv`, `libjpeg-turbo`, and NDK `libc++_shared.so`. Discovered iteratively by running `java -version` and resolving each missing-library error from the Android linker.
9. **`DownloadedBinaryExecInstrumentedTest` no longer depends on `:app`** (commit 4621520 added `androidTestImplementation(project(":app"))` to access `BOOTSTRAP_PUBKEY_HEX`; this dragged in androidx.startup InitializationProvider which crashed the test APK). Pubkey inlined in the test — documented with reference to `docs/bootstrap/dev-keypair-setup.md`.

**Operational prerequisites learned:**
- `kotlin` CLI NOT required for signing manifests. JDK 15+ in PATH suffices (`brew install openjdk` on macOS, `apt install default-jdk` on Linux).
- `adb reverse tcp:8000 tcp:8000` is the development reach-through. `curl http://localhost:8000` from the host may be intercepted by a corporate proxy (returns 502); use `curl --noproxy '*'` or just rely on the device side which doesn't use `HTTP_PROXY`.

Phase 2a complete. Phase 2b (`gradle --version` on device) is unblocked: Gradle itself is a shell-wrapper + JAR invocation, so it runs in the *descendants* of the launched java process where `libtermux-exec.so`'s shebang-rewrite takes effect.
