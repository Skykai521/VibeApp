package com.vibe.app.feature.uipattern

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * JVM-only smoke test. Walks every pattern, substitutes slot defaults, and
 * verifies the resulting XML is well-formed via the javax DocumentBuilder.
 *
 * This is NOT AAPT2 compilation — M2 attr resolution is verified manually
 * against a live agent-run build (see docs/ui-pattern-manual-smoke.md).
 */
class PatternXmlValidityTest {

    @Serializable
    private data class MetaJson(
        val id: String,
        val kind: String,
        val slots: List<MetaSlot> = emptyList(),
    )

    @Serializable
    private data class MetaSlot(
        val name: String,
        val description: String,
        val default: String,
    )

    @Test
    fun `every pattern is well-formed after slot substitution`() {
        val root = locatePatternsDir()
        val json = Json { ignoreUnknownKeys = true }
        val patternDirs = listOf("blocks", "screens")
            .map { File(root, it) }
            .filter { it.exists() }
            .flatMap { it.listFiles()?.filter(File::isDirectory).orEmpty() }

        val failures = mutableListOf<String>()
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }

        for (dir in patternDirs) {
            val metaFile = File(dir, "meta.json")
            val layoutFile = File(dir, "layout.xml")
            if (!metaFile.exists() || !layoutFile.exists()) continue

            val meta = json.decodeFromString(MetaJson.serializer(), metaFile.readText())
            var xml = layoutFile.readText()
            for (slot in meta.slots) {
                xml = xml.replace("{{${slot.name}}}", slot.default)
            }

            val unreplacedRegex = Regex("\\{\\{[^}]+}}")
            val leftover = unreplacedRegex.findAll(xml).map { it.value }.toList()
            if (leftover.isNotEmpty()) {
                failures += "${meta.id}: unreplaced placeholders: $leftover"
                continue
            }

            try {
                factory.newDocumentBuilder().parse(xml.byteInputStream())
            } catch (t: Throwable) {
                failures += "${meta.id}: ${t.message}"
            }
        }

        assertFalse(
            "Pattern XML validity failures:\n${failures.joinToString("\n")}",
            failures.isNotEmpty(),
        )
    }

    private fun locatePatternsDir(): File {
        val candidates = listOf(
            File("app/src/main/assets/patterns"),
            File("../app/src/main/assets/patterns"),
            File(System.getProperty("user.dir"), "app/src/main/assets/patterns"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("patterns dir not found")
    }
}
