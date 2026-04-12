package com.vibe.app.feature.uipattern

object PatternSearch {

    fun search(
        hits: List<PatternSearchHit>,
        keyword: String,
        kind: PatternKind?,
        limit: Int,
    ): List<PatternSearchHit> {
        val needle = keyword.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        if (limit <= 0) return emptyList()

        val filtered = if (kind == null) hits else hits.filter { it.kind == kind }

        val ranked = filtered.mapNotNull { hit ->
            val idLower = hit.id.lowercase()
            val score = when {
                idLower == needle -> 0
                idLower.contains(needle) -> 1
                hit.keywords.any { it.lowercase().contains(needle) } -> 2
                hit.description.lowercase().contains(needle) -> 3
                else -> return@mapNotNull null
            }
            score to hit
        }

        return ranked
            .sortedWith(compareBy({ it.first }, { it.second.id }))
            .take(limit)
            .map { it.second }
    }
}
