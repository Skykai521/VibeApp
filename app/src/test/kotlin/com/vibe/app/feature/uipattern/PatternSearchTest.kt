package com.vibe.app.feature.uipattern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PatternSearchTest {

    private val hits = listOf(
        hit("list_item_two_line", PatternKind.BLOCK, "Two-line list row", listOf("list", "item", "row")),
        hit("screen_list", PatternKind.SCREEN, "List screen with empty state", listOf("list", "screen")),
        hit("section_header", PatternKind.BLOCK, "Group header with optional subtitle", listOf("header", "title")),
        hit("stat_card", PatternKind.BLOCK, "Numeric stat card", listOf("card", "stat", "number")),
    )

    private fun hit(id: String, kind: PatternKind, desc: String, kw: List<String>) = PatternSearchHit(
        id = id,
        kind = kind,
        description = desc,
        keywords = kw,
        slotNames = emptyList(),
        dependencies = emptyList(),
    )

    @Test
    fun `empty keyword returns empty`() {
        assertTrue(PatternSearch.search(hits, "", PatternKind.BLOCK, 10).isEmpty())
    }

    @Test
    fun `id exact match ranks first`() {
        val out = PatternSearch.search(hits, "screen_list", null, 10)
        assertEquals("screen_list", out.first().id)
    }

    @Test
    fun `id substring outranks description match`() {
        val out = PatternSearch.search(hits, "list", null, 10)
        assertTrue(out.take(2).map { it.id }.containsAll(listOf("list_item_two_line", "screen_list")))
    }

    @Test
    fun `kind filter restricts results`() {
        val out = PatternSearch.search(hits, "list", PatternKind.SCREEN, 10)
        assertEquals(1, out.size)
        assertEquals("screen_list", out.first().id)
    }

    @Test
    fun `limit caps results`() {
        val out = PatternSearch.search(hits, "list", null, 1)
        assertEquals(1, out.size)
    }

    @Test
    fun `keyword hit found via keywords list`() {
        val out = PatternSearch.search(hits, "header", null, 10)
        assertEquals("section_header", out.first().id)
    }
}
