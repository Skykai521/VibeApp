package com.vibe.app.feature.uipattern

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regenerates app/src/main/assets/patterns/index.json by scanning the
 * blocks/ and screens/ subdirectories. Also validates that every meta.json
 * has required fields and that layout.xml / notes.md exist.
 *
 * Running this test is the only supported way to update the index — treat
 * it as a build script tucked inside the test suite.
 */
class PatternIndexGeneratorTest {

    @Serializable
    private data class MetaJson(
        val id: String,
        val kind: String,
        val description: String,
        val keywords: List<String> = emptyList(),
        val slots: List<MetaSlot> = emptyList(),
        val dependencies: List<String> = emptyList(),
    )

    @Serializable
    private data class MetaSlot(
        val name: String,
        val description: String,
        val default: String,
    )

    @Serializable
    private data class IndexEntry(
        val id: String,
        val kind: String,
        val description: String,
        val keywords: List<String>,
        val slotNames: List<String>,
        val dependencies: List<String>,
    )

    @Serializable
    private data class IndexJson(
        val version: Int,
        val generated: String,
        val patterns: List<IndexEntry>,
    )

    @Test
    fun `regenerates index and validates every pattern`() {
        val patternsRoot = locatePatternsDir()
        val blocksDir = File(patternsRoot, "blocks")
        val screensDir = File(patternsRoot, "screens")
        blocksDir.mkdirs()
        screensDir.mkdirs()

        val entries = mutableListOf<IndexEntry>()
        val json = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

        for ((kindDir, kindName) in listOf(blocksDir to "block", screensDir to "screen")) {
            val children = kindDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()
            for (dir in children) {
                val metaFile = File(dir, "meta.json")
                val layoutFile = File(dir, "layout.xml")
                val notesFile = File(dir, "notes.md")
                assertTrue("Missing meta.json in ${dir.absolutePath}", metaFile.exists())
                assertTrue("Missing layout.xml in ${dir.absolutePath}", layoutFile.exists())
                assertTrue("Missing notes.md in ${dir.absolutePath}", notesFile.exists())

                val meta = json.decodeFromString(MetaJson.serializer(), metaFile.readText())
                assertEquals("Directory name must equal meta.id", dir.name, meta.id)
                assertEquals("meta.kind must match directory kind", kindName, meta.kind.lowercase())
                assertTrue("meta.description empty in ${dir.name}", meta.description.isNotBlank())
                for (slot in meta.slots) {
                    assertTrue("slot name empty in ${dir.name}", slot.name.isNotBlank())
                    assertTrue("slot default empty in ${dir.name}/${slot.name}", slot.default.isNotBlank())
                }

                entries += IndexEntry(
                    id = meta.id,
                    kind = kindName,
                    description = meta.description,
                    keywords = meta.keywords,
                    slotNames = meta.slots.map { it.name },
                    dependencies = meta.dependencies,
                )
            }
        }

        val index = IndexJson(
            version = 1,
            generated = "2026-04-11T00:00:00Z",
            patterns = entries.sortedBy { it.id },
        )
        val output = json.encodeToString(IndexJson.serializer(), index) + "\n"
        File(patternsRoot, "index.json").writeText(output)

        println("PatternIndexGeneratorTest: regenerated index with ${entries.size} patterns")
    }

    private fun locatePatternsDir(): File {
        val candidates = listOf(
            File("app/src/main/assets/patterns"),
            File("../app/src/main/assets/patterns"),
            File(System.getProperty("user.dir"), "app/src/main/assets/patterns"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("Could not locate app/src/main/assets/patterns from ${File(".").absolutePath}")
    }
}
