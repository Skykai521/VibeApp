package com.vibe.app.feature.agent.tool.web

object WebConstants {
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    const val ACCEPT_LANGUAGE = "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7"
    const val ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    const val SEC_FETCH_DEST = "document"
    const val SEC_FETCH_MODE = "navigate"
    const val SEC_FETCH_SITE = "none"
    const val SEC_FETCH_USER = "?1"

    const val TIMEOUT_MS = 15_000L
    const val WEBVIEW_TIMEOUT_MS = 20_000L
    const val MAX_CONTENT_LENGTH = 8_000

    private val ANTI_CRAWL_PATTERNS = listOf(
        "cf-challenge-running",
        "cf-browser-verification",
        "captcha",
        "recaptcha",
        "hcaptcha",
        "verify you are human",
        "请完成安全验证",
        "access denied",
        "just a moment",
        "checking your browser",
        "enable javascript and cookies",
    )

    /**
     * Heuristic check for responses that indicate anti-crawling mechanisms
     * blocked the real page content.
     */
    fun isAntiCrawlResponse(statusCode: Int, body: String): Boolean {
        if (body.isBlank()) return true
        if (statusCode == 403 || statusCode == 503) return true
        if (body.length < 200) return true
        val lower = body.lowercase()
        return ANTI_CRAWL_PATTERNS.any { pattern -> lower.contains(pattern) }
    }
}
