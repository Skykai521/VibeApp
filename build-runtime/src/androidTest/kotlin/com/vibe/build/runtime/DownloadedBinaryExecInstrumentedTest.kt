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
