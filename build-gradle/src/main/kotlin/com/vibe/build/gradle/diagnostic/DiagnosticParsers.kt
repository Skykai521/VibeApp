package com.vibe.build.gradle.diagnostic

/**
 * Parsers that turn raw build-tool output lines into [BuildDiagnostic].
 * Each parser handles one tool's format and ignores noise.
 */

/**
 * Parse a single Kotlin compiler line into a [BuildDiagnostic].
 *
 * Accepted shapes (Kotlin 1.x and 2.x both observed):
 *
 *   `e: file:///abs/path/Foo.kt:34:15 Unresolved reference 'foo'`
 *   `w: file:///abs/path/Foo.kt:34:15 Variable 'x' is never used`
 *   `e: /abs/path/Foo.kt:34:15 ...`              ← bare path, no file://
 *   `file:///abs/path/Foo.kt:34:15 e: ...`       ← Kotlin 2.x reorder
 *
 * Returns null on lines that don't match any of these shapes (continuation
 * snippets like `   var x = ...`, decorative `^` carets, blank lines).
 */
fun parseKotlinDiagnostic(line: String): BuildDiagnostic? {
    val trimmed = line.trimStart('[', ']', ' ').trimStart()
    // Strip optional Gradle log-level prefix if it slipped through.
    val cleaned = trimmed.removePrefix("KOTLIN ").removePrefix("ERROR ")

    // Variant A: severity-first  (`e: file:///foo.kt:34:15 message`)
    REGEX_SEVERITY_FIRST.matchEntire(cleaned)?.let { m ->
        val (severityStr, path, lineStr, colStr, message) = m.destructured
        return BuildDiagnostic(
            severity = if (severityStr == "e") BuildDiagnostic.Severity.ERROR else BuildDiagnostic.Severity.WARNING,
            source = BuildDiagnostic.Source.KOTLIN,
            file = path.removePrefix("file://"),
            line = lineStr.toIntOrNull(),
            column = colStr.toIntOrNull(),
            message = message.trim(),
        )
    }

    // Variant B: path-first  (`file:///foo.kt:34:15 e: message`)
    REGEX_PATH_FIRST.matchEntire(cleaned)?.let { m ->
        val (path, lineStr, colStr, severityStr, message) = m.destructured
        return BuildDiagnostic(
            severity = if (severityStr == "e") BuildDiagnostic.Severity.ERROR else BuildDiagnostic.Severity.WARNING,
            source = BuildDiagnostic.Source.KOTLIN,
            file = path.removePrefix("file://"),
            line = lineStr.toIntOrNull(),
            column = colStr.toIntOrNull(),
            message = message.trim(),
        )
    }
    return null
}

/**
 * Parse a single AAPT2 line into a [BuildDiagnostic].
 *
 * Accepted shapes:
 *   `ERROR:/path/to/strings.xml:5:5: AAPT: error: resource string/foo not found.`
 *   `/path/to/strings.xml:5:5: error: resource string/foo not found.`
 *   `/path/to/strings.xml: error: <something without line>`
 */
fun parseAapt2Diagnostic(line: String): BuildDiagnostic? {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return null

    REGEX_AAPT2_WITH_LINE.matchEntire(trimmed)?.let { m ->
        val (path, lineStr, colStr, message) = m.destructured
        return BuildDiagnostic(
            severity = BuildDiagnostic.Severity.ERROR,
            source = BuildDiagnostic.Source.AAPT2,
            file = path,
            line = lineStr.toIntOrNull(),
            column = colStr.toIntOrNull(),
            message = message.trim(),
        )
    }
    REGEX_AAPT2_NO_LINE.matchEntire(trimmed)?.let { m ->
        val (path, message) = m.destructured
        return BuildDiagnostic(
            severity = BuildDiagnostic.Severity.ERROR,
            source = BuildDiagnostic.Source.AAPT2,
            file = path,
            line = null,
            column = null,
            message = message.trim(),
        )
    }
    return null
}

private val REGEX_SEVERITY_FIRST = Regex(
    "^(e|w):\\s+(.+\\.[a-zA-Z]+):(\\d+):(\\d+)\\s+(.+)$"
)

private val REGEX_PATH_FIRST = Regex(
    "^(file://[^\\s:]+\\.[a-zA-Z]+):(\\d+):(\\d+)\\s+(e|w):\\s*(.+)$"
)

private val REGEX_AAPT2_WITH_LINE = Regex(
    "^(?:ERROR:)?(/[^:]+\\.[a-zA-Z]+):(\\d+):(\\d+):\\s*(?:AAPT:\\s*)?(?:error|warning):\\s*(.+)$"
)

private val REGEX_AAPT2_NO_LINE = Regex(
    "^(?:ERROR:)?(/[^:]+\\.[a-zA-Z]+):\\s*(?:AAPT:\\s*)?(?:error|warning):\\s*(.+)$"
)
