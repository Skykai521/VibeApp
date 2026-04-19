package com.vibe.build.gradle.diagnostic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiagnosticParsersTest {

    // ── Kotlin ─────────────────────────────────────────────────────

    @Test
    fun kotlin_severity_first_with_file_uri() {
        val d = parseKotlinDiagnostic(
            "e: file:///tmp/projects/p1/app/src/main/kotlin/com/x/MainActivity.kt:34:15 Unresolved reference 'foo'"
        )!!
        assertEquals(BuildDiagnostic.Severity.ERROR, d.severity)
        assertEquals(BuildDiagnostic.Source.KOTLIN, d.source)
        assertEquals("/tmp/projects/p1/app/src/main/kotlin/com/x/MainActivity.kt", d.file)
        assertEquals(34, d.line)
        assertEquals(15, d.column)
        assertEquals("Unresolved reference 'foo'", d.message)
    }

    @Test
    fun kotlin_severity_first_warning_bare_path() {
        val d = parseKotlinDiagnostic(
            "w: /tmp/Foo.kt:42:5 Variable 'x' is never used"
        )!!
        assertEquals(BuildDiagnostic.Severity.WARNING, d.severity)
        assertEquals("/tmp/Foo.kt", d.file)
        assertEquals(42, d.line)
        assertEquals(5, d.column)
        assertEquals("Variable 'x' is never used", d.message)
    }

    @Test
    fun kotlin_path_first_kotlin_2_x() {
        val d = parseKotlinDiagnostic(
            "file:///tmp/Bar.kt:12:8 e: Type mismatch."
        )!!
        assertEquals(BuildDiagnostic.Severity.ERROR, d.severity)
        assertEquals("/tmp/Bar.kt", d.file)
        assertEquals(12, d.line)
        assertEquals(8, d.column)
        assertEquals("Type mismatch.", d.message)
    }

    @Test
    fun kotlin_returns_null_for_continuation_and_decorations() {
        assertNull(parseKotlinDiagnostic("    var x = foo()"))
        assertNull(parseKotlinDiagnostic("        ^"))
        assertNull(parseKotlinDiagnostic(""))
        assertNull(parseKotlinDiagnostic("> Task :app:compileDebugKotlin"))
    }

    // ── AAPT2 ──────────────────────────────────────────────────────

    @Test
    fun aapt2_full_form() {
        val d = parseAapt2Diagnostic(
            "ERROR:/tmp/projects/p1/app/src/main/res/values/strings.xml:5:5: AAPT: error: resource string/foo not found."
        )!!
        assertEquals(BuildDiagnostic.Severity.ERROR, d.severity)
        assertEquals(BuildDiagnostic.Source.AAPT2, d.source)
        assertEquals("/tmp/projects/p1/app/src/main/res/values/strings.xml", d.file)
        assertEquals(5, d.line)
        assertEquals(5, d.column)
        assertEquals("resource string/foo not found.", d.message)
    }

    @Test
    fun aapt2_no_error_prefix_no_aapt_prefix() {
        val d = parseAapt2Diagnostic(
            "/tmp/foo.xml:10:3: error: missing attribute"
        )!!
        assertEquals("/tmp/foo.xml", d.file)
        assertEquals(10, d.line)
        assertEquals(3, d.column)
        assertEquals("missing attribute", d.message)
    }

    @Test
    fun aapt2_without_line_or_column() {
        val d = parseAapt2Diagnostic(
            "/tmp/foo.xml: error: file failed to compile."
        )!!
        assertEquals("/tmp/foo.xml", d.file)
        assertNull(d.line)
        assertNull(d.column)
        assertEquals("file failed to compile.", d.message)
    }

    @Test
    fun aapt2_returns_null_for_unrelated_lines() {
        assertNull(parseAapt2Diagnostic("    > Task :app:processDebugResources"))
        assertNull(parseAapt2Diagnostic(""))
        assertNull(parseAapt2Diagnostic("AAPT2 daemon started."))
    }
}
