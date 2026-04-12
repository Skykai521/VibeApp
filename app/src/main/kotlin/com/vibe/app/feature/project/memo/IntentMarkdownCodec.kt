package com.vibe.app.feature.project.memo

/**
 * Converts between [Intent] and a fixed markdown layout. The encoding enforces
 * the length and count limits from [Intent] so written memos always stay bounded,
 * regardless of what the LLM produces. Decoding is lenient — unknown lines are
 * ignored and missing sections default to empty.
 */
object IntentMarkdownCodec {

    private const val HEADER = "<!-- Maintained by AI, edited by you -->"

    fun encode(intent: Intent, appName: String): String = buildString {
        appendLine(HEADER)
        appendLine("# $appName")
        appendLine()
        appendLine("**Purpose**: ${intent.purpose.take(Intent.PURPOSE_MAX)}")
        if (intent.keyDecisions.isNotEmpty()) {
            appendLine()
            appendLine("**Key Decisions**:")
            intent.keyDecisions.take(Intent.LIST_MAX).forEach {
                appendLine("- ${it.take(Intent.LINE_MAX)}")
            }
        }
        if (intent.knownLimits.isNotEmpty()) {
            appendLine()
            appendLine("**Known Limits**:")
            intent.knownLimits.take(Intent.LIST_MAX).forEach {
                appendLine("- ${it.take(Intent.LINE_MAX)}")
            }
        }
    }

    fun decode(markdown: String): Intent {
        var purpose = ""
        val keyDecisions = mutableListOf<String>()
        val knownLimits = mutableListOf<String>()
        var section: Section = Section.NONE

        markdown.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() || line.startsWith("<!--") || line.startsWith("#") -> {
                    // skip header / blank / h1
                }
                line.startsWith("**Purpose**:") -> {
                    purpose = line.removePrefix("**Purpose**:").trim()
                    section = Section.NONE
                }
                line == "**Key Decisions**:" -> section = Section.KEY_DECISIONS
                line == "**Known Limits**:" -> section = Section.KNOWN_LIMITS
                line.startsWith("- ") -> {
                    val item = line.removePrefix("- ").trim()
                    when (section) {
                        Section.KEY_DECISIONS -> keyDecisions += item
                        Section.KNOWN_LIMITS -> knownLimits += item
                        Section.NONE -> {} // stray bullet, ignore
                    }
                }
            }
        }

        return Intent(purpose = purpose, keyDecisions = keyDecisions, knownLimits = knownLimits)
    }

    private enum class Section { NONE, KEY_DECISIONS, KNOWN_LIMITS }
}
