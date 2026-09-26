package com.vibe.app.plugin

object DebugReportValidator {
    private const val PACKAGE_PREFIX = "com.vibe.generated.p"
    private val PROJECT_ID_PATTERN = Regex("[A-Za-z0-9_-]+")
    const val MAX_REPORT_CHARS = 64 * 1024

    /** Derives the projectId from a generated app's package name, or null if not ours. */
    fun projectIdFromPackage(callingPackage: String?): String? {
        if (callingPackage == null || !callingPackage.startsWith(PACKAGE_PREFIX)) return null
        val id = callingPackage.removePrefix(PACKAGE_PREFIX)
        return id.takeIf { it.isNotEmpty() && PROJECT_ID_PATTERN.matches(it) }
    }

    fun truncateForAppend(content: String): String =
        if (content.length <= MAX_REPORT_CHARS) content else content.take(MAX_REPORT_CHARS)
}
