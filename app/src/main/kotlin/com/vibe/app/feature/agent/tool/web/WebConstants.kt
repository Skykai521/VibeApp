package com.vibe.app.feature.agent.tool.web

object WebConstants {
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    /** WebView page-load timeout in milliseconds. */
    const val WEBVIEW_TIMEOUT_MS = 20_000L

    /** Maximum content length returned to the agent. */
    const val MAX_CONTENT_LENGTH = 8_000
}
