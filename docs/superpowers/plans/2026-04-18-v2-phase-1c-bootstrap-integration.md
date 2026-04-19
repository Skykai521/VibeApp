# v2.0 Phase 1c: Bootstrap Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Glue Phase 1a (bootstrap download) and Phase 1b (native process runtime) into a shippable subsystem. After Phase 1c, a developer can open the app, tap a debug-only button in Settings, and watch VibeApp download a (synthetic test) manifest, verify an Ed25519 signature, unpack a `.tar.zst`, then `exec` a binary from the extracted tree — all on real hardware.

**Architecture:** A new `ManifestFetcher` issues two HTTP GETs (manifest JSON + detached `.sig`) behind `MirrorSelector`. The `RuntimeBootstrapper.bootstrap()` flow no longer takes `parsedManifestOverride`; it fetches, verifies, parses, and then iterates components as before. A dev-cycle Ed25519 keypair is generated; the public key is committed as a Kotlin constant; the private key lives outside the repo. A debug-mode-only screen (`BuildRuntimeDebugScreen`) hosts trigger + state + "launch test process" controls. An instrumented test stitches it all together against a MockWebServer.

**Tech Stack:** Everything already in the repo — `kotlinx.coroutines.Flow`, `java.net.HttpURLConnection` (no new HTTP deps), `net.i2p.crypto:eddsa` (for the test keypair generation), Compose for the debug screen, MockWebServer for the acceptance test. No new Gradle deps.

---

## Spec References

- Design doc: `docs/superpowers/specs/2026-04-18-v2-gradle-compose-arch-design.md`
- This plan implements the remaining pieces of §3: the missing §3.5 manifest fetch link + §3.4 real Ready transition + §3.9 end-to-end integration test on a real device.
- Prior plans (completed): Phase 0, Phase 1a, Phase 1b.

## Working Directory

**All file operations happen in the git worktree:** `/Users/skykai/Documents/work/VibeApp/.worktrees/v2-arch`

Branch: `v2-arch`. Current HEAD at plan write time: `81b464c` (Phase 1b complete).

## File Structure

**Create:**

| File | Responsibility |
|---|---|
| `build-runtime/src/main/kotlin/com/vibe/build/runtime/bootstrap/ManifestFetcher.kt` | HTTP GET of `manifest.json` + `manifest.json.sig` via `MirrorSelector`; returns raw bytes. |
| `build-runtime/src/test/kotlin/com/vibe/build/runtime/bootstrap/ManifestFetcherTest.kt` | MockWebServer tests: happy path + 404 + body truncation + mirror fallback. |
| `app/src/main/kotlin/com/vibe/app/feature/bootstrap/BuildRuntimeDebugViewModel.kt` | ViewModel exposing `StateFlow<DebugUiState>`; launches `RuntimeBootstrapper` + sample `ProcessLauncher` call. |
| `app/src/main/kotlin/com/vibe/app/feature/bootstrap/BuildRuntimeDebugScreen.kt` | Compose screen + navigation entry behind debug-mode flag. |
| `app/src/main/kotlin/com/vibe/app/feature/bootstrap/BuildRuntimeDebugState.kt` | Data class + sealed state for the UI. |
| `build-runtime/src/androidTest/kotlin/com/vibe/build/runtime/BootstrapEndToEndInstrumentedTest.kt` | Full-stack test: MockWebServer + real FS + real ProcessLauncher against the extracted binary. |

**Modify:**

| File | Responsibility |
|---|---|
| `build-runtime/src/main/kotlin/com/vibe/build/runtime/bootstrap/RuntimeBootstrapper.kt` | Replace `parsedManifestOverride` test hook with real `ManifestFetcher` + signature verify + parse flow. Override hook remains for Phase 1a tests. |
| `build-runtime/src/test/kotlin/com/vibe/build/runtime/bootstrap/RuntimeBootstrapperTest.kt` | Keep existing tests (they use `parsedManifestOverride`); add 1-2 tests that exercise the `ManifestFetcher` path with a fake fetcher. |
| `app/src/main/kotlin/com/vibe/app/di/BuildRuntimeModule.kt` | Replace placeholder pubkey (`"00".repeat(32)`) with the committed dev pubkey constant. Inject `ManifestFetcher`. Wire the `bootstrapManifestUrl` string. |
| `app/src/main/kotlin/com/vibe/app/presentation/ui/setting/SettingScreen.kt` | Add a single new entry "Build Runtime (debug)" guarded by `debugMode == true`. |
| `build-runtime/src/main/kotlin/com/vibe/build/runtime/di/BuildRuntimeBootstrapModule.kt` | Add `@Binds` for `ManifestFetcher` if it becomes interface-backed (see Task 1 decision). |

**Committed as text (outside code tree):**

| File | Responsibility |
|---|---|
| `docs/bootstrap/dev-keypair-setup.md` | How to regenerate the dev Ed25519 keypair; where to put the private key (outside repo). |

**Do NOT touch in Phase 1c:**
- Anything under `build-gradle/`, `plugin-host/`, `build-engine/`, `build-tools/`, `shadow-runtime/`.
- Any existing `feature/agent/...` or `feature/project/...` code.
- `agent-system-prompt.md`.
- `libtermux-exec.c` or related native sources (deferred to Phase 2 prep).
- Any real JDK/Gradle/SDK tarball (deferred to Phase 1d).

## Key Constraints (ALL TASKS)

1. **Keep `parsedManifestOverride` alive**: Phase 1a tests depend on it. Phase 1c adds a *real* fetch path that runs when `parsedManifestOverride == null`. Don't delete the hook.
2. **Pubkey commit style**: Store the dev pubkey as a `const val BOOTSTRAP_PUBKEY_HEX = "..."` inside `app/src/main/kotlin/com/vibe/app/di/BuildRuntimeModule.kt`. No BuildConfig gymnastics. For production, a future Phase 1d task will move this behind a Gradle property + CI secret.
3. **Private key NEVER in repo**: The generation script writes the private key to `~/.vibeapp/dev-bootstrap-private.pem` (or a user-chosen path outside the worktree). Confirm `git status` does not show it before committing.
4. **Debug UI is debug-mode gated**: Use the existing `SettingDataSource.getDebugMode()` flag. The entry must NOT appear unless debug mode is on. No setting flag flip UI in this phase (it already exists in `SettingDataSource`).
5. **MockWebServer**: Already in `:build-runtime`'s `testImplementation` and `androidTestImplementation`. No new dep.
6. **No `libtermux-exec.so`**: The end-to-end test uses a binary that does NOT require shebang resolution — it's a statically-linked `toybox`-compatible ELF, or a raw binary we compile during the test. Safer: re-use `/system/bin/toybox` as the test payload's proof of `exec` after extraction. See Task 4 design.

