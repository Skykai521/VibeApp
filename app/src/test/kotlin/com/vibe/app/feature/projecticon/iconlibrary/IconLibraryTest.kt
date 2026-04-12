package com.vibe.app.feature.projecticon.iconlibrary

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IconLibraryTest {

    private val sampleJson = """
        {
          "prefix": "lucide",
          "icons": {
            "house":     { "body": "<path d='M1 1'/>" },
            "home-plus": { "body": "<path d='M2 2'/>" },
            "star":      { "body": "<path d='M3 3'/>" }
          },
          "aliases": {
            "home": { "parent": "house" }
          },
          "categories": {
            "Buildings": ["house", "home-plus"],
            "Shapes":    ["star"]
          },
          "width": 24,
          "height": 24
        }
    """.trimIndent()

    private fun library() = IconLibrary { ByteArrayInputStream(sampleJson.toByteArray()) }

    @Test
    fun `loads icons and expands aliases`() {
        val lib = library()
        assertEquals(4, lib.size())
        assertNotNull(lib.get("house"))
        assertNotNull(lib.get("home"))
        assertEquals("<path d='M1 1'/>", lib.get("home")?.body)
        assertNull(lib.get("nope"))
    }

    @Test
    fun `search ranks id matches before category matches`() {
        val hits = library().search("house")
        assertEquals("house", hits.first().id)
    }

    @Test
    fun `search matches by category name when id does not contain keyword`() {
        val hits = library().search("building")
        assertTrue(hits.any { it.id == "house" })
        assertTrue(hits.any { it.id == "home-plus" })
    }

    @Test
    fun `search respects the limit`() {
        val hits = library().search("h", limit = 2)
        assertEquals(2, hits.size)
    }
}
