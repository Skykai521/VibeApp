package com.vibe.build.gradle.diagnostic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildDiagnosticIngestTest {

    private val ingest = BuildDiagnosticIngest()

    @Test
    fun mixed_kotlin_and_aapt2_lines_yield_combined_diagnostics() {
        val lines = sequenceOf(
            "> Task :app:compileDebugKotlin",
            "e: file:///prj/app/src/main/kotlin/Foo.kt:10:5 Unresolved reference 'bar'",
            "    var x = bar()",
            "        ^",
            "ERROR:/prj/app/src/main/res/values/strings.xml:3:5: AAPT: error: missing closing tag",
            "noise line",
        )
        val out = ingest.ingest(lines, projectRoot = "/prj")
        assertEquals(2, out.size)
        // Errors-first; both are errors so secondary sort by file path lexicographic.
        // "app/src/main/kotlin/..." < "app/src/main/res/..." → Kotlin first.
        assertEquals(BuildDiagnostic.Source.KOTLIN, out[0].source)
        assertEquals("app/src/main/kotlin/Foo.kt", out[0].file)
        assertEquals(BuildDiagnostic.Source.AAPT2, out[1].source)
        assertEquals("app/src/main/res/values/strings.xml", out[1].file)
    }

    @Test
    fun dedupes_identical_lines_emitted_twice() {
        val lines = sequenceOf(
            "e: /a/b/Foo.kt:10:5 Unresolved reference 'x'",
            "e: /a/b/Foo.kt:10:5 Unresolved reference 'x'",  // duplicate
            "e: /a/b/Foo.kt:11:5 Type mismatch.",
        )
        val out = ingest.ingest(lines)
        assertEquals(2, out.size)
        assertEquals(10, out[0].line)
        assertEquals(11, out[1].line)
    }

    @Test
    fun errors_sort_before_warnings() {
        val lines = sequenceOf(
            "w: /a/Foo.kt:5:1 unused",
            "e: /a/Bar.kt:50:1 syntax error",
        )
        val out = ingest.ingest(lines)
        assertEquals(BuildDiagnostic.Severity.ERROR, out[0].severity)
        assertEquals("/a/Bar.kt", out[0].file)
        assertEquals(BuildDiagnostic.Severity.WARNING, out[1].severity)
    }

    @Test
    fun truncates_to_maxItems() {
        val many = (1..50).map { "e: /a/Foo.kt:$it:1 error number $it" }.asSequence()
        val out = ingest.ingest(many, maxItems = 5)
        assertEquals(5, out.size)
    }

    @Test
    fun all_unparseable_lines_yield_empty_list() {
        val lines = sequenceOf(
            "BUILD FAILED in 30s",
            "> Task :app:foo FAILED",
            "Daemon idle.",
        )
        assertTrue(ingest.ingest(lines).isEmpty())
    }
}