---

## Task 1: `ManifestFetcher` + integrate into `RuntimeBootstrapper`

**Files:**
- Create: `build-runtime/src/main/kotlin/com/vibe/build/runtime/bootstrap/ManifestFetcher.kt`
- Create: `build-runtime/src/test/kotlin/com/vibe/build/runtime/bootstrap/ManifestFetcherTest.kt`
- Modify: `build-runtime/src/main/kotlin/com/vibe/build/runtime/bootstrap/RuntimeBootstrapper.kt`
- Modify: `build-runtime/src/test/kotlin/com/vibe/build/runtime/bootstrap/RuntimeBootstrapperTest.kt`

### Step 1: Write the failing test `ManifestFetcherTest.kt`

```kotlin
package com.vibe.build.runtime.bootstrap

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking

class ManifestFetcherTest {

    private lateinit var primary: MockWebServer
    private lateinit var fallback: MockWebServer

    @Before
    fun setUp() {
        primary = MockWebServer().apply { start() }
        fallback = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        primary.shutdown()
        fallback.shutdown()
    }

    private fun selector() = MirrorSelector(
        primaryBase = primary.url("").toString().trimEnd('/'),
        fallbackBase = fallback.url("").toString().trimEnd('/'),
    )

    @Test
    fun `fetch returns manifest and signature bytes from primary`() = runTest {
        val manifestBody = """{"schemaVersion":1}""".toByteArray()
        val sigBody = ByteArray(64) { it.toByte() }
        primary.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Length", manifestBody.size.toString())
                .setBody(Buffer().write(manifestBody)),
        )
        primary.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Length", sigBody.size.toString())
                .setBody(Buffer().write(sigBody)),
        )

        val fetcher = ManifestFetcher(selector())
        val result = fetcher.fetch(manifestFileName = "manifest.json")

        assertArrayEquals(manifestBody, result.manifestBytes)
        assertArrayEquals(sigBody, result.signatureBytes)
    }

    @Test
    fun `fetch falls back to secondary mirror on primary manifest 500`() = runTest {
        primary.enqueue(MockResponse().setResponseCode(500))
        val manifestBody = """{"schemaVersion":1}""".toByteArray()
        val sigBody = ByteArray(64)
        fallback.enqueue(
            MockResponse().setResponseCode(200).setBody(Buffer().write(manifestBody)),
        )
        fallback.enqueue(
            MockResponse().setResponseCode(200).setBody(Buffer().write(sigBody)),
        )

        val selector = selector()
        val fetcher = ManifestFetcher(selector)
        val result = fetcher.fetch(manifestFileName = "manifest.json")

        assertArrayEquals(manifestBody, result.manifestBytes)
        assertEquals("FALLBACK", selector.currentMirrorName())
    }

    @Test
    fun `fetch throws DownloadFailedException when both mirrors fail`() {
        primary.enqueue(MockResponse().setResponseCode(500))
        fallback.enqueue(MockResponse().setResponseCode(500))

        val fetcher = ManifestFetcher(selector())
        assertThrows(DownloadFailedException::class.java) {
            runBlocking { fetcher.fetch(manifestFileName = "manifest.json") }
        }
    }

    @Test
    fun `fetch throws when signature file is missing`() {
        val manifestBody = """{"schemaVersion":1}""".toByteArray()
        primary.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(manifestBody)))
        primary.enqueue(MockResponse().setResponseCode(404))
        fallback.enqueue(MockResponse().setResponseCode(404))

        val fetcher = ManifestFetcher(selector())
        assertThrows(DownloadFailedException::class.java) {
            runBlocking { fetcher.fetch(manifestFileName = "manifest.json") }
        }
    }
}
```

### Step 2: Run → fail

```bash
cd /Users/skykai/Documents/work/VibeApp/.worktrees/v2-arch
./gradlew --no-daemon :build-runtime:testDebugUnitTest --tests ManifestFetcherTest
```

Expected: compile error (`ManifestFetcher` not found).

### Step 3: Implement `ManifestFetcher.kt`

```kotlin
package com.vibe.build.runtime.bootstrap

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Two-leg HTTP fetch: manifest JSON + detached signature, both under
 * the same base URL. Fetches through [MirrorSelector] so a 4xx/5xx/
 * connect-fault on the primary mirror flips to the fallback (sticky
 * for the rest of the session).
 *
 * Returned bytes are unverified; caller must pass through
 * [ManifestSignature.verify] before [ManifestParser.parse].
 */
@Singleton
open class ManifestFetcher @Inject constructor(
    private val mirrors: MirrorSelector,
) {
    data class Fetched(
        val manifestBytes: ByteArray,
        val signatureBytes: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Fetched) return false
            return manifestBytes.contentEquals(other.manifestBytes) &&
                signatureBytes.contentEquals(other.signatureBytes)
        }
        override fun hashCode(): Int =
            manifestBytes.contentHashCode() * 31 + signatureBytes.contentHashCode()
    }

    open suspend fun fetch(manifestFileName: String): Fetched = withContext(Dispatchers.IO) {
        val manifest = fetchWithFallback(manifestFileName)
        val signature = fetchWithFallback("$manifestFileName.sig")
        Fetched(manifestBytes = manifest, signatureBytes = signature)
    }

    private fun fetchWithFallback(fileName: String): ByteArray {
        val firstUrl = mirrors.currentUrlFor(fileName)
        try {
            return httpGet(firstUrl)
        } catch (e: DownloadFailedException) {
            mirrors.markPrimaryFailed()
            val retryUrl = mirrors.currentUrlFor(fileName)
            if (retryUrl == firstUrl) throw e
            return httpGet(retryUrl)
        }
    }

    private fun httpGet(url: String): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
        val status: Int = try {
            conn.responseCode
        } catch (e: IOException) {
            throw DownloadFailedException("network error for $url: ${e.message}", e)
        }
        if (status !in 200..299) {
            throw DownloadFailedException("HTTP $status from $url")
        }
        return try {
            conn.inputStream.use { it.readBytes() }
        } catch (e: IOException) {
            throw DownloadFailedException("read error for $url: ${e.message}", e)
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
    }
}
```

