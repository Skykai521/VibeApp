# Release Process

This document describes how to publish a new release of VibeApp.

## Prerequisites

The following GitHub repository secrets must be configured:

| Secret | Description |
|--------|-------------|
| `APP_KEYSTORE` | Base64-encoded release keystore (`.jks`) |
| `KEY_ALIAS` | Key alias inside the keystore |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_PASSWORD` | Key password |

## How CI Works

The workflow is defined in `.github/workflows/release-build.yml` and triggers on:

1. **Push a tag matching `v*`** — builds, signs, and creates a GitHub Release with APK + AAB attached.
2. **Manual dispatch** (`workflow_dispatch`) — builds and signs, uploads artifacts to the workflow run (no GitHub Release created).

### Pipeline Steps

```
assembleRelease → bundleRelease → sign APK (apksigner) → sign AAB (jarsigner) → upload artifacts → create GitHub Release (tag only)
```

## Step-by-Step Release

### 1. Update version numbers

In `app/build.gradle.kts`:

```kotlin
versionCode = <increment by 1>
versionName = "<new semver>"
```

- `versionCode`: integer, must increase every release.
- `versionName`: semver string (e.g., `1.2.0`).

### 2. Commit and push to dev

```bash
git add app/build.gradle.kts
git commit -m "bump version to <versionName>"
git push origin dev
```

### 3. Merge to main

Merge `dev` into `main` via PR or direct merge (follow your team workflow).

### 4. Tag and push

```bash
git checkout main
git pull origin main
git tag v<versionName>     # e.g., git tag v1.2.0
git push origin v<versionName>
```

This triggers the CI workflow which will:
- Build unsigned release APK and AAB
- Sign both with the release keystore
- Create a GitHub Release at the tag with auto-generated release notes
- Attach `app-release.apk` and `app-release.aab` to the release

### 5. Verify

- Go to **Actions** tab on GitHub to monitor the workflow run.
- Once complete, check **Releases** page for the new release and its artifacts.

## How to Ask Claude to Do a Release

Use a prompt like:

> 请升级版本到 1.3.0 (versionCode 8)，提交并推送到 dev，然后在 main 上打 tag v1.3.0 并推送。

Or in English:

> Bump version to 1.3.0 (versionCode 8), commit and push to dev, then tag v1.3.0 on main and push the tag.

Key information to include:
- **New versionName** (e.g., `1.3.0`)
- **New versionCode** (e.g., `8`)
- **Whether to merge dev → main and push the tag** (this triggers the release)

## Versioning Convention

| Change type | Version bump | Example |
|-------------|-------------|---------|
| Breaking / major feature | Major (`X.0.0`) | `1.0.0` → `2.0.0` |
| New features | Minor (`x.Y.0`) | `1.1.2` → `1.2.0` |
| Bug fixes only | Patch (`x.y.Z`) | `1.2.0` → `1.2.1` |

## Manual Trigger (No Tag)

If you want to build and sign without creating a release:

1. Go to **Actions** → **Generate Release Version** → **Run workflow**
2. Select the branch to build from
3. Artifacts will be available as workflow downloads (no GitHub Release)
