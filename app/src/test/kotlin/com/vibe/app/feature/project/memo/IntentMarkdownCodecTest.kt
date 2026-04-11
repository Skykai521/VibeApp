package com.vibe.app.feature.project.memo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentMarkdownCodecTest {

    @Test
    fun `encode produces structured markdown`() {
        val intent = Intent(
            purpose = "Users search a city and see current + 7-day forecast.",
            keyDecisions = listOf("Data source: wttr.in", "Storage: SharedPreferences for favorites"),
            knownLimits = listOf("No offline cache", "Celsius only"),
        )
        val md = IntentMarkdownCodec.encode(intent, appName = "Weather")
        assertEquals(
            """
            <!-- Maintained by AI, edited by you -->
            # Weather

            **Purpose**: Users search a city and see current + 7-day forecast.

            **Key Decisions**:
            - Data source: wttr.in
            - Storage: SharedPreferences for favorites

            **Known Limits**:
            - No offline cache
            - Celsius only
            """.trimIndent(),
            md.trim()
        )
    }

    @Test
    fun `decode recovers structured intent from encoded markdown`() {
        val original = Intent(
            purpose = "Quick calculator.",
            keyDecisions = listOf("Single Activity"),
            knownLimits = listOf("No scientific mode"),
        )
        val md = IntentMarkdownCodec.encode(original, appName = "Calc")
        val decoded = IntentMarkdownCodec.decode(md)
        assertEquals(original, decoded)
    }

    @Test
    fun `encode truncates over-length purpose`() {
        val long = "a".repeat(200)
        val intent = Intent(purpose = long, keyDecisions = emptyList(), knownLimits = emptyList())
        val md = IntentMarkdownCodec.encode(intent, appName = "X")
        assertTrue(md.contains("a".repeat(Intent.PURPOSE_MAX)))
        assertFalse(md.contains("a".repeat(Intent.PURPOSE_MAX + 1)))
    }

    @Test
    fun `encode truncates over-length list items`() {
        val long = "b".repeat(100)
        val intent = Intent(
            purpose = "p",
            keyDecisions = listOf(long),
            knownLimits = emptyList(),
        )
        val md = IntentMarkdownCodec.encode(intent, appName = "X")
        assertTrue(md.contains("b".repeat(Intent.LINE_MAX)))
        assertFalse(md.contains("b".repeat(Intent.LINE_MAX + 1)))
    }

    @Test
    fun `encode caps list to LIST_MAX items`() {
        val intent = Intent(
            purpose = "p",
            keyDecisions = List(10) { "decision_$it" },
            knownLimits = emptyList(),
        )
        val md = IntentMarkdownCodec.encode(intent, appName = "X")
        assertTrue(md.contains("decision_0"))
        assertTrue(md.contains("decision_${Intent.LIST_MAX - 1}"))
        assertFalse(md.contains("decision_${Intent.LIST_MAX}"))
    }

    @Test
    fun `decode ignores the HTML comment header`() {
        val md = """
            <!-- Maintained by AI, edited by you -->
            # X

            **Purpose**: foo

            **Key Decisions**:
            - one
        """.trimIndent()
        val decoded = IntentMarkdownCodec.decode(md)
        assertEquals("foo", decoded.purpose)
        assertEquals(listOf("one"), decoded.keyDecisions)
        assertEquals(emptyList<String>(), decoded.knownLimits)
    }
}
