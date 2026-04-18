package com.vibe.build.runtime.process

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vibe.build.runtime.bootstrap.BootstrapFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumented acceptance tests for [NativeProcessLauncher].
 *
 * These tests exercise the full native stack:
 * Kotlin NativeProcessLauncher → NativeProcessBridge (JNI) → libbuildruntime.so →
 * fork/execve → /system/bin/toybox
 *
 * All tests use [runBlocking] rather than [kotlinx.coroutines.test.runTest] because
 * they interact with real OS processes. [withTimeout] requires real wall-clock time;
 * the virtual scheduler used by runTest would expire timeouts immediately.
 */
@RunWith(AndroidJUnit4::class)
class NativeProcessLauncherInstrumentedTest {

    private lateinit var scratchDir: File
    private lateinit var launcher: NativeProcessLauncher

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        scratchDir = File(ctx.cacheDir, "nativeprocess-test-${System.nanoTime()}")
        require(scratchDir.mkdirs())

        val fs = BootstrapFileSystem(filesDir = scratchDir)
        fs.ensureDirectories()

        val preloadLib = PreloadLibLocator(
            nativeLibraryDir = java.io.File(ctx.applicationInfo.nativeLibraryDir),
        )
        launcher = NativeProcessLauncher(ProcessEnvBuilder(fs, preloadLib))
    }

    @After
    fun tearDown() {
        scratchDir.deleteRecursively()
    }

    private val toyboxPath: String = "/system/bin/toybox"

    /** launch toybox echo produces stdout and exit 0 */
    @Test
    fun launch_toybox_echo_produces_stdout_and_exit_0() = runBlocking {
        val process = launcher.launch(
            executable = toyboxPath,
            args = listOf("echo", "hello", "world"),
            cwd = scratchDir,
        )

        val events = withTimeout(10_000) { process.events.toList() }

        val stdoutBytes = events.filterIsInstance<ProcessEvent.Stdout>()
            .fold(ByteArray(0)) { acc, e -> acc + e.bytes }
        val exit = events.filterIsInstance<ProcessEvent.Exited>().first()

        assertEquals(0, exit.code)
        assertEquals("hello world\n", String(stdoutBytes, Charsets.UTF_8))
    }

    /**
     * launch toybox ls on the scratch dir produces nontrivial stdout and exit 0.
     *
     * Lists the app-private scratchDir (which setUp populates with usr/opt/ and
     * usr/tmp/ via ensureDirectories()). The app always has full access to its own
     * cache directory, so this avoids SELinux restrictions that block app-uid
     * access to system paths like / or /system/bin/ on API 31+ emulators.
     */
    @Test
    fun launch_toybox_ls_slash_produces_nontrivial_stdout_and_exit_0() = runBlocking {
        val process = launcher.launch(
            executable = toyboxPath,
            args = listOf("ls", scratchDir.absolutePath),
            cwd = scratchDir,
        )

        val events = withTimeout(10_000) { process.events.toList() }

        val stdout = events.filterIsInstance<ProcessEvent.Stdout>()
            .fold(ByteArray(0)) { acc, e -> acc + e.bytes }
        val exit = events.filterIsInstance<ProcessEvent.Exited>().first()

        assertEquals(0, exit.code)
        // setUp calls fs.ensureDirectories() which creates usr/ under scratchDir.
        assertTrue(
            "ls scratchDir should list 'usr': ${String(stdout, Charsets.UTF_8)}",
            String(stdout, Charsets.UTF_8).contains("usr"),
        )
    }

    /** launch with nonexistent executable exits with code 127 */
    @Test
    fun launch_with_nonexistent_executable_exits_with_code_127() = runBlocking {
        // The launcher returns pid > 0 (fork succeeds) but the child's
        // execve fails inside the child and _exits(127).
        val process = launcher.launch(
            executable = "/system/bin/toybox-does-not-exist-${System.nanoTime()}",
            args = listOf("ls"),
            cwd = scratchDir,
        )

        val events = withTimeout(5_000) { process.events.toList() }
        val exit = events.filterIsInstance<ProcessEvent.Exited>().first()
        assertEquals(127, exit.code)
    }

    /**
     * signal SIGTERM to a long-running process produces signaled exit.
     *
     * Uses runBlocking so that withTimeout operates on real wall-clock time.
     * The native sleep(30) call is real OS time.
     */
    @Test
    fun signal_SIGTERM_to_a_long_running_process_produces_signaled_exit() = runBlocking {
        // Launch toybox sleep 30; signal SIGTERM; expect exit 128+15 = 143.
        val process = launcher.launch(
            executable = toyboxPath,
            args = listOf("sleep", "30"),
            cwd = scratchDir,
        )

        // Give the child a moment to actually start waiting.
        withContext(Dispatchers.IO) { Thread.sleep(300) }

        val rc = process.signal(SIGTERM)
        assertEquals("signal() returned errno=$rc", 0, rc)

        val exit = withTimeout(5_000) {
            process.events.first { it is ProcessEvent.Exited } as ProcessEvent.Exited
        }
        // Processes terminated by SIGTERM return 128+15 = 143.
        assertEquals(143, exit.code)
    }
}
