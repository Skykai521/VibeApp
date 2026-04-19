package com.vibe.build.gradle.diagnostic

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turn raw build-tool stderr/stdout lines into a deduped, sorted list of
 * actionable [BuildDiagnostic]s suitable for surfacing to the agent.
 *
 * Pipeline:
 *   1. Run every parser over each line; first match wins.
 *   2. De-dupe by (file, line, column, message) — the same compiler error
 *      is often echoed by both Kotlin daemon stderr AND the daemon's own
 *      summary block.
 *   3. Sort errors first, then by file path then line number — so the
 *      agent reads the most actionable items at the top.
 *   4. Truncate to [maxItems] (default 20) — preserves the FIRST per-file
 *      occurrence so a single error doesn't crowd out a real second issue.
 */
@Singleton
class BuildDiagnosticIngest @Inject constructor() {

    fun ingest(
        lines: Sequence<String>,
        projectRoot: String? = null,
        maxItems: Int = DEFAULT_MAX_ITEMS,
    ): List<BuildDiagnostic> {
        val raw = lines.mapNotNull { line -> parseAny(line) }.toList()
        val deduped = raw.distinctBy { Quad(it.file, it.line, it.column, it.message) }
        val withRelativePaths = if (projectRoot != null) {
            deduped.map { d -> d.relativizeTo(projectRoot) }
        } else {
            deduped
        }
        val errorsFirst = withRelativePaths.sortedWith(
            compareBy(
                { it.severity != BuildDiagnostic.Severity.ERROR }, // false=ERROR first
                { it.file ?: "" },
                { it.line ?: 0 },
                { it.column ?: 0 },
            ),
        )
        return errorsFirst.take(maxItems)
    }

    private fun parseAny(line: String): BuildDiagnostic? =
        parseKotlinDiagnostic(line) ?: parseAapt2Diagnostic(line)

    private fun BuildDiagnostic.relativizeTo(root: String): BuildDiagnostic {
        val file = this.file ?: return this
        val normalizedRoot = root.removeSuffix("/")
        return if (file.startsWith("$normalizedRoot/")) {
            copy(file = file.removePrefix("$normalizedRoot/"))
        } else {
            this
        }
    }

    private data class Quad(val a: String?, val b: Int?, val c: Int?, val d: String)

    companion object {
        const val DEFAULT_MAX_ITEMS = 20
    }
}
