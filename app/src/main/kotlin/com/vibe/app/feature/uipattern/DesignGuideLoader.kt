package com.vibe.app.feature.uipattern

import java.io.InputStream

/**
 * Loads assets/design/design-guide.md and serves it whole or sliced by H2
 * section. The full text is cached on first read.
 */
class DesignGuideLoader(
    private val streamProvider: StreamProvider,
) {
    fun interface StreamProvider {
        fun open(): InputStream
    }

    private val content: String by lazy {
        streamProvider.open().use { it.readBytes().decodeToString() }
    }

    fun load(section: String): String {
        val key = section.trim().lowercase()
        if (key == "all") return content
        val sections = splitByH2(content)
        val match = sections.entries.firstOrNull { it.key.lowercase().startsWith(key) }
            ?: throw IllegalArgumentException(
                "Unknown section '$section'. Valid: ${sections.keys.joinToString()} or 'all'.",
            )
        return "## ${match.key}\n\n${match.value}".trimEnd()
    }

    private fun splitByH2(text: String): LinkedHashMap<String, String> {
        val result = LinkedHashMap<String, String>()
        var currentTitle: String? = null
        val currentBody = StringBuilder()
        for (line in text.lines()) {
            if (line.startsWith("## ")) {
                if (currentTitle != null) {
                    result[currentTitle] = currentBody.toString().trimEnd()
                }
                currentTitle = line.removePrefix("## ").trim()
                currentBody.clear()
            } else if (currentTitle != null) {
                currentBody.appendLine(line)
            }
        }
        if (currentTitle != null) {
            result[currentTitle] = currentBody.toString().trimEnd()
        }
        return result
    }
}
