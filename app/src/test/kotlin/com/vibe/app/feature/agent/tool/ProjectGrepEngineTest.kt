package com.vibe.app.feature.agent.tool

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProjectGrepEngineTest {

    private lateinit var root: File
    private val engine = ProjectGrepEngine()

    @Before
    fun setUp() {
        root = Files.createTempDirectory("grep-test").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun write(relative: String, content: String) {
        val file = File(root, relative)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    @Test
    fun `literal match returns matching lines with line numbers`() {
        write(
            "src/main/java/Main.java",
            "class Main {\n    int foo = 42;\n    int bar = 7;\n}\n",
        )
        val result = engine.search(root, root, GrepArgs(pattern = "foo"))
        assertEquals(1, result.matchCount)
        assertEquals(1, result.fileCount)
        assertTrue(result.matchesText, result.matchesText.contains("src/main/java/Main.java:2:"))
        assertTrue(result.matchesText, result.matchesText.contains("foo = 42"))
    }

    @Test
    fun `case insensitive matches regardless of case`() {
        write("a.txt", "Hello\nWORLD\n")
        val result = engine.search(root, root, GrepArgs(pattern = "hello", caseInsensitive = true))
        assertEquals(1, result.matchCount)
    }

    @Test
    fun `case sensitive does not match different case`() {
        write("a.txt", "Hello\nWORLD\n")
        val result = engine.search(root, root, GrepArgs(pattern = "hello"))
        assertEquals(0, result.matchCount)
    }

    @Test
    fun `regex mode matches pattern`() {
        write("a.txt", "id123\nfoo\nid456\n")
        val result = engine.search(root, root, GrepArgs(pattern = "id\\d+", regex = true))
        assertEquals(2, result.matchCount)
    }

    @Test
    fun `glob filters to java files only`() {
        write("a.java", "foo marker\n")
        write("b.xml", "foo marker\n")
        val result = engine.search(root, root, GrepArgs(pattern = "foo", glob = "*.java"))
        assertEquals(1, result.matchCount)
        assertTrue(result.matchesText, result.matchesText.contains("a.java"))
    }

    @Test
    fun `glob recursive matches any depth`() {
        write("res/values/strings.xml", "foo\n")
        write("res/values-en/strings.xml", "foo\n")
        write("res/layout/activity.xml", "foo\n")
        val result = engine.search(root, root, GrepArgs(pattern = "foo", glob = "**/strings.xml"))
        assertEquals(2, result.matchCount)
    }

    @Test
    fun `build directory is excluded`() {
        write("src/a.txt", "foo\n")
        write("build/b.txt", "foo\n")
        val result = engine.search(root, root, GrepArgs(pattern = "foo"))
        assertEquals(1, result.matchCount)
        assertFalse(result.matchesText, result.matchesText.contains("build/"))
    }

    @Test
    fun `binary extension is skipped`() {
        write("a.txt", "foo\n")
        write("b.png", "foo\n")
        val result = engine.search(root, root, GrepArgs(pattern = "foo"))
        assertEquals(1, result.matchCount)
    }

    @Test
    fun `max results truncates and sets flag`() {
        val lines = (1..100).joinToString("\n") { "foo line $it" }
        write("a.txt", lines)
        val result = engine.search(root, root, GrepArgs(pattern = "foo", maxResults = 10))
        assertEquals(10, result.matchCount)
        assertTrue(result.truncated)
    }

    @Test
    fun `context lines include lines before and after match`() {
        write("a.txt", "L1\nL2\nmatch here\nL4\nL5\n")
        val result = engine.search(root, root, GrepArgs(pattern = "match", contextLines = 1))
        val text = result.matchesText
        assertTrue(text, text.contains("a.txt:2-L2"))
        assertTrue(text, text.contains("a.txt:3:match here"))
        assertTrue(text, text.contains("a.txt:4-L4"))
    }

    @Test
    fun `files with matches mode returns distinct paths`() {
        write("a.txt", "foo\n")
        write("b.txt", "foo\nfoo\n")
        write("c.txt", "bar\n")
        val result = engine.search(
            root, root,
            GrepArgs(pattern = "foo", outputMode = GrepOutputMode.FILES_WITH_MATCHES),
        )
        assertEquals(2, result.fileCount)
        assertEquals(2, result.files.size)
        assertTrue(result.files.contains("a.txt"))
        assertTrue(result.files.contains("b.txt"))
    }

    @Test
    fun `count mode returns per-file counts`() {
        write("a.txt", "foo\nbar\nfoo\n")
        write("b.txt", "foo\n")
        val result = engine.search(
            root, root,
            GrepArgs(pattern = "foo", outputMode = GrepOutputMode.COUNT),
        )
        val text = result.matchesText
        assertTrue(text, text.contains("a.txt:2"))
        assertTrue(text, text.contains("b.txt:1"))
        assertEquals(2, result.fileCount)
    }

    @Test
    fun `search path limits scope to subdirectory`() {
        write("app/src/Main.java", "foo\n")
        write("other/stuff.java", "foo\n")
        val searchRoot = File(root, "app")
        val result = engine.search(searchRoot, root, GrepArgs(pattern = "foo"))
        assertEquals(1, result.matchCount)
        assertTrue(result.matchesText, result.matchesText.contains("app/src/Main.java"))
    }

    @Test
    fun `long matched line is truncated`() {
        val longLine = "x".repeat(800) + "foo" + "y".repeat(200)
        write("a.txt", longLine)
        val result = engine.search(root, root, GrepArgs(pattern = "foo"))
        assertEquals(1, result.matchCount)
        // Line should be truncated to ~500 chars + marker
        val lines = result.matchesText.lines().filter { it.contains("foo") }
        assertTrue(lines.isNotEmpty())
        assertTrue(lines.first().length < 700)
        assertTrue(lines.first().contains("…"))
    }
}
