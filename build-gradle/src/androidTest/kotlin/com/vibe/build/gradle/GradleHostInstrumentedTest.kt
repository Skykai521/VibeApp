package com.vibe.build.gradle

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
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
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
 * Phase 2c acceptance. Opt-in via
 * `-Pandroid.testInstrumentationRunnerArguments.vibeapp.phase2c.dev_server_url=http://localhost:8000`.
 *
 * Bootstraps JDK + Gradle from dev server, extracts GradleHost JAR
 * from APK assets, spawns the JVM, asks it to run `:help` on a
 * synthetic empty project (just settings.gradle.kts). Asserts a
 * terminal BuildFinish(success=true).
 */
@RunWith(AndroidJUnit4::class)
class GradleHostInstrumentedTest {

    private lateinit var ctx: Context
    private lateinit var scratchDir: File
    private lateinit var fs: BootstrapFileSystem
    private lateinit var launcher: NativeProcessLauncher
    private lateinit var bootstrapper: RuntimeBootstrapper
    private lateinit var service: GradleBuildServiceImpl

    private fun devServerUrlOrSkip(): String {
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("vibeapp.phase2c.dev_server_url")
        assumeTrue(
            "vibeapp.phase2c.dev_server_url not provided; skipping Phase 2c acceptance",
            !url.isNullOrBlank(),
        )
        return url!!.trimEnd('/')
    }

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        scratchDir = File(ctx.cacheDir, "phase2c-${System.nanoTime()}")
        require(scratchDir.mkdirs())

        fs = BootstrapFileSystem(filesDir = scratchDir)
        fs.ensureDirectories()
        // :build-gradle's instrumented-test APK does NOT package
        // libtermux-exec.so from :build-runtime's native libs
        // (transitive JNI propagation doesn't happen for library test
        // APKs). We don't actually need the preload for this test —
        // we exec java directly (a real binary, not a script), so
        // shebang rewriting is irrelevant. Return empty path → LD_PRELOAD
        // gets set to "" which the linker treats as "no preload".
        val preloadLib = object : PreloadLibLocator(File(ctx.applicationInfo.nativeLibraryDir)) {
            override fun termuxExecLibPath(): String = ""
        }
        val envBuilder = ProcessEnvBuilder(fs, preloadLib)
        launcher = NativeProcessLauncher(envBuilder)
    }

    @After
    fun tearDown() {
        runBlocking {
            if (::service.isInitialized) {
                try { service.shutdown() } catch (_: Throwable) {}
            }
        }
        scratchDir.deleteRecursively()
    }

    private fun buildBootstrapper(devBaseUrl: String): RuntimeBootstrapper {
        val mirrors = MirrorSelector(
            primaryBase = devBaseUrl,
            fallbackBase = "https://unused.test",
        )
        val devPubkeyHex = "5ce0c624f59a72ee8eb6f72c25ad905a27afcd0392998f353ef86f3247725f40"
        return RuntimeBootstrapper(
            fs = fs,
            store = InMemoryBootstrapStateStore(),
            parser = ManifestParser(),
            signature = ManifestSignature(publicKeyHex = devPubkeyHex),
            mirrors = mirrors,
            downloader = BootstrapDownloader(),
            extractor = ZstdExtractor(),
            abi = Abi.pickPreferred(Build.SUPPORTED_ABIS) ?: Abi.ARM64,
            fetcher = ManifestFetcher(mirrors),
            parsedManifestOverride = null,
        )
    }

    @Test
    fun help_task_on_empty_project_succeeds() = runBlocking {
        val devUrl = devServerUrlOrSkip()
        bootstrapper = buildBootstrapper(devUrl)

        val seen = mutableListOf<BootstrapState>()
        bootstrapper.bootstrap(manifestUrl = "manifest.json") { seen += it }
        assertTrue("bootstrap failed: ${seen.last()}", seen.last() is BootstrapState.Ready)

        // Stage a synthetic empty Gradle project. The settings file alone is
        // enough for Gradle to recognize this as a project root.
        val probeDir = File(scratchDir, "probe")
        probeDir.mkdirs()
        File(probeDir, "settings.gradle.kts").writeText("""rootProject.name = "probe"""")

        // Extract the GradleHost JAR + build the service
        val extractor = GradleHostExtractor(ctx, fs)
        service = GradleBuildServiceImpl(
            launcher = launcher,
            envBuilder = ProcessEnvBuilder(fs, PreloadLibLocator(File(ctx.applicationInfo.nativeLibraryDir))),
            extractor = extractor,
            fs = fs,
        )

        val gradleDist = fs.componentInstallDir("gradle-8.10.2")
        val toolingApiVersion = withTimeout(60_000) { service.start(gradleDist) }
        assertTrue(
            "expected Tooling API 8.10.2, got '$toolingApiVersion'",
            toolingApiVersion.contains("8.10"),
        )

        val events = withTimeout(120_000) {
            service.runBuild(projectDirectory = probeDir, tasks = listOf(":help")).toList()
        }
        val finish = events.filterIsInstance<HostEvent.BuildFinish>().first()
        assertEquals(
            "expected success, got failure: ${finish.failureSummary}",
            true, finish.success,
        )
    }
}
