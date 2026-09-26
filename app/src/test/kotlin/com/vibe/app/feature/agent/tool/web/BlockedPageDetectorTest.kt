package com.vibe.app.feature.agent.tool.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockedPageDetectorTest {

    @Test
    fun `cloudflare challenge page is detected`() {
        assertTrue(BlockedPageDetector.isBlockedPage("<html><title>Just a moment...</title><div class=\"cf-chl-widget\"></div></html>"))
    }

    @Test
    fun `google unusual traffic page is detected`() {
        assertTrue(BlockedPageDetector.isBlockedPage("<html>Our systems have detected unusual traffic from your computer network.</html>"))
    }

    @Test
    fun `baidu security verification page is detected`() {
        assertTrue(BlockedPageDetector.isBlockedPage("<html><title>百度安全验证</title>网络不给力，请稍后重试</html>"))
    }

    @Test
    fun `normal serp page is not flagged`() {
        assertFalse(BlockedPageDetector.isBlockedPage("<html><li class=\"b_algo\"><h2><a href=\"https://a.com\">Result</a></h2></li></html>"))
    }

    @Test
    fun `blank html is not flagged as blocked`() {
        assertFalse(BlockedPageDetector.isBlockedPage(""))
    }
}
