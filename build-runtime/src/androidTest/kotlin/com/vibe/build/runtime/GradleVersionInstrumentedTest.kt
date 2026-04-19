package com.vibe.build.runtime

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
import com.vibe.build.runtime.process.ProcessEvent
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
 * Phase 2b acceptance. Opt-in via
 * `-Pandroid.testInstrumentationRunnerArguments.vibeapp.phase2b.dev_server_url=http://localhost:8000`.
 * Requires the dev server to be serving a manifest that lists BOTH
 * the `jdk-17.0.13` AND `gradle-9.3.1` components (produce via
 * `scripts/bootstrap/build-{jdk,gradle}.sh` + `build-manifest.sh`).
 *
 * Skipped when the runner argument is absent — CI stays green.
 */
@RunWith(AndroidJUnit4::class)
class GradleVersionInstrumentedTest {

    private lateinit var ctx: Context
    private lateinit var scratchDir: File
    private lateinit var fs: BootstrapFileSystem
    private lateinit var launcher: NativeProcessLauncher
    private lateinit var bootstrapper: RuntimeBootstrapper

    private fun devServerUrlOrSkip(): String {
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("vibeapp.phase2b.dev_server_url")
        assumeTrue(
            "vibeapp.phase2b.dev_server_url not provided; skipping Phase 2b acceptance",
            !url.isNullOrBlank(),
        )
        return url!!.trimEnd('/')
    }

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        scratchDir = File(ctx.cacheDir, "phase2b-${System.nanoTime()}")
        require(scratchDir.mkdirs())

        fs = BootstrapFileSystem(filesDir = scratchDir)
        fs.ensureDirectories()

        val preloadLib = PreloadLibLocator(File(ctx.applicationInfo.nativeLibraryDir))
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
        // Same dev pubkey as Phase 2a; see docs/bootstrap/dev-keypair-setup.md.
        val devPubkeyHex = "5ce0c624f59a72ee8eb6f72c25ad905a27afcd0392998f353ef86f3247725f40"
        val signature = ManifestSignature(publicKeyHex = devPubkeyHex)
        return RuntimeBootstrapper(
            fs = fs,
            store = store,
            parser = ManifestParser(),
            signature = signature,
            mirrors = mirrors,
            downloader = BootstrapDownloader(),
            extractor = ZstdExtractor(),
            abi = Abi.pickPreferred(Build.SUPPORTED_ABIS) ?: Abi.ARM64,
            fetcher = ManifestFetcher(mirrors),
            parsedManifestOverride = null,
        )
    }

    @Test
    fun gradle_version_after_bootstrap() = runBlocking {
        val devUrl = devServerUrlOrSkip()
        bootstrapper = buildBootstrapper(devUrl)

        val seen = mutableListOf<BootstrapState>()
        bootstrapper.bootstrap(manifestUrl = "manifest.json") { seen += it }
        val terminal = seen.last()
        assertTrue("expected Ready, got $terminal", terminal is BootstrapState.Ready)

        val javaBinary = File(fs.componentInstallDir("jdk-17.0.13"), "bin/java")
        val launcherJar = File(
            fs.componentInstallDir("gradle-9.3.1"),
            "lib/gradle-launcher-9.3.1.jar",
        )
        assertTrue("java missing: $javaBinary", javaBinary.isFile)
        assertTrue("gradle launcher missing: $launcherJar", launcherJar.isFile)

        // GRADLE_USER_HOME needs to exist for gradle --version.
        File(fs.usrRoot.parentFile, ".gradle").mkdirs()

        val process = launcher.launch(
            executable = javaBinary.absolutePath,
            args = listOf(
                "-cp",
                launcherJar.absolutePath,
                "org.gradle.launcher.GradleMain",
                "--version",
                "--no-daemon",
            ),
            cwd = scratchDir,
        )
        // Gradle's first startup on an unfamiliar device (class loading,
        // native image caches) can take >30s; allow 60s.
        val events = withTimeout(60_000) { process.events.toList() }

        val stdout = events.filterIsInstance<ProcessEvent.Stdout>()
            .fold(ByteArray(0)) { acc, e -> acc + e.bytes }
        val stderr = events.filterIsInstance<ProcessEvent.Stderr>()
            .fold(ByteArray(0)) { acc, e -> acc + e.bytes }
        val exit = events.filterIsInstance<ProcessEvent.Exited>().first()
        val combined = String(stdout, Charsets.UTF_8) + String(stderr, Charsets.UTF_8)

        assertEquals(
            "gradle --version exit=$exit combined=$combined",
            0, exit.code,
        )
        assertTrue(
            "expected 'Gradle 9.3.1' in output:\n$combined",
            combined.contains("Gradle 9.3.1"),
        )
    }
}
