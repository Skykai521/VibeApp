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
 * Tests for the `libtermux-exec.so` LD_PRELOAD plumbing.
 *
 * Scope note:
 *   Full end-to-end validation of shebang rewriting (running a script
 *   with `#!/usr/bin/env sh` and observing the override rewrite it
 *   to `$PREFIX/bin/sh`) is NOT possible from inside the stock
 *   Android API 29+ app sandbox: SELinux's `app_data_file` domain
 *   denies `execute_no_trans` on any path resolving into `filesDir` /
 *   `cacheDir`, whether the target is a real script or a symlink
 *   into `/system/bin/`. That restriction applies to ANY exec — the
 *   libtermux-exec.so rewrite doesn't bypass it; kernel SELinux runs
 *   before the override even loads.
 *
 *   The code path WILL work once Phase 2 introduces `proot` (or an
 *   equivalent chroot/pivot_root layer), which is required
 *   independently for running `java` / `gradle` binaries out of the
 *   downloaded JDK. A full shebang acceptance test will land there.
 *
 *   What this test does validate:
 *     - LD_PRELOAD is wired into the child environment (via ProcessEnvBuilder).
 *     - VIBEAPP_USR_PREFIX is wired into the child environment.
 *     - libtermux-exec.so is transparent for direct binary execs
 *       (proven in isolation here; also covered by every other
 *       Phase 1a/1b/1c instrumented test).
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
        // binary execs. This is the regression-guard for Phase 1d against
        // the dozens of places in the codebase that exec direct binaries.
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
        // two key env vars. Proves ProcessEnvBuilder is wiring them
        // into child processes end-to-end.
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
