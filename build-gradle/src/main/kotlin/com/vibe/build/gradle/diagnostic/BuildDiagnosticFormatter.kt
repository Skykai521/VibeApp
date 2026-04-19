package com.vibe.build.gradle.diagnostic

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Render a list of [BuildDiagnostic] as agent-friendly markdown:
 *
 *   ```
 *   ## Build failed: 2 errors, 1 warning
 *
 *   ### ERROR `app/src/main/kotlin/Foo.kt:34:15` — KOTLIN
 *   Unresolved reference 'foo'
 *   ```kotlin
 *   33: fun main() {
 *   34:     foo()
 *                ^
 *   35: }
 *   ```
 *   ```
 *
 * Source snippets (3 lines of context) are read from disk if [projectRoot]
 * is provided AND the file path resolves under it. Snippets are skipped
 * silently if the file isn't readable — formatting still works on lines
 * alone.
 */
@Singleton
class BuildDiagnosticFormatter @Inject constructor() {

    fun format(
        diagnostics: List<BuildDiagnostic>,
        projectRoot: File? = null,
    ): String {
        if (diagnostics.isEmpty()) {
            return "Build failed but no parsed diagnostics — see raw build output."
        }
        val errors = diagnostics.count { it.severity == BuildDiagnostic.Severity.ERROR }
        val warnings = diagnostics.count { it.severity == BuildDiagnostic.Severity.WARNING }
        return buildString {
            append("## Build failed: ").append(errors).append(" error")
            if (errors != 1) append('s')
            if (warnings > 0) {
                append(", ").append(warnings).append(" warning")
                if (warnings != 1) append('s')
            }
            append("\n\n")
            diagnostics.forEach { d ->
                appendDiagnostic(this, d, projectRoot)
            }
        }
    }

    private fun appendDiagnostic(
        out: StringBuilder,
        d: BuildDiagnostic,
        projectRoot: File?,
    ) {
        out.append("### ").append(d.severity.name).append(" `")
        out.append(d.file ?: "<unknown>")
        d.line?.let { out.append(":").append(it) }
        d.column?.let { out.append(":").append(it) }
        out.append("` — ").append(d.source.name).append('\n')
        out.append(d.message).append("\n")

        // Source snippet (3 lines of context centered on d.line).
        val snippet = readSnippet(d, projectRoot)
        if (snippet != null) {
            out.append("```").append(d.source.name.lowercase().take(6)).append('\n')
            out.append(snippet)
            out.append("```\n")
        }
        out.append('\n')
    }

    private fun readSnippet(d: BuildDiagnostic, projectRoot: File?): String? {
        val filePath = d.file ?: return null
        val line = d.line ?: return null
        val resolved = if (File(filePath).isAbsolute) {
            File(filePath)
        } else {
            projectRoot?.let { File(it, filePath) } ?: return null
        }
        if (!resolved.isFile || !resolved.canRead()) return null
        val allLines = try {
            resolved.readLines()
        } catch (_: Throwable) {
            return null
        }
        val start = (line - 2).coerceAtLeast(1)
        val end = (line + 1).coerceAtMost(allLines.size)
        if (start > allLines.size) return null
        val width = end.toString().length
        return buildString {
            for (i in start..end) {
                val text = allLines.getOrNull(i - 1) ?: break
                append(i.toString().padStart(width)).append(": ").append(text).append('\n')
                if (i == line) {
                    val col = d.column
                    if (col != null && col >= 1) {
                        append(" ".repeat(width + 2)).append(" ".repeat(col - 1)).append("^\n")
                    }
                }
            }
        }
    }
}