### Step 4: Run → pass

```bash
./gradlew --no-daemon :build-runtime:testDebugUnitTest --tests ManifestFetcherTest
```

Expected: 4 tests, 0 failures.

### Step 5: Extend `RuntimeBootstrapper.kt` with the real fetch path

Open `build-runtime/src/main/kotlin/com/vibe/build/runtime/bootstrap/RuntimeBootstrapper.kt` and extend the constructor + `bootstrap()` body.

Replace the existing class header + `bootstrap` method (keeping `bootstrapComponent` and helpers unchanged):

```kotlin
@Singleton
class RuntimeBootstrapper @Inject constructor(
    private val fs: BootstrapFileSystem,
    private val store: BootstrapStateStore,
    private val parser: ManifestParser,
    private val signature: ManifestSignature,
    private val mirrors: MirrorSelector,
    private val downloader: BootstrapDownloader,
    private val extractor: ZstdExtractor,
    private val abi: Abi,
    private val fetcher: ManifestFetcher,
    /** Phase 1a test hook — bypasses real manifest fetch + verify + parse. */
    private val parsedManifestOverride: BootstrapManifest? = null,
) {
    suspend fun bootstrap(
        manifestUrl: String,
        onState: suspend (BootstrapState) -> Unit = {},
    ) {
        try {
            fs.ensureDirectories()

            val manifest = if (parsedManifestOverride != null) {
                parsedManifestOverride
            } else {
                val fetched = fetcher.fetch(manifestFileName = manifestUrl.substringAfterLast('/'))
                if (!signature.verify(fetched.manifestBytes, fetched.signatureBytes)) {
                    throw SignatureMismatchException(
                        "manifest signature failed verification",
                    )
                }
                parser.parse(fetched.manifestBytes)
            }

            for (component in manifest.components) {
                val artifact = manifest.findArtifact(component.id, abi)
                    ?: throw DownloadFailedException(
                        "no artifact for component=${component.id} ABI=${abi.abiId}",
                    )
                bootstrapComponent(component, artifact, onState)
            }

            emit(BootstrapState.Ready(manifest.manifestVersion), onState)
        } catch (e: BootstrapException) {
            emit(BootstrapState.Failed(e.messageOrClass()), onState)
        } catch (e: Exception) {
            emit(BootstrapState.Failed(e.messageOrClass()), onState)
        }
    }

    // bootstrapComponent, emit, messageOrClass — unchanged from Phase 1a
}
```

### Step 6: Update `RuntimeBootstrapperTest.kt` for the new constructor param

The existing tests construct `RuntimeBootstrapper` without `fetcher`. Fix them by adding a stub fetcher. Update the `FakeCollaborators` helper at the bottom of the test file to include:

```kotlin
    val fetcher = object : ManifestFetcher(mirrors) {
        override suspend fun fetch(manifestFileName: String): Fetched {
            // Phase 1a tests use parsedManifestOverride, so fetch() should never be called.
            // If it is, something leaked the fake and the test is wrong.
            throw AssertionError("ManifestFetcher.fetch() was called unexpectedly in Phase 1a test fake")
        }
    }
```

And update every call site that constructs `RuntimeBootstrapper` to pass `fetcher = collaborators.fetcher`. There are **4** such call sites in `RuntimeBootstrapperTest.kt` (one per `@Test`). Add `fetcher = collaborators.fetcher` to the named-argument list in each.

### Step 7: Add a new `@Test` exercising the real fetch path

Append to `RuntimeBootstrapperTest`:

```kotlin
    @Test
    fun `signature verify failure on real fetch path transitions to Failed`() = runTest {
        val store = InMemoryBootstrapStateStore()
        val fs = BootstrapFileSystem(filesDir = temp.root)
        val collaborators = FakeCollaborators(
            manifestBytes = ByteArray(0),
            manifestSignature = ByteArray(0),
            abi = Abi.ARM64,
            successfulFakeDownload = true,
            successfulFakeVerify = false,   // cause SignatureMismatchException on real path
            successfulFakeExtract = true,
        )
        val fakeFetcher = object : ManifestFetcher(collaborators.mirrors) {
            override suspend fun fetch(manifestFileName: String): Fetched =
                Fetched(manifestBytes = "fake".toByteArray(), signatureBytes = ByteArray(64))
        }

        val bootstrapper = RuntimeBootstrapper(
            fs = fs,
            store = store,
            parser = collaborators.parser,
            signature = collaborators.signature,
            mirrors = collaborators.mirrors,
            downloader = collaborators.downloader,
            extractor = collaborators.extractor,
            abi = Abi.ARM64,
            fetcher = fakeFetcher,
            parsedManifestOverride = null,   // forces real fetch path
        )

        val seen = mutableListOf<BootstrapState>()
        bootstrapper.bootstrap(manifestUrl = "https://example.test/manifest.json") { seen += it }

        val terminal = seen.last()
        assertTrue("expected Failed, got $terminal", terminal is BootstrapState.Failed)
        assertTrue(
            (terminal as BootstrapState.Failed).reason.contains("signature", ignoreCase = true),
        )
    }
```

### Step 8: Run the full `:build-runtime` test suite

```bash
./gradlew --no-daemon :build-runtime:testDebugUnitTest
```

Expected: 52 + 4 (ManifestFetcherTest) + 1 (new RuntimeBootstrapperTest case) = **57 total tests, 0 failures**.

### Step 9: Commit

