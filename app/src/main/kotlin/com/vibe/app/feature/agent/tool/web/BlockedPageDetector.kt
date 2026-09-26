package com.vibe.app.feature.agent.tool.web

/** Heuristic detection of anti-bot / captcha interstitial pages. Only consulted
 *  when result parsing yields zero items, so broad markers are acceptable. */
object BlockedPageDetector {
    private val markers = listOf(
        // Cloudflare / generic
        "just a moment...", "checking your browser", "cf-chl", "turnstile",
        "verify you are human", "captcha", "grecaptcha", "hcaptcha",
        // Google
        "unusual traffic",
        // Baidu / CN
        "百度安全验证", "安全验证", "网络不给力", "异常访问请求", "请输入验证码",
    )

    fun isBlockedPage(html: String): Boolean {
        if (html.isBlank()) return false
        val head = html.take(20_000).lowercase()
        return markers.any { head.contains(it) }
    }
}
