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
    const val MAX_CONTENT_LENGTH = 8_000
}
