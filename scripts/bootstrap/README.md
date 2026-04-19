# Bootstrap Artifact Pipeline

Scripts that produce the on-device toolchain (JDK + Gradle + Android
SDK + aapt2) as tar.gz artifacts under `scripts/bootstrap/artifacts/`,
plus the `manifest.json` that describes them.

The artifacts are **bundled into the APK** by the
`:app:copyBootstrapArtifacts` Gradle task, which copies them from
`scripts/bootstrap/artifacts/` into `app/src/main/assets/bootstrap/`.
On first launch, `RuntimeBootstrapper` extracts them into
`filesDir/usr/opt/`. No network download at runtime.

## Supported ABIs

`arm64-v8a` (64-bit ARM — every modern Android device) and
`armeabi-v7a` (32-bit ARM — older low-end hardware). x86_64 was
dropped; if you need it back, revive the `X86_64` enum entry in
`build-runtime/.../Abi.kt` and add the `x86_64` case back in each
build script.

## Prerequisites

- Android NDK 28.2+ at `$ANDROID_SDK/ndk/28.2.13676358/` (or set
  `$ANDROID_NDK_HOME` / pass `--ndk-path` to the scripts that need it).
- `ar` + `tar` + `sha256sum` — standard on Linux/macOS.
- `curl` for JDK / Android SDK downloads.

## Workflow

```bash
cd scripts/bootstrap

# 1. Build the artifacts for your target ABI (arm64-v8a for typical
#    devices + the arm64 Android Studio emulator).
./build-jdk.sh        --abi arm64-v8a
./build-gradle.sh                       # gradle-9.3.1-common.tar.gz, ~100 MB
./build-androidsdk.sh --abi arm64-v8a

# 2. Write manifest.json describing what we just built.
./build-manifest.sh

# 3. Back at the repo root, rebuild the APK. copyBootstrapArtifacts
#    auto-runs as part of the `merge*Assets` task graph; the artifacts
#    will be under assets/bootstrap/ in the APK.
cd ../..
./gradlew :app:assembleDebug
```

## Output layout

```
scripts/bootstrap/artifacts/
├── manifest.json
├── jdk-17.0.13-arm64-v8a.tar.gz
├── gradle-9.3.1-common.tar.gz
└── android-sdk-36.0.0-arm64-v8a.tar.gz
```