```bash
git add build-runtime/src/main/kotlin/com/vibe/build/runtime/bootstrap/ManifestFetcher.kt \
        build-runtime/src/test/kotlin/com/vibe/build/runtime/bootstrap/ManifestFetcherTest.kt \
        build-runtime/src/main/kotlin/com/vibe/build/runtime/bootstrap/RuntimeBootstrapper.kt \
        build-runtime/src/test/kotlin/com/vibe/build/runtime/bootstrap/RuntimeBootstrapperTest.kt
git commit -m "feat(build-runtime): ManifestFetcher + real fetch path in RuntimeBootstrapper

ManifestFetcher does two HTTP GETs (manifest.json + manifest.json.sig)
through MirrorSelector with automatic primary→fallback on 4xx/5xx or
connect errors. RuntimeBootstrapper.bootstrap() now drives
fetch→verify→parse when parsedManifestOverride is null; the override
stays as a Phase 1a test hook so existing tests still pass.

Ref: docs/superpowers/plans/2026-04-18-v2-phase-1c-bootstrap-integration.md Task 1

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Ed25519 dev pubkey + documentation

**Files:**
- Create: `docs/bootstrap/dev-keypair-setup.md`
- Modify: `app/src/main/kotlin/com/vibe/app/di/BuildRuntimeModule.kt`

This task swaps the `"00" * 32` placeholder pubkey for a real dev keypair's public half. The **private** key is produced during this task but stored **outside the repo**, never committed. A companion doc explains regeneration.

### Step 1: Generate a dev Ed25519 keypair

The repository root has a shell one-liner that can be evaluated. Run it once to produce the keypair. This is not committed; only the resulting public key is.

```bash
mkdir -p ~/.vibeapp
cat > /tmp/gen_dev_keypair.kts <<'EOF'
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import java.security.SecureRandom

val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
val spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
val privateKey = EdDSAPrivateKey(EdDSAPrivateKeySpec(seed, spec))

val pubHex = privateKey.abyte.joinToString("") { "%02x".format(it) }
val seedHex = seed.joinToString("") { "%02x".format(it) }

println("PUBLIC_KEY_HEX=$pubHex")
println("PRIVATE_SEED_HEX=$seedHex")
EOF
```

Because running `.kts` files against `net.i2p.crypto:eddsa` is non-trivial without the project classpath, use the test harness instead: add a temporary `main` function in a **test** file (don't commit), or run the unit-test-style generator via `./gradlew :build-runtime:testDebugUnitTest --tests com.vibe.build.runtime.bootstrap.GenerateDevKeypairRunOnce`.

**Simpler alternative** — add a throwaway `main` function in a test file, run it via IntelliJ's play button, capture stdout, then delete the file before committing. Because the implementer is agentic, use the following concrete recipe:

1. Create temporary file `build-runtime/src/test/kotlin/com/vibe/build/runtime/bootstrap/GenerateDevKeypairRunOnce.kt`:

```kotlin
package com.vibe.build.runtime.bootstrap

import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.security.SecureRandom

class GenerateDevKeypairRunOnce {

    @Test
    fun generate() {
        val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
        val privateKey = EdDSAPrivateKey(EdDSAPrivateKeySpec(seed, spec))

        val pubHex = privateKey.abyte.joinToString("") { "%02x".format(it) }
        val seedHex = seed.joinToString("") { "%02x".format(it) }

        println("BOOTSTRAP_PUBKEY_HEX=$pubHex")
        println("BOOTSTRAP_PRIVSEED_HEX=$seedHex")
        assertNotNull(privateKey)
    }
}
```

2. Run it: `./gradlew --no-daemon :build-runtime:testDebugUnitTest --tests GenerateDevKeypairRunOnce --info`
3. Capture both hex strings from the `--info` stdout. They'll appear as `BOOTSTRAP_PUBKEY_HEX=<64-hex>` and `BOOTSTRAP_PRIVSEED_HEX=<64-hex>`.
4. Write the private seed to `~/.vibeapp/dev-bootstrap-privseed.hex`:
   ```bash
   echo "<the-priv-seed-hex>" > ~/.vibeapp/dev-bootstrap-privseed.hex
   chmod 600 ~/.vibeapp/dev-bootstrap-privseed.hex
   ```
5. **Delete** the throwaway test file: `rm build-runtime/src/test/kotlin/com/vibe/build/runtime/bootstrap/GenerateDevKeypairRunOnce.kt`

The public key hex (one 64-char hex string) is what you commit into `BuildRuntimeModule.kt` in Step 3.

### Step 2: Create the documentation at `docs/bootstrap/dev-keypair-setup.md`

```markdown
# Dev Ed25519 Keypair for Bootstrap Manifest Signing

The bootstrap subsystem verifies a signed manifest file before trusting
any on-device download. The public key is embedded in the APK
(`BuildRuntimeModule.BOOTSTRAP_PUBKEY_HEX`); the private key is
**never** in the repo.

## One-time key ceremony (dev cycle)

1. Run a throwaway generator test to produce `seed` and `pubkey`:
   ```kotlin
   // build-runtime/src/test/kotlin/.../GenerateDevKeypairRunOnce.kt
   // (Create locally, run, delete. Never commit.)
   val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
   val spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
   val privateKey = EdDSAPrivateKey(EdDSAPrivateKeySpec(seed, spec))
   println("pub=${privateKey.abyte.joinToString("") { "%02x".format(it) }}")
   println("seed=${seed.joinToString("") { "%02x".format(it) }}")
   ```
2. Save the seed (private key) to `~/.vibeapp/dev-bootstrap-privseed.hex`
   with `chmod 600`. Do NOT add it to the repo.
3. Paste the public key hex into
   `app/src/main/kotlin/com/vibe/app/di/BuildRuntimeModule.kt` as
   `BOOTSTRAP_PUBKEY_HEX`.
4. Delete the throwaway generator test.

## Signing a test manifest

To sign a synthetic manifest for the Phase 1c end-to-end instrumented
test or for local developer use, construct an `EdDSAPrivateKey` from the
seed and sign raw manifest JSON bytes:

```kotlin
val seed = File(System.getProperty("user.home"), ".vibeapp/dev-bootstrap-privseed.hex")
    .readText().trim().hexToByteArray()
val spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
val priv = EdDSAPrivateKey(EdDSAPrivateKeySpec(seed, spec))
val engine = EdDSAEngine().apply { initSign(priv) }
engine.update(manifestBytes)
val sig = engine.sign()   // 64 bytes
```

