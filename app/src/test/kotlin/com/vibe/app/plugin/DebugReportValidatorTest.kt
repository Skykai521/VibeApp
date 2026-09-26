package com.vibe.app.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DebugReportValidatorTest {

    @Test
    fun `projectIdFromPackage extracts id from generated package`() {
        assertEquals("abc123", DebugReportValidator.projectIdFromPackage("com.vibe.generated.pabc123"))
    }

    @Test
    fun `projectIdFromPackage rejects foreign packages`() {
        assertNull(DebugReportValidator.projectIdFromPackage("com.evil.app"))
        assertNull(DebugReportValidator.projectIdFromPackage("com.vibe.generated.p"))
        assertNull(DebugReportValidator.projectIdFromPackage(null))
    }

    @Test
    fun `projectIdFromPackage rejects path traversal characters`() {
        assertNull(DebugReportValidator.projectIdFromPackage("com.vibe.generated.p../../etc"))
    }

    @Test
    fun `truncateForAppend caps content size`() {
        val big = "x".repeat(300_000)
        assertEquals(64 * 1024, DebugReportValidator.truncateForAppend(big).length)
    }
}
