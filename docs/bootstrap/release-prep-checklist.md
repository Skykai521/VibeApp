# VibeApp v2.0 Release Preparation Checklist

> Prerequisite for shipping v2.0 to users. Phase 1 (code side) is
> complete after Phase 1d; this checklist covers the DevOps side —
> producing the real 180MB bootstrap artifacts, generating a
> production signing key, and publishing to GitHub Release + mirror.
>
> None of this blocks further Phase 2-7 development on the code side;
> do these tasks on whatever schedule fits the release timeline.

## 1. Prepare the real Tier-1 artifacts (~90 min)

For each of the three ABIs (arm64-v8a, armeabi-v7a, x86_64):

1. Download **Adoptium Temurin 17.0.13 JDK** for the ABI. Source:
   https://adoptium.net/temurin/releases/?version=17
2. Unpack and strip unused pieces:
   ```bash
   cd <jdk-unpack-dir>
   rm -rf demo man sample src.zip legal
   find . -name "*.diz" -delete
   ```
3. Repack as zstd-compressed tar:
   ```bash
   tar -cf - . | zstd -19 -o /tmp/jdk-17.0.13-${ABI}.tar.zst
   ```

For **Gradle 9.3.1**:
1. Download `gradle-9.3.1-bin.zip` from https://gradle.org/releases/
2. Unpack, then repack as tar.zst.

For the **minimal Android SDK**:
1. Use `sdkmanager` to fetch only `platforms;android-34` and `build-tools;34.0.0`.
2. Pre-accept licenses: copy the accepted license files from
   `$ANDROID_HOME/licenses/` into the SDK tree before packing.
3. Repack as tar.zst.

Compute SHA-256 for each artifact:
```bash
sha256sum /tmp/*.tar.zst > /tmp/artifact-hashes.txt
```

## 2. Production Ed25519 key ceremony (~30 min)

**Do this on an air-gapped machine if possible.**

Generate a new Ed25519 keypair distinct from the dev keypair used by
contributors during development. Use any Ed25519-capable tool you trust
(openssl 3.x, age, or the same `net.i2p.crypto:eddsa` Kotlin test
harness as the dev key ceremony — see `docs/bootstrap/dev-keypair-setup.md`).

Result should be two 32-byte values, hex-encoded:
  - `vibeapp-prod-ed25519.priv`  (seed)
  - `vibeapp-prod-ed25519.pub`   (public key)

Store the private key in password-protected storage (1Password,
hardware security module, etc.). NEVER commit it. NEVER keep it
unencrypted on a networked machine.

## 3. Inject production pubkey via CI secret

Add a GitHub repository secret:

  Name:  `BOOTSTRAP_PRODUCTION_PUBKEY_HEX`
  Value: the 64-char hex from `vibeapp-prod-ed25519.pub`

Wire the secret into `.github/workflows/release-build.yml`:

```yaml
- name: Build release APK
  run: ./gradlew :app:assembleRelease
  env:
    BOOTSTRAP_PUBKEY_HEX: ${{ secrets.BOOTSTRAP_PRODUCTION_PUBKEY_HEX }}
```

Update `app/build.gradle.kts` to surface the env var as a BuildConfig field:

```kotlin
android {
    defaultConfig {
        buildConfigField(
            "String",
            "BOOTSTRAP_PUBKEY_HEX",
            "\"${System.getenv("BOOTSTRAP_PUBKEY_HEX")
                ?: com.vibe.app.di.BuildRuntimeModule.BOOTSTRAP_PUBKEY_HEX}\"",
        )
    }
    buildFeatures { buildConfig = true }
}
```

Change `BuildRuntimeModule.provideManifestSignature` to prefer the
`BuildConfig` value:

```kotlin
@Provides
@Singleton
fun provideManifestSignature(): ManifestSignature =
    ManifestSignature(publicKeyHex = com.vibe.app.BuildConfig.BOOTSTRAP_PUBKEY_HEX)
```

Debug builds without the env var set will fall back to the dev const.

## 4. Build and sign the manifest (~15 min)

Build `manifest.json` with the 5 artifact entries + SHA-256 hashes
from Step 1. Schema (matches `build-runtime/src/test/resources/manifest/valid.json`):

```json
{
  "schemaVersion": 1,
  "manifestVersion": "v2.0.0",
  "components": [
    {
      "id": "jdk-17.0.13",
      "version": "17.0.13",
      "artifacts": {
        "arm64-v8a": {
          "fileName": "jdk-17.0.13-arm64-v8a.tar.zst",
          "sizeBytes": 83000000,
          "sha256": "<hex>"
        },
        "armeabi-v7a": { ... },
        "x86_64": { ... }
      }
    },
    {
      "id": "gradle-9.3.1",
      "version": "9.3.1",
      "artifacts": {
        "common": {
          "fileName": "gradle-9.3.1-noarch.tar.zst",
          "sizeBytes": 68000000,
          "sha256": "<hex>"
        }
      }
    },
    {
      "id": "android-sdk-34-minimal",
      "version": "34",
      "artifacts": {
        "common": {
          "fileName": "android-sdk-34-minimal.tar.zst",
          "sizeBytes": 95000000,
          "sha256": "<hex>"
        }
      }
    }
  ]
}
```

Sign with the production private key. The signature must match
`net.i2p.crypto:eddsa`'s raw 64-byte output format (what
`ManifestSignature.verify` expects).

## 5. Upload to GitHub Release

1. Create release tag `v2.0.0` (push the tag from dev branch).
2. Upload all 6 files to the release:
   - `manifest.json`
   - `manifest.json.sig`
   - `jdk-17.0.13-{arm64-v8a,armeabi-v7a,x86_64}.tar.zst`
   - `gradle-9.3.1-noarch.tar.zst`
   - `android-sdk-34-minimal.tar.zst`
3. Write release notes describing v2.0 user-facing features.

## 6. Aliyun mirror

Create an Aliyun OSS bucket `vibeapp-cdn` in `oss-cn-hangzhou`. Sync
the 6 release files:

```bash
ossutil cp -r /path/to/release-files/ oss://vibeapp-cdn/releases/v2.0.0/
```

`MirrorSelector` in `BuildRuntimeModule.kt` already points at
`https://vibeapp-cdn.oss-cn-hangzhou.aliyuncs.com/releases/v2.0.0`
as the fallback.

## 7. Final validation on a clean device

1. Install the release APK on a fresh device.
2. Open Settings → Build Runtime (debug).
3. Tap "Trigger bootstrap". Watch state transition:
   Downloading → Verifying → Unpacking → Installing → Ready.
4. Tap "Launch toybox echo". Confirm stdout `debug-launch OK`.
5. Via `adb shell`, verify the installed JDK responds:
   ```bash
   adb shell run-as com.vibe.app \
       /data/user/0/com.vibe.app/files/usr/opt/jdk-17.0.13/bin/java -version
   ```
   Expected stderr: `openjdk version "17.0.13" ...`.

   **NOTE**: This step currently will NOT work under stock API 29+
   SELinux policy (untrusted_app can't exec `app_data_file`). Phase 2
   introduces `proot` (or equivalent) to chroot past the restriction.
   For the initial v2.0 release you have two options:
   - Hold the release until Phase 2's proot lands, or
   - Ship v2.0 as an internal tech-preview with a documented limitation,
     then ship the full feature in v2.1 after Phase 2.

If any step fails, debug before publishing.
