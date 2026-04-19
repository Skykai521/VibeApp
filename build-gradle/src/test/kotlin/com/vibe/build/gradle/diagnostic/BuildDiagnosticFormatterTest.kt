package com.vibe.build.gradle.diagnostic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BuildDiagnosticFormatterTest {
    @get:Rule val tmp = TemporaryFolder()

    private val formatter = BuildDiagnosticFormatter()

    @Test
    fun empty_list_produces_simple_message() {
        val out = formatter.format(emptyList())
        assertTrue(out.contains("Build failed but no parsed diagnostics"))
    }

    @Test
    fun renders_header_with_error_and_warning_counts() {
        val out = formatter.format(
            listOf(
                BuildDiagnostic(
                    severity = BuildDiagnostic.Severity.ERROR,
                    source = BuildDiagnostic.Source.KOTLIN,
                    file = "Foo.kt",
                    line = 5,
                    column = 1,
                    message = "msg1",
                ),
                BuildDiagnostic(
                    severity = BuildDiagnostic.Severity.WARNING,
                    source = BuildDiagnostic.Source.KOTLIN,
                    file = "Bar.kt",
                    line = 3,
                    column = 1,
                    message = "msg2",
                ),
            ),
        )
        val firstLine = out.lineSequence().first()
        assertEquals("## Build failed: 1 error, 1 warning", firstLine)
    }

    @Test
    fun includes_file_line_column_in_each_section() {
        val out = formatter.format(
            listOf(
                BuildDiagnostic(
                    severity = BuildDiagnostic.Severity.ERROR,
                    source = BuildDiagnostic.Source.AAPT2,
                    file = "app/res/values/strings.xml",
                    line = 7,
                    column = 9,
                    message = "missing closing tag",
                ),
            ),
        )
        assertTrue(
            "section header missing file:line:col — got:\n$out",
            out.contains("`app/res/values/strings.xml:7:9`"),
        )
        assertTrue(out.contains("AAPT2"))
        assertTrue(out.contains("missing closing tag"))
    }

    @Test
    fun reads_source_snippet_from_disk_when_file_resolves() {
        val src = tmp.newFolder("src")
        val mainKt = File(src, "Main.kt").apply {
            parentFile.mkdirs()
            writeText("fun a() {}\nfun b() {}\nfun missing() = bug()\nfun c() {}\nfun d() {}\n")
        }

        val out = formatter.format(
            diagnostics = listOf(
                BuildDiagnostic(
                    severity = BuildDiagnostic.Severity.ERROR,
                    source = BuildDiagnostic.Source.KOTLIN,
                    file = "Main.kt",
                    line = 3,
                    column = 17,
                    message = "Unresolved reference 'bug'",
                ),
            ),
            projectRoot = src,
        )
        assertTrue("snippet missing line 2 — got:\n$out", out.contains("2: fun b() {}"))
        assertTrue("snippet missing line 3 — got:\n$out", out.contains("3: fun missing() = bug()"))
        assertTrue("snippet missing caret — got:\n$out", out.contains("^"))
        assertTrue("fence missing — got:\n$out", out.contains("```kotlin"))
        // Sanity: didn't accidentally use the temp file's existence
        assertTrue(mainKt.isFile)
    }

    @Test
    fun gracefully_skips_snippet_when_file_unreadable() {
        val out = formatter.format(
            listOf(
                BuildDiagnostic(
                    severity = BuildDiagnostic.Severity.ERROR,
                    source = BuildDiagnostic.Source.KOTLIN,
                    file = "/does/not/exist/Foo.kt",
                    line = 5,
                    column = 1,
                    message = "Unresolved reference 'foo'",
                ),
            ),
            projectRoot = null,
        )
        // Header + message present; no snippet fence.
        assertTrue(out.contains("Unresolved reference 'foo'"))
        assertTrue("snippet should be absent — got:\n$out", !out.contains("```kotlin"))
    }
}
