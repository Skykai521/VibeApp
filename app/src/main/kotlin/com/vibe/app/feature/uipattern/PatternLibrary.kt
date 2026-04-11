package com.vibe.app.feature.uipattern

import java.io.InputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * In-process catalogue of UI pattern blocks and screen templates loaded
 * from assets/patterns/. The index is read eagerly on first access; full
 * pattern records (layout.xml + notes.md) are loaded on demand.
 *
 * [allHits] and [get] are open so tests can substitute a fake. Production
 * callers should inject the Hilt-provided singleton, not subclass.
 */
open class PatternLibrary(
    private val assets: AssetProvider,
) {
    interface AssetProvider {
        fun openIndex(): InputStream
        fun openFile(relativePath: String): InputStream
    }

    @Serializable
    private data class IndexJson(
        val version: Int,
        val generated: String = "",
        val patterns: List<IndexEntry>,
    )

    @Serializable
    private data class IndexEntry(
        val id: String,
        val kind: String,
        val description: String,
        val keywords: List<String> = emptyList(),
        val slotNames: List<String> = emptyList(),
        val dependencies: List<String> = emptyList(),
    )

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

    private val json = Json { ignoreUnknownKeys = true }

    private val index: List<IndexEntry> by lazy {
        val raw = assets.openIndex().use { it.readBytes().decodeToString() }
        json.decodeFromString(IndexJson.serializer(), raw).patterns
    }

    private fun IndexEntry.toHit() = PatternSearchHit(
        id = id,
        kind = parseKind(kind),
        description = description,
        keywords = keywords,
        slotNames = slotNames,
        dependencies = dependencies,
    )

    open fun allHits(): List<PatternSearchHit> = index.map { it.toHit() }

    open fun get(id: String): PatternRecord? {
        val entry = index.firstOrNull { it.id == id } ?: return null
        val kind = parseKind(entry.kind)
        val dir = "${if (kind == PatternKind.BLOCK) "blocks" else "screens"}/$id"
        val meta = assets.openFile("$dir/meta.json").use { it.readBytes().decodeToString() }
            .let { json.decodeFromString(MetaJson.serializer(), it) }
        val layoutXml = assets.openFile("$dir/layout.xml").use { it.readBytes().decodeToString() }
        val notes = assets.openFile("$dir/notes.md").use { it.readBytes().decodeToString() }
        return PatternRecord(
            id = meta.id,
            kind = parseKind(meta.kind),
            description = meta.description,
            keywords = meta.keywords,
            slots = meta.slots.map { PatternSlot(it.name, it.description, it.default) },
            dependencies = meta.dependencies,
            layoutXml = layoutXml,
            notes = notes,
        )
    }

    private fun parseKind(raw: String): PatternKind = when (raw.lowercase()) {
        "block" -> PatternKind.BLOCK
        "screen" -> PatternKind.SCREEN
        else -> error("Unknown pattern kind '$raw'")
    }
}
