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

    @Test
    fun `build produces PATH rooted at usr bin plus Android system paths`() {
        val fs = newFs()
        val env = ProcessEnvBuilder(fs).build(cwd = temp.root, extra = emptyMap())

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
        val env = ProcessEnvBuilder(fs).build(cwd = home, extra = emptyMap())

        assertEquals(home.absolutePath, env["HOME"])
    }

    @Test
    fun `build sets TMPDIR under usr tmp`() {
        val fs = newFs()
        val env = ProcessEnvBuilder(fs).build(cwd = temp.root, extra = emptyMap())

        assertEquals(File(fs.usrRoot, "tmp").absolutePath, env["TMPDIR"])
    }

    @Test
    fun `build sets JAVA_HOME and ANDROID_HOME under usr opt`() {
        val fs = newFs()
        val env = ProcessEnvBuilder(fs).build(cwd = temp.root, extra = emptyMap())

        assertEquals(File(fs.optRoot, "jdk-17.0.13").absolutePath, env["JAVA_HOME"])
        assertEquals(File(fs.optRoot, "android-sdk").absolutePath, env["ANDROID_HOME"])
    }

    @Test
    fun `extra env entries override base entries`() {
        val fs = newFs()
        val env = ProcessEnvBuilder(fs).build(
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
    fun `build sets LD_LIBRARY_PATH under usr lib`() {
        val fs = newFs()
        val env = ProcessEnvBuilder(fs).build(cwd = temp.root, extra = emptyMap())
        assertEquals(File(fs.usrRoot, "lib").absolutePath, env["LD_LIBRARY_PATH"])
    }

    @Test
    fun `build sets GRADLE_USER_HOME under filesDir gradle`() {
        val fs = newFs()
        val env = ProcessEnvBuilder(fs).build(cwd = temp.root, extra = emptyMap())
        // filesDir/.gradle — one level up from usr/
        val expected = File(temp.root, ".gradle").absolutePath
        assertEquals(expected, env["GRADLE_USER_HOME"])
    }
}
