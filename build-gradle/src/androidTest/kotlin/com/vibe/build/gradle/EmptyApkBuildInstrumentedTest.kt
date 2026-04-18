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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

/**
 * Phase 2d acceptance. Same opt-in arg as 2c:
 *   -Pandroid.testInstrumentationRunnerArguments.vibeapp.phase2c.dev_server_url=http://10.0.2.2:8000
 *
 * Runs full bootstrap (JDK + Gradle + Android SDK 36.0.0), stages the
 * probe-app template from androidTest/assets to filesDir/projects/probe,
 * and runs :app:assembleDebug via GradleBuildService. Asserts the APK
 * exists, is a valid zip (PK header), and contains AndroidManifest.xml.
 */
@RunWith(AndroidJUnit4::class)
class EmptyApkBuildInstrumentedTest {

    private lateinit var ctx: Context
    private lateinit var scratchDir: File
    private lateinit var fs: BootstrapFileSystem
    private lateinit var launcher: NativeProcessLauncher
    private lateinit var service: GradleBuildServiceImpl

    private fun devServerUrlOrSkip(): String {
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("vibeapp.phase2c.dev_server_url")
        assumeTrue(
            "vibeapp.phase2c.dev_server_url not provided; skipping Phase 2d acceptance",
            !url.isNullOrBlank(),
        )
        return url!!.trimEnd('/')
    }

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        scratchDir = File(ctx.cacheDir, "phase2d-${System.nanoTime()}")
        require(scratchDir.mkdirs())

        fs = BootstrapFileSystem(filesDir = scratchDir)
        fs.ensureDirectories()
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

    @Test
    fun probe_app_assembleDebug_produces_installable_apk() = runBlocking {
        val devUrl = devServerUrlOrSkip()

        val mirrors = MirrorSelector(primaryBase = devUrl, fallbackBase = "https://unused.test")
        val devPubkeyHex = "5ce0c624f59a72ee8eb6f72c25ad905a27afcd0392998f353ef86f3247725f40"
        val bootstrapper = RuntimeBootstrapper(
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

        val seen = mutableListOf<BootstrapState>()
        withTimeout(300_000) {
            bootstrapper.bootstrap(manifestUrl = "manifest.json") { seen += it }
        }
        assertTrue("bootstrap failed: ${seen.last()}", seen.last() is BootstrapState.Ready)

        // Extract the probe template from androidTest assets to a temp
        // directory, then stage it onto filesDir via ProjectStager.
        val templateSrc = File(scratchDir, "probe-src").also { it.mkdirs() }
        copyAssetDir(ctx, "probe-app", templateSrc)

        val projectDir = File(scratchDir, "projects/probe")
        val sdkDir = fs.componentInstallDir("android-sdk-36.0.0")
        val gradleUserHome = File(scratchDir, ".gradle")
        ProjectStager().stage(
            template = ProjectTemplate.FromDirectory(templateSrc),
            destinationDir = projectDir,
            variables = mapOf(
                "SDK_DIR" to sdkDir.absolutePath,
                "GRADLE_USER_HOME" to gradleUserHome.absolutePath,
            ),
        )

        val gradleDist = fs.componentInstallDir("gradle-9.3.1")
        val javaBinary = File(fs.componentInstallDir("jdk-17.0.13"), "bin/java")
        assertTrue("java binary missing at $javaBinary", javaBinary.canExecute())
        assertTrue("aapt2 missing", File(sdkDir, "build-tools/36.0.0/aapt2").canExecute())

        service = GradleBuildServiceImpl(
            launcher = launcher,
            envBuilder = ProcessEnvBuilder(fs, PreloadLibLocator(File(ctx.applicationInfo.nativeLibraryDir))),
            extractor = GradleHostExtractor(ctx, fs),
            fs = fs,
        )

        withTimeout(60_000) { service.start(gradleDist) }

        val events = withTimeout(600_000) {
            service.runBuild(
                projectDirectory = projectDir,
                tasks = listOf(":app:assembleDebug"),
                // Tooling API always runs a daemon; --no-daemon is rejected.
                // Configuration-cache disable is set in probe-app's gradle.properties.
                args = emptyList(),
            ).toList()
        }

        val finish = events.filterIsInstance<HostEvent.BuildFinish>().firstOrNull()
        val err = events.filterIsInstance<HostEvent.Error>().firstOrNull()
        val logLines = events.filterIsInstance<HostEvent.Log>().joinToString("\n") { "[${it.level}] ${it.text}" }
        if (finish == null) {
            val dump = buildString {
                appendLine("No BuildFinish. Event summary:")
                events.forEach { appendLine("  - ${it::class.simpleName}: $it") }
                if (err != null) {
                    appendLine("Error event:")
                    appendLine("  class=${err.exceptionClass}")
                    appendLine("  msg=${err.message}")
                }
                if (logLines.isNotEmpty()) {
                    appendLine("Logs:")
                    appendLine(logLines)
                }
            }
            org.junit.Assert.fail(dump)
            return@runBlocking
        }
        assertEquals(
            "assembleDebug failed: ${finish.failureSummary}\nlogs:\n$logLines",
            true, finish.success,
        )

        val apk = File(projectDir, "app/build/outputs/apk/debug/app-debug.apk")
        assertTrue("apk not produced at $apk", apk.exists())

        // Byte-check: first 4 bytes MUST be 'PK\x03\x04' (local file header).
        apk.inputStream().use { s ->
            val header = ByteArray(4)
            assertEquals(4, s.read(header))
            assertEquals(0x50, header[0].toInt() and 0xff)
            assertEquals(0x4B, header[1].toInt() and 0xff)
            assertEquals(0x03, header[2].toInt() and 0xff)
            assertEquals(0x04, header[3].toInt() and 0xff)
        }

        // Zip-validity + contains AndroidManifest.xml (binary-encoded by aapt2).
        ZipFile(apk).use { z ->
            assertTrue(
                "APK missing AndroidManifest.xml",
                z.getEntry("AndroidManifest.xml") != null,
            )
            assertTrue(
                "APK missing classes.dex",
                z.getEntry("classes.dex") != null,
            )
            assertTrue(
                "APK missing debug v2 signing block",
                z.getEntry("META-INF/CERT.SF") != null ||
                z.getEntry("META-INF/CERT.RSA") != null ||
                apk.length() > 10_000,
            )
        }
    }

    private fun copyAssetDir(ctx: Context, assetPath: String, destDir: File) {
        destDir.mkdirs()
        val entries = ctx.assets.list(assetPath) ?: emptyArray()
        entries.forEach { entry ->
            val childAssetPath = "$assetPath/$entry"
            val childList = ctx.assets.list(childAssetPath) ?: emptyArray()
            if (childList.isEmpty()) {
                // Leaf (AssetManager.list on a file returns empty).
                ctx.assets.open(childAssetPath).use { input ->
                    File(destDir, entry).outputStream().use { out ->
                        input.copyTo(out)
                    }
                }
            } else {
                copyAssetDir(ctx, childAssetPath, File(destDir, entry))
            }
        }
    }
}
