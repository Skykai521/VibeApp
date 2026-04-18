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
