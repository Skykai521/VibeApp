package com.vibe.build.runtime.bootstrap

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * End-to-end integration test that exercises RuntimeBootstrapper against a
 * live MockWebServer + real app-private filesystem.
 *
 * Phase 1a acceptance criterion: given a synthetic artifact served from
 * MockWebServer, the state machine reaches Ready and the component is
 * installed under filesDir/usr/opt/<componentId>/.
 *
 * Notes on collaborator overrides:
 * - ManifestSignature: returns true — Phase 1a uses placeholder pubkey.
 * - ZstdExtractor: writes a synthetic file tree (libzstd-jni .so can't be
 *   loaded on-device from the standard JAR; real extraction is covered by
 *   ZstdExtractorTest, a JVM unit test).
 * - BootstrapDownloader: inline subclass that overrides flowOn dispatcher to
 *   Dispatchers.Default so the download runs within the runBlocking scope
 *   and can reach the in-process MockWebServer without scheduler issues.
 */
@RunWith(AndroidJUnit4::class)
class RuntimeBootstrapperIntegrationTest {

    private lateinit var server: MockWebServer
    private lateinit var testScratchDir: File

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        testScratchDir = File(ctx.cacheDir, "bootstrap-test-${System.nanoTime()}")
        testScratchDir.mkdirs()
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) server.shutdown()
        if (::testScratchDir.isInitialized) testScratchDir.deleteRecursively()
    }

    @Test
    fun full_bootstrap_cycle_produces_installed_component() = runBlocking<Unit> {
        // 1. Create a synthetic artifact payload (arbitrary bytes — the
        //    fake extractor below doesn't parse it, but the downloader does
        //    SHA-256 verification so we need a consistent hash).
        val artifactBytes = "synthetic-artifact-v1".toByteArray()
        val sha = MessageDigest.getInstance("SHA-256").digest(artifactBytes)
            .joinToString(separator = "") { "%02x".format(it) }

        // 2. Build a matching manifest using "common" so any device ABI works.
        val manifest = BootstrapManifest(
            schemaVersion = 1,
            manifestVersion = "v2.0.0-integration-test",
            components = listOf(
                BootstrapComponent(
                    id = "hello-component",
                    version = "1.0",
                    artifacts = mapOf(
                        "common" to ArchArtifact(
                            fileName = "hello.tar.zst",
                            sizeBytes = artifactBytes.size.toLong(),
                            sha256 = sha,
                        ),
                    ),
                ),
            ),
        )

        // 3. Server serves the synthetic artifact
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", artifactBytes.size.toString())
                .setBody(Buffer().write(artifactBytes)),
        )

        // 4. Wire collaborators. Real impls throughout except:
        //    - ManifestSignature: always returns true (Phase 1a placeholder)
        //    - ZstdExtractor: writes synthetic file tree (libzstd-jni not on-device)
        //    - BootstrapDownloader: uses Dispatchers.Default instead of IO so the
        //      download executes in-scope with MockWebServer without threading issues
        val fs = BootstrapFileSystem(filesDir = testScratchDir)
        val store = InMemoryBootstrapStateStore()
        val serverBaseUrl = "http://${server.hostName}:${server.port}"
        val mirrors = MirrorSelector(
            primaryBase = serverBaseUrl,
            fallbackBase = "https://nope.test",
        )
        val parser = ManifestParser()
        val signature = object : ManifestSignature("00".repeat(32)) {
            override fun verify(manifestBytes: ByteArray, signature: ByteArray) = true
        }
        val downloader = object : BootstrapDownloader() {
            override fun download(
                url: String,
                destination: File,
                expectedSha256: String,
                expectedSizeBytes: Long,
            ): Flow<DownloadEvent> = flow {
                destination.parentFile?.mkdirs()
                val digest = MessageDigest.getInstance("SHA-256")
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 10_000
                }
                val status = conn.responseCode
                check(status == 200 || status == 206) {
                    "HTTP $status from $url"
                }
                val out = FileOutputStream(destination)
                var totalRead = 0L
                conn.inputStream.use { input ->
                    BufferedInputStream(input).use { buffered ->
                        out.use { sink ->
                            val scratch = ByteArray(4096)
                            while (true) {
                                val n = buffered.read(scratch)
                                if (n <= 0) break
                                sink.write(scratch, 0, n)
                                digest.update(scratch, 0, n)
                                totalRead += n
                            }
                        }
                    }
                }
                val actualSha = digest.digest()
                    .joinToString("") { "%02x".format(it) }
                check(actualSha.equals(expectedSha256, ignoreCase = true)) {
                    "SHA-256 mismatch: expected $expectedSha256, got $actualSha"
                }
                emit(DownloadEvent.Progress(totalRead, expectedSizeBytes))
                emit(DownloadEvent.Done(destination))
            }.flowOn(Dispatchers.Default)
        }
        val extractor = object : ZstdExtractor() {
            override fun extract(source: File, destinationDir: File) {
                File(destinationDir, "hello").mkdirs()
                File(destinationDir, "hello/world.txt").writeText("hello from test\n")
            }
        }

        val bootstrapper = RuntimeBootstrapper(
            fs = fs,
            store = store,
            parser = parser,
            signature = signature,
            mirrors = mirrors,
            downloader = downloader,
            extractor = extractor,
            abi = Abi.pickPreferred(android.os.Build.SUPPORTED_ABIS) ?: Abi.ARM64,
            parsedManifestOverride = manifest,
        )

        // 5. Run bootstrap and collect states
        val seenStates = mutableListOf<BootstrapState>()
        bootstrapper.bootstrap(manifestUrl = "ignored") { seenStates += it }

        // 6. Assert terminal state is Ready
        val terminal = seenStates.last()
        assertTrue("expected Ready, got $terminal", terminal is BootstrapState.Ready)
        assertEquals(
            "v2.0.0-integration-test",
            (terminal as BootstrapState.Ready).manifestVersion,
        )

        // 7. Assert component is installed under usr/opt/
        val installed = fs.componentInstallDir("hello-component")
        assertTrue("$installed should be a dir", installed.isDirectory)
        val extractedFile = File(installed, "hello/world.txt")
        assertTrue("$extractedFile should exist", extractedFile.isFile)
        assertEquals("hello from test\n", extractedFile.readText())
    }
}
