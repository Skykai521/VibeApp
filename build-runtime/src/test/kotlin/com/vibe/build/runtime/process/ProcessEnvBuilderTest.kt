package com.vibe.build.runtime.process

import com.vibe.build.runtime.bootstrap.BootstrapFileSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProcessEnvBuilderTest {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    private fun newFs(): BootstrapFileSystem = BootstrapFileSystem(filesDir = temp.root)

    private fun fakePreload(libPath: String = "/fake/native-lib-dir/libtermux-exec.so"): PreloadLibLocator =
        object : PreloadLibLocator(File("/fake/native-lib-dir")) {
            override fun termuxExecLibPath(): String = libPath
        }

    @Test
    fun `build produces PATH rooted at usr bin plus Android system paths`() {
        val fs = newFs()
        val env = ProcessEnvBuilder(fs, fakePreload()).build(cwd = temp.root, extra = emptyMap())

        val path = env["PATH"]!!
        assertTrue("PATH should start with usr/bin: $path",
            path.startsWith(File(fs.usrRoot, "bin").absolutePath))
        assertTrue("PATH should include /system/bin: $path", path.contains("/system/bin"))
    }

    @Test
    fun `build sets HOME to cwd`() {
        val fs = newFs()
        val home = File(temp.root, "projects/p1")
        home.mkdirs()
        val env = ProcessEnvBuilder(fs, fakePreload()).build(cwd = home, extra = emptyMap())

        assertEquals(home.absolutePath, env["HOME"])
    }

    @Test
    fun `build sets TMPDIR under usr tmp`() {
        val fs = newFs()
        val env = ProcessEnvBuilder(fs, fakePreload()).build(cwd = temp.root, extra = emptyMap())

        assertEquals(File(fs.usrRoot, "tmp").absolutePath, env["TMPDIR"])
    }

    @Test
    fun `build sets JAVA_HOME and ANDROID_HOME under usr opt`() {
        val fs = newFs()
        val env = ProcessEnvBuilder(fs, fakePreload()).build(cwd = temp.root, extra = emptyMap())

        assertEquals(File(fs.optRoot, "jdk-17.0.13").absolutePath, env["JAVA_HOME"])
        assertEquals(File(fs.optRoot, "android-sdk-36.0.0").absolutePath, env["ANDROID_HOME"])
    }

    @Test
    fun `extra env entries override base entries`() {
        val fs = newFs()
        val env = ProcessEnvBuilder(fs, fakePreload()).build(
            cwd = temp.root,
            extra = mapOf(
                "PATH" to "/custom/path",
                "MY_VAR" to "hello",
            ),
        )

        assertEquals("/custom/path", env["PATH"])
        assertEquals("hello", env["MY_VAR"])
        // Unrelated base entries preserved
        assertEquals(File(fs.usrRoot, "tmp").absolutePath, env["TMPDIR"])
    }

    @Test
    fun `build sets LD_LIBRARY_PATH to JDK lib dirs then usr lib`() {
        val fs = newFs()
        val env = ProcessEnvBuilder(fs, fakePreload()).build(cwd = temp.root, extra = emptyMap())
        val javaHome = File(fs.optRoot, "jdk-17.0.13")
        val expected = listOf(
            File(javaHome, "lib/server").absolutePath,
            File(javaHome, "lib").absolutePath,
            File(fs.usrRoot, "lib").absolutePath,
        ).joinToString(separator = ":")
        assertEquals(expected, env["LD_LIBRARY_PATH"])
    }

    @Test
    fun `build sets GRADLE_USER_HOME under filesDir gradle`() {
        val fs = newFs()
        val env = ProcessEnvBuilder(fs, fakePreload()).build(cwd = temp.root, extra = emptyMap())
        // filesDir/.gradle — one level up from usr/
        val expected = File(temp.root, ".gradle").absolutePath
        assertEquals(expected, env["GRADLE_USER_HOME"])
    }

    @Test
    fun `build sets LD_PRELOAD to the preload lib path`() {
        val fs = newFs()
        val env = ProcessEnvBuilder(fs, fakePreload("/custom/libtermux-exec.so"))
            .build(cwd = temp.root, extra = emptyMap())
        assertEquals("/custom/libtermux-exec.so", env["LD_PRELOAD"])
    }

    @Test
    fun `extra map can override LD_PRELOAD`() {
        val fs = newFs()
        val env = ProcessEnvBuilder(fs, fakePreload("/base/libtermux-exec.so")).build(
            cwd = temp.root,
            extra = mapOf("LD_PRELOAD" to ""),   // consumer disables preload
        )
        assertEquals("", env["LD_PRELOAD"])
    }

    @Test
    fun `build sets VIBEAPP_USR_PREFIX to usr root for libtermux-exec`() {
        val fs = newFs()
        val env = ProcessEnvBuilder(fs, fakePreload()).build(cwd = temp.root, extra = emptyMap())
        assertEquals(fs.usrRoot.absolutePath, env["VIBEAPP_USR_PREFIX"])
    }

    @Test
    fun ANDROID_HOME_points_to_android_sdk_36_0_0() {
        val fs = newFs()
        val env = ProcessEnvBuilder(fs, fakePreload()).build(cwd = temp.root, extra = emptyMap())
        val expected = File(fs.optRoot, "android-sdk-36.0.0").absolutePath
        assertEquals(expected, env["ANDROID_HOME"])
    }
}
