package com.vibe.build.runtime.bootstrap

import org.junit.Assert.assertEquals
import org.junit.Test

class MirrorSelectorTest {

    private val primary = "https://github.com/Skykai521/VibeApp/releases/download/v2.0.0"
    private val fallback = "https://mirror.example.com/vibeapp/v2.0.0"

    @Test
    fun `initial session uses primary`() {
        val selector = MirrorSelector(primary, fallback)
        assertEquals("$primary/jdk.tar.zst", selector.currentUrlFor("jdk.tar.zst"))
    }

    @Test
    fun `markPrimaryFailed switches to fallback`() {
        val selector = MirrorSelector(primary, fallback)
        selector.markPrimaryFailed()
        assertEquals("$fallback/jdk.tar.zst", selector.currentUrlFor("jdk.tar.zst"))
    }

    @Test
    fun `once on fallback further failures keep fallback sticky`() {
        val selector = MirrorSelector(primary, fallback)
        selector.markPrimaryFailed()
        selector.markPrimaryFailed()   // no-op
        assertEquals("$fallback/jdk.tar.zst", selector.currentUrlFor("jdk.tar.zst"))
    }

    @Test
    fun `reset brings back primary`() {
        val selector = MirrorSelector(primary, fallback)
        selector.markPrimaryFailed()
        selector.reset()
        assertEquals("$primary/jdk.tar.zst", selector.currentUrlFor("jdk.tar.zst"))
    }

    @Test
    fun `currentMirrorName returns PRIMARY or FALLBACK`() {
        val selector = MirrorSelector(primary, fallback)
        assertEquals("PRIMARY", selector.currentMirrorName())
        selector.markPrimaryFailed()
        assertEquals("FALLBACK", selector.currentMirrorName())
    }
}