The instrumented test in Task 4 follows this pattern.

## Production (Phase 1d / release ceremony)

v2.0 release will:
- Generate a NEW keypair on an air-gapped machine for production.
- Keep that private key in the release engineer's hardware security module.
- Update `BOOTSTRAP_PUBKEY_HEX` to the production public key.
- Build signed manifests offline and upload to GitHub Release.

The dev key is for internal testing only and MUST NOT be used for
production artifact signing.
```

### Step 3: Wire the committed pubkey into `BuildRuntimeModule.kt`

Read `app/src/main/kotlin/com/vibe/app/di/BuildRuntimeModule.kt`. Locate the `provideManifestSignature` function. Replace it with:

```kotlin
    /**
     * Dev-cycle Ed25519 public key for bootstrap manifest verification.
     * Matches the private seed in ~/.vibeapp/dev-bootstrap-privseed.hex on
     * the developer's workstation. See docs/bootstrap/dev-keypair-setup.md.
     *
     * Production ceremony (Phase 1d) will replace this with a CI-injected value.
     */
    const val BOOTSTRAP_PUBKEY_HEX: String = "<PASTE THE PUBHEX FROM STEP 1 HERE>"

    @Provides
    @Singleton
    fun provideManifestSignature(): ManifestSignature =
        ManifestSignature(publicKeyHex = BOOTSTRAP_PUBKEY_HEX)
```

**IMPORTANT**: replace `<PASTE THE PUBHEX FROM STEP 1 HERE>` with the actual 64-character hex string you captured in Step 1. Do NOT commit with the placeholder string.

### Step 4: Verify `:app:assembleDebug` still works

```bash
./gradlew --no-daemon :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

### Step 5: Commit

```bash
git add docs/bootstrap/dev-keypair-setup.md \
        app/src/main/kotlin/com/vibe/app/di/BuildRuntimeModule.kt
git commit -m "feat(bootstrap): wire dev Ed25519 pubkey for manifest verification

Dev-cycle keypair replaces the '00'*32 placeholder. Public key is
committed as a const in BuildRuntimeModule; private seed lives in
~/.vibeapp/dev-bootstrap-privseed.hex (outside the repo) per
docs/bootstrap/dev-keypair-setup.md. Production key ceremony is a
future Phase 1d task.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

### Step 6: Verify `git status` clean + throwaway file removed

```bash
git status
ls build-runtime/src/test/kotlin/com/vibe/build/runtime/bootstrap/GenerateDevKeypairRunOnce.kt 2>/dev/null || echo "correctly deleted"
```

The throwaway generator test must NOT be in the commit. If you accidentally committed it, amend the commit to remove it.

---

## Task 3: Debug UI — `BuildRuntimeDebugScreen`

**Files:**
- Create: `app/src/main/kotlin/com/vibe/app/feature/bootstrap/BuildRuntimeDebugState.kt`
- Create: `app/src/main/kotlin/com/vibe/app/feature/bootstrap/BuildRuntimeDebugViewModel.kt`
- Create: `app/src/main/kotlin/com/vibe/app/feature/bootstrap/BuildRuntimeDebugScreen.kt`
- Modify: `app/src/main/kotlin/com/vibe/app/presentation/ui/setting/SettingScreen.kt`
- Modify: `app/src/main/kotlin/com/vibe/app/di/BuildRuntimeModule.kt` (add `bootstrapManifestUrl` provider)

### Step 1: Create `BuildRuntimeDebugState.kt`

```kotlin
package com.vibe.app.feature.bootstrap

import com.vibe.build.runtime.bootstrap.BootstrapState

/**
 * UI-facing state for the debug Build Runtime screen.
 *
 * [bootstrap] mirrors [com.vibe.build.runtime.bootstrap.BootstrapState].
 * [launchLog] accumulates stdout/stderr from the "launch test process"
 * button for quick diagnostic.
 */
