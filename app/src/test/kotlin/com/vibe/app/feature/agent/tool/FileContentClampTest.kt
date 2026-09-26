package com.vibe.app.feature.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileContentClampTest {

    @Test
    fun `small file passes through unchanged`() {
        val content = (1..10).joinToString("\n") { "line $it" }
        val result = clampFileContent(content)
        assertFalse(result.truncated)
        assertEquals(content, result.content)
        assertEquals(10, result.totalLines)
    }

    @Test
    fun `file over line limit is cut to maxLines`() {
        val content = (1..3000).joinToString("\n") { "line $it" }
        val result = clampFileContent(content, maxLines = 2000, maxChars = 50_000)
        assertTrue(result.truncated)
        assertEquals(3000, result.totalLines)
        assertEquals(2000, result.content.lines().size)
        assertTrue(result.content.endsWith("line 2000"))
    }

    @Test
    fun `file over char limit is cut to maxChars`() {
        val content = "x".repeat(60_000) // 单行超长
        val result = clampFileContent(content, maxLines = 2000, maxChars = 50_000)
        assertTrue(result.truncated)
        assertEquals(50_000, result.content.length)
    }

    @Test
    fun `logicalLineCount excludes trailing empty line from final newline`() {
        assertEquals(3, logicalLineCount("a\nb\nc\n"))
    }

    @Test
    fun `logicalLineCount counts all lines without trailing newline`() {
        assertEquals(3, logicalLineCount("a\nb\nc"))
    }

    @Test
    fun `deliveredRangeEnd returns requested end when clamp not truncated`() {
        val clamp = ClampResult(content = "a\nb\nc", truncated = false, totalLines = 3)
        assertEquals(50, deliveredRangeEnd(rangeStart = 10, rangeEnd = 50, clamp = clamp))
    }

    @Test
    fun `deliveredRangeEnd reports last delivered line when clamp truncated`() {
        // clamp only delivered 5 logical lines starting at rangeStart = 100
        val clamp = ClampResult(
            content = (1..5).joinToString("\n") { "line $it" },
            truncated = true,
            totalLines = 5,
        )
        assertEquals(104, deliveredRangeEnd(rangeStart = 100, rangeEnd = 500, clamp = clamp))
    }

    @Test
    fun `deliveredRangeEnd never exceeds the requested rangeEnd`() {
        // more delivered lines than the requested range span (defensive cap)
        val clamp = ClampResult(
            content = (1..10).joinToString("\n") { "line $it" },
            truncated = true,
            totalLines = 10,
        )
        assertEquals(105, deliveredRangeEnd(rangeStart = 100, rangeEnd = 105, clamp = clamp))
    }
}
