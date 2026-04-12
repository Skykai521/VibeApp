package com.vibe.app.feature.projecticon.iconlibrary

import java.io.InputStream
import kotlinx.serialization.json.Json

/**
 * In-process catalogue of preset launcher icons loaded from an Iconify JSON blob.
 *
 * Loads lazily on first access. The stream is read once. Android code wires the
 * provider to AssetManager; tests feed an in-memory JSON via [InputStreamProvider].
 */
class IconLibrary(
    private val streamProvider: InputStreamProvider,
) {
    fun interface InputStreamProvider {
        fun open(): InputStream
    }

    data class IconRecord(
        val id: String,
        val body: String,
        val width: Int,
        val height: Int,
        val categories: List<String>,
    )

    data class SearchHit(
        val id: String,
        val categories: List<String>,
    )

    private val data: Loaded by lazy { load() }

    private data class Loaded(
        val byId: Map<String, IconRecord>,
        val orderedIds: List<String>,
    )

    private fun load(): Loaded {
        val json = Json { ignoreUnknownKeys = true }
        val raw = streamProvider.open().use { it.readBytes().decodeToString() }
        val set = json.decodeFromString(IconifyIconSet.serializer(), raw)

        val iconToCategories = HashMap<String, MutableList<String>>()
        for ((cat, ids) in set.categories) {
            for (id in ids) {
                iconToCategories.getOrPut(id) { mutableListOf() }.add(cat)
            }
        }

        val byId = LinkedHashMap<String, IconRecord>(set.icons.size)
        for ((id, entry) in set.icons) {
            byId[id] = IconRecord(
                id = id,
                body = entry.body,
                width = entry.width ?: set.width,
                height = entry.height ?: set.height,
                categories = iconToCategories[id].orEmpty(),
            )
        }
        for ((alias, target) in set.aliases) {
            val parent = byId[target.parent] ?: continue
            byId.putIfAbsent(alias, parent.copy(id = alias))
        }
        return Loaded(byId = byId, orderedIds = byId.keys.toList())
    }

    fun get(id: String): IconRecord? = data.byId[id]

    fun size(): Int = data.byId.size

    /**
     * Substring search over icon id + category names (case-insensitive).
     * Id matches rank before category matches. Returns up to [limit] hits.
     */
    fun search(keyword: String, limit: Int = 20): List<SearchHit> {
        val needle = keyword.trim().lowercase()
        if (needle.isEmpty()) return emptyList()

        val idHits = ArrayList<SearchHit>()
        val categoryHits = ArrayList<SearchHit>()

        for (id in data.orderedIds) {
            val record = data.byId[id] ?: continue
            val idMatch = id.contains(needle)
            val catMatch = !idMatch && record.categories.any { it.lowercase().contains(needle) }
            if (idMatch) {
                idHits += SearchHit(id, record.categories)
                if (idHits.size >= limit) break
            } else if (catMatch) {
                categoryHits += SearchHit(id, record.categories)
            }
        }

        val combined = ArrayList<SearchHit>(idHits.size + categoryHits.size)
        combined.addAll(idHits)
        for (hit in categoryHits) {
            if (combined.size >= limit) break
            combined.add(hit)
        }
        return combined
    }
}
