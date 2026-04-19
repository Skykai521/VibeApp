package com.vibe.build.gradle.diagnostic

/**
 * One actionable error or warning emitted by some part of the v2 build
 * pipeline (Kotlin compiler, AAPT2, AGP plugins, Gradle daemon). Use as
 * the unit of cleanup, dedupe, and presentation to the agent.
 */
data class BuildDiagnostic(
    val severity: Severity,
    val source: Source,
    /** Project-relative path if resolvable, else absolute. Null for non-file diagnostics. */
    val file: String?,
    val line: Int? = null,
    val column: Int? = null,
    val message: String,
) {
    enum class Severity { ERROR, WARNING }

    enum class Source {
        /** Kotlin compiler output (e.g. `e: file://...:42:15 Unresolved reference 'foo'`). */
        KOTLIN,

        /** AAPT2 resource compile/link errors (e.g. `ERROR:strings.xml:5:5: ...`). */
        AAPT2,

        /** Gradle / AGP exception cause chain (no specific source location). */
        GRADLE,

        /** Catch-all for messages we couldn't parse but kept verbatim. */
        UNKNOWN,
    }
}
