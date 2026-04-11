package com.vibe.app.feature.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Test

class SliceByLinesTest {

    @Test
    fun `default range returns entire file`() {
        val (content, range) = sliceByLines("L1\nL2\nL3\nL4\n", startLine = 1, endLine = -1)
        assertEquals("L1\nL2\nL3\nL4", content)
        assertEquals(1..4, range)
    }

    @Test
    fun `subrange returns inclusive slice`() {
        val (content, range) = sliceByLines("L1\nL2\nL3\nL4\n", startLine = 2, endLine = 3)
        assertEquals("L2\nL3", content)
        assertEquals(2..3, range)
    }

    @Test
    fun `end line minus one means last line`() {
        val (content, range) = sliceByLines("L1\nL2\nL3\n", startLine = 2, endLine = -1)
        assertEquals("L2\nL3", content)
        assertEquals(2..3, range)
    }

    @Test
    fun `end beyond total clamps to last line`() {
        val (content, range) = sliceByLines("L1\nL2\n", startLine = 1, endLine = 999)
        assertEquals("L1\nL2", content)
        assertEquals(1..2, range)
    }

    @Test
    fun `start less than 1 is clamped to 1`() {
        val (content, range) = sliceByLines("L1\nL2\n", startLine = 0, endLine = 2)
        assertEquals("L1\nL2", content)
        assertEquals(1..2, range)
    }

    @Test
    fun `start beyond total returns empty`() {
        val (content, range) = sliceByLines("L1\nL2\n", startLine = 5, endLine = 10)
        assertEquals("", content)
        assertEquals(IntRange.EMPTY, range)
    }

    @Test
    fun `end before start returns empty`() {
        val (content, range) = sliceByLines("L1\nL2\nL3\n", startLine = 3, endLine = 2)
        assertEquals("", content)
        assertEquals(IntRange.EMPTY, range)
    }

    @Test
    fun `empty content returns empty`() {
        val (content, range) = sliceByLines("", startLine = 1, endLine = -1)
        assertEquals("", content)
        assertEquals(IntRange.EMPTY, range)
    }

    @Test
    fun `file without trailing newline is handled`() {
        val (content, range) = sliceByLines("L1\nL2\nL3", startLine = 1, endLine = -1)
        assertEquals("L1\nL2\nL3", content)
        assertEquals(1..3, range)
    }
}
