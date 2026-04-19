package com.vibe.build.runtime.process

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vibe.build.runtime.bootstrap.BootstrapFileSystem
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device tests for the `libtermux-exec.so` LD_PRELOAD plumbing.
 *
 * Scope note:
 *   `libtermux-exec.so`'s `execve()` override only intercepts execs
 *   made by DESCENDANTS of a process that loads it via LD_PRELOAD at
 *   startup — e.g. the Gradle daemon's own children (worker JVMs,
 *   kotlinc, R8, etc.) inherit the preload through the daemon's
 *   envp. Our own `process_launcher.c` links against libc directly
 *   and can't be intercepted by `System.loadLibrary`-loaded overrides
 *   (Android uses `RTLD_LOCAL` for JNI libs, preventing symbol
 *   interposition into libbuildruntime.so's namespace).
 *
 *   In practice this is fine: Phase 2's call pattern is
 *   `ProcessLauncher.launch("/data/.../usr/opt/jdk/bin/java", ...)`,
 *   which execs a REAL BINARY (no shebang involved), and `java`'s
 *   descendants benefit from `LD_PRELOAD` normally. We never need to
 *   `exec` a script at the first level.
 *
 *   What this test class validates:
 *     - LD_PRELOAD is wired into the child environment.
 *     - VIBEAPP_USR_PREFIX is wired into the child environment.
 *     - libtermux-exec.so is transparent for direct binary execs
 *       (regression guard for every other place that execs a real binary).
 *
 *   Shebang-rewrite validation for descendant processes will land in
 *   Phase 2's Gradle tests, where we have a real parent process
 *   (the gradle daemon JVM) exec'ing script children.
 */
@RunWith(AndroidJUnit4::class)
class ShebangInstrumentedTest {

    private lateinit var ctx: Context
    private lateinit var fs: BootstrapFileSystem
    private lateinit var scratchDir: File
    private lateinit var launcher: NativeProcessLauncher

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        scratchDir = File(ctx.cacheDir, "shebang-test-${System.nanoTime()}")
        require(scratchDir.mkdirs())

        fs = BootstrapFileSystem(filesDir = ctx.filesDir)
        fs.ensureDirectories()

        val preloadLib = PreloadLibLocator(
            File(ctx.applicationInfo.nativeLibraryDir),
        )
        val envBuilder = ProcessEnvBuilder(fs, preloadLib)
        launcher = NativeProcessLauncher(envBuilder)
    }

    @After
    fun tearDown() {
        scratchDir.deleteRecursively()
    }

    @Test
    fun direct_binary_exec_unaffected_by_preload() = runBlocking {
        // Proves LD_PRELOAD=libtermux-exec.so is transparent for non-script
        // binary execs. Regression guard for every other place in the
        // codebase that execs /system/bin/* directly.
        val process = launcher.launch(
            executable = "/system/bin/toybox",
            args = listOf("echo", "direct-ok"),
            cwd = scratchDir,
        )
        val events = withTimeout(10_000) { process.events.toList() }

        val stdout = events.filterIsInstance<ProcessEvent.Stdout>()
            .fold(ByteArray(0)) { acc, e -> acc + e.bytes }
        val exit = events.filterIsInstance<ProcessEvent.Exited>().first()

        assertEquals(0, exit.code)
        assertEquals("direct-ok\n", String(stdout, Charsets.UTF_8))
    }

    @Test
    fun toybox_env_shows_LD_PRELOAD_and_VIBEAPP_USR_PREFIX() = runBlocking {
        // Launches /system/bin/toybox env and inspects stdout for our
        // two key env vars. Proves ProcessEnvBuilder wires them end-to-end.
        val process = launcher.launch(
            executable = "/system/bin/toybox",
            args = listOf("env"),
            cwd = scratchDir,
        )
        val events = withTimeout(10_000) { process.events.toList() }

        val stdout = events.filterIsInstance<ProcessEvent.Stdout>()
            .fold(ByteArray(0)) { acc, e -> acc + e.bytes }
        val exit = events.filterIsInstance<ProcessEvent.Exited>().first()
        val envOutput = String(stdout, Charsets.UTF_8)

        assertEquals("env exit code was $exit; stdout=$envOutput", 0, exit.code)
        assertTrue(
            "LD_PRELOAD line missing from child env: $envOutput",
            envOutput.lineSequence().any { it.startsWith("LD_PRELOAD=") && it.endsWith("libtermux-exec.so") },
        )
        assertTrue(
            "VIBEAPP_USR_PREFIX line missing from child env: $envOutput",
            envOutput.lineSequence().any { it.startsWith("VIBEAPP_USR_PREFIX=") && it.endsWith("/usr") },
        )
    }
}