data class BuildRuntimeDebugState(
    val bootstrap: BootstrapState = BootstrapState.NotInstalled,
    val manifestUrl: String = "",
    val launchLog: String = "",
    val launchRunning: Boolean = false,
)
```

### Step 2: Create `BuildRuntimeDebugViewModel.kt`

```kotlin
package com.vibe.app.feature.bootstrap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    @Named("bootstrapManifestUrl") private val manifestUrl: String,
    @Named("appCacheDir") private val cacheDir: File,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        BuildRuntimeDebugState(manifestUrl = manifestUrl),
    )
    val uiState: StateFlow<BuildRuntimeDebugState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            store.state.collectLatest { bootstrapState ->
                _uiState.update { it.copy(bootstrap = bootstrapState) }
            }
        }
    }

    fun triggerBootstrap() {
        viewModelScope.launch {
            bootstrapper.bootstrap(manifestUrl = manifestUrl) { /* store already updates UI */ }
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
                    when (ev) {
                        is ProcessEvent.Stdout -> log.append(String(ev.bytes, Charsets.UTF_8))
                        is ProcessEvent.Stderr -> log.append("[stderr] ")
                            .append(String(ev.bytes, Charsets.UTF_8))
                        is ProcessEvent.Exited -> log.append("\n[exit ${ev.code}]")
                    }
                }
            } catch (t: Throwable) {
                log.append("[error] ${t.message}")
            }
            _uiState.update {
                it.copy(
                    launchRunning = false,
                    launchLog = log.toString(),
                )
            }
        }
    }
}
```

### Step 3: Create `BuildRuntimeDebugScreen.kt`

```kotlin
package com.vibe.app.feature.bootstrap

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vibe.build.runtime.bootstrap.BootstrapState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuildRuntimeDebugScreen(
    onBack: () -> Unit,
    viewModel: BuildRuntimeDebugViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Build Runtime (debug)") })
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Manifest URL", style = MaterialTheme.typography.labelMedium)
            Text(ui.manifestUrl, style = MaterialTheme.typography.bodySmall)

            Divider()

            Text("Bootstrap state", style = MaterialTheme.typography.labelMedium)
            Text(ui.bootstrap.describe(), fontFamily = FontFamily.Monospace)

            if (ui.bootstrap is BootstrapState.Downloading) {
                val dl = ui.bootstrap as BootstrapState.Downloading
                LinearProgressIndicator(
                    progress = { (dl.percent / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Button(
                onClick = viewModel::triggerBootstrap,
                enabled = ui.bootstrap !is BootstrapState.Downloading &&
                        ui.bootstrap !is BootstrapState.Verifying &&
                        ui.bootstrap !is BootstrapState.Unpacking &&
                        ui.bootstrap !is BootstrapState.Installing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Trigger bootstrap")
            }

            Divider()

            Text("Launch test process", style = MaterialTheme.typography.labelMedium)
            Button(
                onClick = viewModel::launchTestProcess,
                enabled = !ui.launchRunning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Launch toybox echo")
            }

            if (ui.launchLog.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    ui.launchLog,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(16.dp))
            Button(onClick = onBack) { Text("Back") }
        }
    }
}

private fun BootstrapState.describe(): String = when (this) {
    is BootstrapState.NotInstalled -> "NotInstalled"
    is BootstrapState.Downloading -> "Downloading ${componentId}: $bytesRead/$totalBytes ($percent%)"
    is BootstrapState.Verifying -> "Verifying ${componentId}"
    is BootstrapState.Unpacking -> "Unpacking ${componentId}"
    is BootstrapState.Installing -> "Installing ${componentId}"
    is BootstrapState.Ready -> "Ready ($manifestVersion)"
    is BootstrapState.Failed -> "Failed: $reason"
    is BootstrapState.Corrupted -> "Corrupted: $reason"
}
```

### Step 4: Add two `@Named` providers to `BuildRuntimeModule.kt`

Inside the `BuildRuntimeModule` object, append:

```kotlin
    @Provides
    @Singleton
    @Named("bootstrapManifestUrl")
    fun provideBootstrapManifestUrl(): String =
        "https://github.com/Skykai521/VibeApp/releases/download/v2.0.0/manifest.json"

    @Provides
    @Singleton
    @Named("appCacheDir")
    fun provideAppCacheDir(@ApplicationContext context: Context): java.io.File =
        context.cacheDir
```

Add the required import if missing:

```kotlin
import javax.inject.Named
```

### Step 5: Add a debug-mode-gated entry in `SettingScreen.kt`

Read `app/src/main/kotlin/com/vibe/app/presentation/ui/setting/SettingScreen.kt`. Locate the area where other Settings items render (e.g. `About`, `Licenses`). Add a new item rendered only when `settingViewModel.uiState.debugMode == true` (or the equivalent existing flag — check `SettingViewModelV2`):

```kotlin
// Inside the Column/LazyColumn that lists setting items, after the existing
// "About" / "License" items:
if (uiState.debugMode) {
    ListItem(
        headlineContent = { Text("Build Runtime (debug)") },
        supportingContent = { Text("Trigger bootstrap + launch test process") },
        modifier = Modifier.clickable { onNavigateToBuildRuntimeDebug() },
    )
}
```

The exact Compose widget to match existing style depends on the file's conventions — check the file and use whatever the other settings items use (`ListItem`, `SettingRow`, custom composable, etc.). Add `onNavigateToBuildRuntimeDebug: () -> Unit` as a parameter to `SettingScreen` and wire it through the nav graph.

**Navigation wiring**: Locate the existing nav graph (likely `app/src/main/kotlin/com/vibe/app/presentation/ui/main/MainNavigation.kt` or similar — grep for `NavHost` / `composable(`). Add a new route:

```kotlin
composable("build_runtime_debug") {
    BuildRuntimeDebugScreen(onBack = { navController.popBackStack() })
}
```

And from the Settings composable invocation, pass:
```kotlin
onNavigateToBuildRuntimeDebug = { navController.navigate("build_runtime_debug") }
```

### Step 6: Verify everything compiles and the Hilt graph resolves

```bash
./gradlew --no-daemon :app:kspDebugKotlin :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

### Step 7: Commit

```bash
git add app/src/main/kotlin/com/vibe/app/feature/bootstrap/ \
        app/src/main/kotlin/com/vibe/app/presentation/ui/setting/SettingScreen.kt \
        app/src/main/kotlin/com/vibe/app/di/BuildRuntimeModule.kt
# Also the nav file if you modified it:
# git add app/src/main/kotlin/com/vibe/app/presentation/ui/main/MainNavigation.kt
git commit -m "feat(app): debug UI for bootstrap + process runtime

Adds BuildRuntimeDebugScreen behind the existing debug-mode flag:
- shows current bootstrap state + progress
- button to trigger RuntimeBootstrapper against the production manifest URL
- button to launch a /system/bin/toybox echo via ProcessLauncher
  and display stdout

Two new @Named providers in BuildRuntimeModule for the manifest URL
and the app cacheDir (used as the ProcessLauncher cwd for the test
launch).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: End-to-end instrumented test

**Files:**
- Create: `build-runtime/src/androidTest/kotlin/com/vibe/build/runtime/BootstrapEndToEndInstrumentedTest.kt`

This test proves Phase 1c's acceptance: a synthetic manifest + signature + `.tar.zst` is served from MockWebServer, `RuntimeBootstrapper` completes the full cycle on a real device, and then `ProcessLauncher` can `exec` a binary **from the extracted directory**.

For the "binary to exec", the test uses a trick: the `.tar.zst` contains a symlink-in-bytes to `/system/bin/toybox` — actually simpler: the `.tar.zst` contains a **small shell script** with mode 0755, and the test copies `/system/bin/toybox` into the extracted tree post-install for the exec step. Since we don't have libtermux-exec.so yet, the exec target itself must NOT have a shebang — it must be a binary ELF.

**Concrete design:** the `.tar.zst` contains one file `hello/world.txt`. After install, the test asserts that file exists. To prove exec works against content *inside* the extracted tree, the test then runs `/system/bin/toybox cat <extracted-path>/hello/world.txt` and verifies stdout matches the text. This proves:
- Bootstrap download → signature verify → extract → install all work end-to-end.
- ProcessLauncher can exec a system binary with args that reference the extracted filesystem.

That's enough for Phase 1c acceptance without needing libtermux-exec.

### Step 1: Create `BootstrapEndToEndInstrumentedTest.kt`

```kotlin
package com.vibe.build.runtime

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.luben.zstd.ZstdOutputStream
import com.vibe.build.runtime.bootstrap.Abi
import com.vibe.build.runtime.bootstrap.ArchArtifact
import com.vibe.build.runtime.bootstrap.BootstrapComponent
import com.vibe.build.runtime.bootstrap.BootstrapDownloader
import com.vibe.build.runtime.bootstrap.BootstrapFileSystem
import com.vibe.build.runtime.bootstrap.BootstrapManifest
import com.vibe.build.runtime.bootstrap.BootstrapState
import com.vibe.build.runtime.bootstrap.InMemoryBootstrapStateStore
import com.vibe.build.runtime.bootstrap.ManifestFetcher
import com.vibe.build.runtime.bootstrap.ManifestParser
import com.vibe.build.runtime.bootstrap.ManifestSignature
import com.vibe.build.runtime.bootstrap.MirrorSelector
import com.vibe.build.runtime.bootstrap.RuntimeBootstrapper
import com.vibe.build.runtime.bootstrap.ZstdExtractor
import com.vibe.build.runtime.process.NativeProcessLauncher
import com.vibe.build.runtime.process.ProcessEnvBuilder
import com.vibe.build.runtime.process.ProcessEvent
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

@RunWith(AndroidJUnit4::class)
class BootstrapEndToEndInstrumentedTest {

    private lateinit var server: MockWebServer
    private lateinit var scratchDir: File

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        scratchDir = File(ctx.cacheDir, "bootstrap-e2e-${System.nanoTime()}")
        require(scratchDir.mkdirs())
    }

    @After
    fun tearDown() {
        server.shutdown()
        scratchDir.deleteRecursively()
    }

    @Test
    fun bootstrap_end_to_end_then_exec_reads_extracted_file() = runBlocking {
        // 1. Generate ephemeral Ed25519 keypair for this test
        val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val edSpec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
        val priv = EdDSAPrivateKey(EdDSAPrivateKeySpec(seed, edSpec))
        val pubHex = priv.abyte.joinToString("") { "%02x".format(it) }

        // 2. Synthetic payload: a tar.zst containing hello/world.txt
        val payload = "phase-1c end-to-end OK\n".toByteArray()
        val tarZst = ByteArrayOutputStream().use { raw ->
            ZstdOutputStream(raw).use { zstd ->
                TarArchiveOutputStream(zstd).use { tar ->
                    tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                    val entry = TarArchiveEntry("hello/world.txt")
                    entry.size = payload.size.toLong()
                    tar.putArchiveEntry(entry)
                    tar.write(payload)
                    tar.closeArchiveEntry()
                }
            }
            raw.toByteArray()
        }
        val artifactSha = MessageDigest.getInstance("SHA-256").digest(tarZst)
            .joinToString("") { "%02x".format(it) }

        // 3. Manifest referencing that artifact
        val manifest = BootstrapManifest(
            schemaVersion = 1,
            manifestVersion = "v2.0.0-phase-1c-e2e",
            components = listOf(
                BootstrapComponent(
                    id = "hello",
                    version = "1.0",
                    artifacts = mapOf(
                        "common" to ArchArtifact(
                            fileName = "hello.tar.zst",
                            sizeBytes = tarZst.size.toLong(),
                            sha256 = artifactSha,
                        ),
                    ),
                ),
            ),
        )
        val manifestJson = kotlinx.serialization.json.Json.encodeToString(
            BootstrapManifest.serializer(), manifest,
        ).toByteArray()

        // 4. Sign with ephemeral private key
        val sig = EdDSAEngine().apply { initSign(priv) }.run {
            update(manifestJson)
            sign()
        }

        // 5. Enqueue responses: manifest, manifest.sig, tar.zst
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(manifestJson)))
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(sig)))
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Length", tarZst.size.toString())
                .setBody(Buffer().write(tarZst)),
        )

        // 6. Assemble the full collaborator graph against the MockWebServer
        val fs = BootstrapFileSystem(filesDir = scratchDir)
        fs.ensureDirectories()
        val store = InMemoryBootstrapStateStore()
        val mirrors = MirrorSelector(
            primaryBase = server.url("").toString().trimEnd('/'),
            fallbackBase = "https://unused.test",
        )
        val signature = ManifestSignature(publicKeyHex = pubHex)
        val parser = ManifestParser()
        val downloader = BootstrapDownloader()
        val extractor = ZstdExtractor()
        val fetcher = ManifestFetcher(mirrors)

        val bootstrapper = RuntimeBootstrapper(
            fs = fs,
            store = store,
            parser = parser,
            signature = signature,
            mirrors = mirrors,
            downloader = downloader,
            extractor = extractor,
            abi = Abi.pickPreferred(Build.SUPPORTED_ABIS) ?: Abi.ARM64,
            fetcher = fetcher,
            parsedManifestOverride = null, // force real fetch path
        )

        val seen = mutableListOf<BootstrapState>()
        bootstrapper.bootstrap(manifestUrl = "manifest.json") { seen += it }

        val terminal = seen.last()
        assertTrue("expected Ready, got $terminal", terminal is BootstrapState.Ready)

        // 7. Prove exec works against a path inside the extracted tree
        val launcher = NativeProcessLauncher(ProcessEnvBuilder(fs))
        val extractedFile = File(fs.componentInstallDir("hello"), "hello/world.txt")
        assertTrue("expected file at $extractedFile", extractedFile.isFile)

        val process = launcher.launch(
            executable = "/system/bin/toybox",
            args = listOf("cat", extractedFile.absolutePath),
            cwd = scratchDir,
        )
        val events = withTimeout(10_000) { process.events.toList() }

        val stdout = events.filterIsInstance<ProcessEvent.Stdout>()
            .fold(ByteArray(0)) { acc, e -> acc + e.bytes }
        val exit = events.filterIsInstance<ProcessEvent.Exited>().first()

        assertEquals(0, exit.code)
        assertEquals("phase-1c end-to-end OK\n", String(stdout, Charsets.UTF_8))
    }
}
```

### Step 2: Run on an emulator

```bash
./gradlew --no-daemon :build-runtime:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.vibe.build.runtime.BootstrapEndToEndInstrumentedTest
```

Expected: 1 test, 0 failures. First run may take 3-5 minutes (APK build + install + test execution).

If it fails, read the full logcat output via:
```bash
/Users/skykai/Library/Android/sdk/platform-tools/adb logcat -d | grep -E "(RuntimeBootstrapper|BootstrapEndToEndInstrumentedTest|AndroidRuntime)"
```

Common failure modes:
- `SignatureMismatchException`: pubkey / priv seed mismatch. Verify `priv.abyte` matches `pubHex` in the test.
- `HashMismatchException`: the tar.zst bytes changed between generation and hashing. Unlikely if code is as shown, but re-check the `MessageDigest` invocation order.
- Cleartext HTTP from MockWebServer (`127.0.0.1` by default): Phase 1a already installed a debug `network_security_config.xml` permitting localhost cleartext. If the error is `CLEARTEXT communication not permitted`, verify that config is still present in `build-runtime/src/debug/`.

### Step 3: Commit

```bash
git add build-runtime/src/androidTest/kotlin/com/vibe/build/runtime/BootstrapEndToEndInstrumentedTest.kt
git commit -m "test(build-runtime): end-to-end Phase 1c integration test

