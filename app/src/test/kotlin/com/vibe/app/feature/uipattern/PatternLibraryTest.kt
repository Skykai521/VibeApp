package com.vibe.app.feature.uipattern

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PatternLibraryTest {

    private val sampleIndex = """
        {
          "version": 1,
          "generated": "2026-04-11T10:00:00Z",
          "patterns": [
            {
              "id": "list_item_two_line",
              "kind": "block",
              "description": "Two-line list row",
              "keywords": ["list", "item"],
              "slotNames": ["title", "subtitle"],
              "dependencies": []
            }
          ]
        }
    """.trimIndent()

    private val sampleMeta = """
        {
          "id": "list_item_two_line",
          "kind": "block",
          "description": "Two-line list row",
          "keywords": ["list", "item"],
          "slots": [
            { "name": "title", "description": "Primary", "default": "Title" },
            { "name": "subtitle", "description": "Secondary", "default": "Subtitle" }
          ],
          "dependencies": []
        }
    """.trimIndent()

    private val sampleLayout = "<View xmlns:android=\"http://schemas.android.com/apk/res/android\"/>"
    private val sampleNotes = "Use for simple rows."

    private fun library() = PatternLibrary(object : PatternLibrary.AssetProvider {
        override fun openIndex() = ByteArrayInputStream(sampleIndex.toByteArray())
        override fun openFile(relativePath: String) = when (relativePath) {
            "blocks/list_item_two_line/meta.json" -> ByteArrayInputStream(sampleMeta.toByteArray())
            "blocks/list_item_two_line/layout.xml" -> ByteArrayInputStream(sampleLayout.toByteArray())
            "blocks/list_item_two_line/notes.md" -> ByteArrayInputStream(sampleNotes.toByteArray())
            else -> throw java.io.FileNotFoundException(relativePath)
        }
    })

    @Test
    fun `loads index and returns hits`() {
        val hits = library().allHits()
        assertEquals(1, hits.size)
        assertEquals("list_item_two_line", hits.first().id)
        assertEquals(PatternKind.BLOCK, hits.first().kind)
    }

    @Test
    fun `get returns full record`() {
        val record = library().get("list_item_two_line")
        assertNotNull(record)
        assertEquals(2, record!!.slots.size)
        assertEquals("Title", record.slots.first().default)
        assertEquals(sampleLayout, record.layoutXml)
        assertEquals("Use for simple rows.", record.notes)
    }

    @Test
    fun `get unknown id returns null`() {
        assertNull(library().get("nope"))
    }
}
