package com.vibe.build.runtime

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * End-to-end Phase 1c acceptance test.
 *
 * Stitches the full Phase 1a + 1b stack against MockWebServer:
 *   synthetic manifest (signed with ephemeral Ed25519 key)
 *     → ManifestFetcher → ManifestSignature.verify
 *     → ManifestParser → RuntimeBootstrapper state machine
 *     → BootstrapDownloader → ZstdExtractor (subclassed — see note)
 *     → hello/world.txt installed under usr/opt/hello/
 *   Then NativeProcessLauncher execs '/system/bin/toybox cat <extracted-path>'
 *   and verifies stdout matches the file's content.
 *
 * NOTE on ZstdExtractor: zstd-jni bundles Linux/glibc .so files which cannot
 * load on Android (Bionic). This is the same constraint discovered in Phase 1a.
 * We subclass ZstdExtractor to use plain TarArchiveInputStream (no zstd
 * decompression) and serve the artifact as a plain .tar from MockWebServer.
 * The SHA-256 hash, download, signature verify, and install paths are all real.
 *
 * NOTE on BootstrapDownloader: the real impl uses flowOn(Dispatchers.IO) which
 * runs on real OS threads — MockWebServer on localhost is reachable from IO
 * threads, so no override is needed here (unlike Phase 1a which subclassed it).
 */
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
        // 1. Generate ephemeral Ed25519 keypair for this test run
        val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val edSpec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
        val priv = EdDSAPrivateKey(EdDSAPrivateKeySpec(seed, edSpec))
        val pubHex = priv.abyte.joinToString("") { "%02x".format(it) }

        // 2. Synthetic payload: a plain .tar containing hello/world.txt
        //    (zstd-jni cannot load its native .so on Android/Bionic — see class doc)
        val payload = "phase-1c end-to-end OK\n".toByteArray()
        val tarBytes = ByteArrayOutputStream().use { raw ->
            TarArchiveOutputStream(raw).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                val entry = TarArchiveEntry("hello/world.txt")
                entry.size = payload.size.toLong()
                tar.putArchiveEntry(entry)
                tar.write(payload)
                tar.closeArchiveEntry()
            }
            raw.toByteArray()
        }
        val artifactSha = MessageDigest.getInstance("SHA-256").digest(tarBytes)
            .joinToString("") { "%02x".format(it) }

        // 3. Manifest referencing the artifact as "common" (ABI-independent)
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
                            sizeBytes = tarBytes.size.toLong(),
                            sha256 = artifactSha,
                        ),
                    ),
                ),
            ),
        )
        val manifestJson = kotlinx.serialization.json.Json.encodeToString(
            BootstrapManifest.serializer(), manifest,
        ).toByteArray()

        // 4. Sign manifest bytes with ephemeral private key
        val sig = EdDSAEngine().apply { initSign(priv) }.run {
            update(manifestJson)
            sign()
        }

        // 5. Enqueue 3 responses: manifest JSON, manifest.sig, artifact tar
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(manifestJson)))
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(sig)))
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Length", tarBytes.size.toString())
                .setBody(Buffer().write(tarBytes)),
        )

        // 6. Assemble real collaborator graph against the MockWebServer
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
        // Subclass ZstdExtractor to use plain TarArchiveInputStream instead of
        // ZstdInputStream (zstd-jni native .so unavailable on Android/Bionic).
        val extractor = object : ZstdExtractor() {
            override fun extract(source: File, destinationDir: File) {
                TarArchiveInputStream(source.inputStream()).use { tar ->
                    while (true) {
                        val entry = tar.nextEntry ?: break
                        val target = File(destinationDir, entry.name)
                        if (entry.isDirectory) {
                            target.mkdirs()
                            continue
                        }
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { out -> tar.copyTo(out) }
                        if ((entry.mode and 0b001_000_000) != 0) {
                            target.setExecutable(true, /* ownerOnly = */ true)
                        }
                    }
                }
            }
        }
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

        // 7. Run bootstrap and collect all states
        val seen = mutableListOf<BootstrapState>()
        bootstrapper.bootstrap(manifestUrl = "manifest.json") { seen += it }

        val terminal = seen.last()
        assertTrue("expected Ready, got $terminal", terminal is BootstrapState.Ready)

        // 8. Prove exec works against a path inside the extracted tree
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