Full-stack instrumented test that proves the Phase 1 acceptance
without yet needing real 180MB artifacts or libtermux-exec:

  synthetic manifest (signed with ephemeral Ed25519 key)
    → ManifestFetcher → ManifestSignature.verify
    → ManifestParser → RuntimeBootstrapper state machine
    → BootstrapDownloader → ZstdExtractor
    → hello/world.txt installed under usr/opt/hello/
  Then NativeProcessLauncher execs '/system/bin/toybox cat
  <extracted-path>' and verifies stdout matches the file's
  content.

Ref: docs/superpowers/plans/2026-04-18-v2-phase-1c-bootstrap-integration.md Task 4

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

### Step 4: Final regression

```bash
./gradlew --no-daemon :build-runtime:testDebugUnitTest \
    :build-runtime:connectedDebugAndroidTest \
    :app:kspDebugKotlin \
    :app:assembleDebug \
    :build-runtime:lintDebug
```

Expected: everything BUILD SUCCESSFUL. Unit tests = 57. Instrumented tests = 6 (Phase 1a had 1, Phase 1b had 4, Phase 1c adds 1).

---

## Phase 1c Exit Criteria

- [ ] `./gradlew :build-runtime:testDebugUnitTest` passes with 57 tests, 0 failures.
- [ ] `./gradlew :build-runtime:assembleDebug` succeeds.
- [ ] `./gradlew :app:assembleDebug` succeeds.
- [ ] `./gradlew :app:kspDebugKotlin` succeeds.
- [ ] `./gradlew :build-runtime:connectedDebugAndroidTest` passes 6 tests on an API 29+ emulator (or all compile + previous 5 still pass if emulator is unavailable for the new test).
- [ ] `BuildRuntimeDebugScreen` appears in Settings only when debug-mode is on.
- [ ] The dev keypair's public hex is committed in `BuildRuntimeModule.kt`. The private seed is NOT in git (`git log -p | grep -i priv` returns nothing suggestive).
- [ ] `docs/bootstrap/dev-keypair-setup.md` explains regeneration.
- [ ] `parsedManifestOverride` still works for Phase 1a tests; real fetch path covered by the new Task 1 test + Task 4 e2e.
- [ ] No changes to `:build-engine`, `:build-tools:*`, `:shadow-runtime`, `:build-gradle`, `:plugin-host`.

