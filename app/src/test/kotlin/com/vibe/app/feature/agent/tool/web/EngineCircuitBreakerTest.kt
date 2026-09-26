package com.vibe.app.feature.agent.tool.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineCircuitBreakerTest {

    @Test
    fun `engine is skipped during cooldown and usable after`() {
        var now = 0L
        val breaker = EngineCircuitBreaker(cooldownMs = 300_000, clock = { now })
        assertFalse(breaker.isOpen("Bing"))
        breaker.recordBlocked("Bing")
        assertTrue(breaker.isOpen("Bing"))
        assertFalse("other engines unaffected", breaker.isOpen("Baidu"))
        now = 299_999
        assertTrue(breaker.isOpen("Bing"))
        now = 300_000
        assertFalse(breaker.isOpen("Bing"))
    }
}
