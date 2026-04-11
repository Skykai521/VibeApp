package com.vibe.app.feature.uipattern

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignGuideLoaderTest {

    private val sample = """
        # VibeApp UI Design Guide

        Intro paragraph.

        ## Tokens

        Color attrs list.

        ## Components

        Component usage.

        ## Layout

        Spacing rules.

        ## Creative Mode

        Overrides allowed.
    """.trimIndent()

    private fun loader() = DesignGuideLoader { ByteArrayInputStream(sample.toByteArray()) }

    @Test
    fun `returns full document for all`() {
        val content = loader().load("all")
        assertTrue(content.contains("# VibeApp UI Design Guide"))
        assertTrue(content.contains("## Tokens"))
        assertTrue(content.contains("## Creative Mode"))
    }

    @Test
    fun `returns single section by name`() {
        val content = loader().load("tokens")
        assertTrue(content.startsWith("## Tokens"))
        assertTrue(content.contains("Color attrs list"))
        assertTrue(!content.contains("## Components"))
    }

    @Test
    fun `section name case insensitive`() {
        val content = loader().load("CREATIVE")
        assertTrue(content.startsWith("## Creative Mode"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unknown section throws`() {
        loader().load("nope")
    }
}