When all boxes are checked, Phase 1c is complete. Phase 1d (real 180MB artifact hosting + production key ceremony) and/or Phase 2 (GradleHost + Tooling API, which needs `libtermux-exec.so` for shebangs) follows.

---

## Self-Review Notes

**Spec coverage against design doc §3:**
- §3.4 state machine + Ready transition → Task 1 real fetch path.
- §3.5 mirror strategy in the manifest fetch leg → `ManifestFetcher` uses `MirrorSelector`.
- §3.4 DataStore persistence of `BootstrapState` → already delivered in Phase 1a; Task 3 only *observes* it.
- §3.6 process runtime → Phase 1b delivered; Task 4 demonstrates end-to-end interaction.
- §3.9 testing: `ManifestFetcherTest` (JVM) + new `RuntimeBootstrapperTest` case + `BootstrapEndToEndInstrumentedTest` (device).

**Placeholders / gaps:**
- `libtermux-exec.so` still not added — see Phase 2 prep. The e2e test explicitly uses `/system/bin/toybox` as the exec target, which doesn't need shebang resolution, so Phase 1c acceptance is achievable without it.
- Production pubkey is still the dev key. The doc explicitly flags this.
- The `bootstrapManifestUrl` is hardcoded to a GitHub URL that will 404 until Phase 1d uploads a real manifest. For Phase 1c, only the e2e test (which uses MockWebServer) runs a real fetch; the debug UI in Task 3 is a manual-tap tool that will appear to "fail" until Phase 1d — acceptable and documented.

**Type consistency:**
- `ManifestFetcher.Fetched(manifestBytes, signatureBytes)` matches the `signature.verify(manifestBytes, signatureBytes)` and `parser.parse(manifestBytes)` signatures.
- `RuntimeBootstrapper` constructor now has 10 parameters (9 before + `fetcher`); all 5 call sites in the Phase 1a test file are updated in Task 1 Step 6.
- `BuildRuntimeDebugState.bootstrap: BootstrapState` unchanged from §3.4 sealed hierarchy.
- `ManifestSignature(publicKeyHex = BOOTSTRAP_PUBKEY_HEX)` in Task 2 matches the Phase 1a constructor signature.
- `ProcessEnvBuilder` + `NativeProcessLauncher` invocation in Task 4 matches the Phase 1b public API.

No "TBD", "TODO", "similar to Task N", or "add error handling" placeholders remain. Where a future-phase item is referenced (libtermux-exec, real 180MB artifacts, production key ceremony), it is clearly marked "Phase 1d" or "Phase 2".
