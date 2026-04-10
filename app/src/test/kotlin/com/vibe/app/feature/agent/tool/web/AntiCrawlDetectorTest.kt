package com.vibe.app.feature.agent.tool.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AntiCrawlDetectorTest {

    @Test
    fun `Cloudflare challenge HTML is detected as anti-crawl`() {
        val html = """
            <html><head><title>Just a moment...</title></head>
            <body>
                <div class="cf-challenge-running">Please wait while we verify your browser...</div>
            </body></html>
        """.trimIndent()
        assertTrue(WebConstants.isAntiCrawlResponse(503, html))
    }

    @Test
    fun `empty body is detected as anti-crawl`() {
        assertTrue(WebConstants.isAntiCrawlResponse(200, ""))
        assertTrue(WebConstants.isAntiCrawlResponse(200, "   "))
    }

    @Test
    fun `403 with short body is detected as anti-crawl`() {
        assertTrue(WebConstants.isAntiCrawlResponse(403, "<html><body>Forbidden</body></html>"))
    }

    @Test
    fun `normal 200 response with enough content is not anti-crawl`() {
        val content = "a".repeat(500) // well above 200 char threshold
        val html = "<html><head><title>Test</title></head><body><p>$content</p></body></html>"
        assertFalse(WebConstants.isAntiCrawlResponse(200, html))
    }

    @Test
    fun `page with captcha in body is detected as anti-crawl`() {
        val html = """
            <html><head><title>Verify</title></head>
            <body>
                <p>Please complete the captcha to continue.</p>
                ${"x".repeat(300)}
            </body></html>
        """.trimIndent()
        assertTrue(WebConstants.isAntiCrawlResponse(200, html))
    }

    @Test
    fun `page with recaptcha in body is detected as anti-crawl`() {
        val html = """
            <html><head><title>Security Check</title></head>
            <body>
                <div class="g-recaptcha" data-sitekey="xxx"></div>
                ${"x".repeat(300)}
            </body></html>
        """.trimIndent()
        assertTrue(WebConstants.isAntiCrawlResponse(200, html))
    }

    @Test
    fun `page with Chinese security verification is detected as anti-crawl`() {
        val html = """
            <html><head><title>安全验证</title></head>
            <body><p>请完成安全验证后继续访问</p>${"x".repeat(300)}</body></html>
        """.trimIndent()
        assertTrue(WebConstants.isAntiCrawlResponse(200, html))
    }

    @Test
    fun `short body under 200 chars with 200 status is detected as anti-crawl`() {
        assertTrue(WebConstants.isAntiCrawlResponse(200, "<html><body>x</body></html>"))
    }

    @Test
    fun `500 status is not anti-crawl when body has enough content`() {
        val content = "a".repeat(500)
        val html = "<html><body><p>$content</p></body></html>"
        // 500 is a server error, not anti-crawl (not 403/503)
        assertFalse(WebConstants.isAntiCrawlResponse(500, html))
    }
}
