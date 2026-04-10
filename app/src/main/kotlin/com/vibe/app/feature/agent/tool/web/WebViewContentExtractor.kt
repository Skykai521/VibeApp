package com.vibe.app.feature.agent.tool.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

data class WebViewExtractionResult(
    val title: String,
    val content: String,
    val url: String,
    val byline: String = "",
    val excerpt: String = "",
)

@Singleton
class WebViewContentExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** Lazily loaded and concatenated JS libraries from assets. */
    private val jsLibraries: String by lazy {
        val assetManager = context.assets
        val libs = listOf(
            "js/readability.js",
            "js/turndown.js",
            "js/turndown-plugin-gfm.js",
            "js/extract-content.js",
        )
        libs.joinToString("\n\n") { path ->
            assetManager.open(path).bufferedReader().use { it.readText() }
        }
    }

    /**
     * Load [url] in a hidden WebView, inject Readability + Turndown JS, and
     * return the page content as Markdown.
     */
    suspend fun extract(url: String): Result<WebViewExtractionResult> = runCatching {
        withTimeout(WebConstants.WEBVIEW_TIMEOUT_MS) {
            val rawJson = loadAndEvaluate(url, jsLibraries)
            parseExtractionResult(rawJson, url)
        }
    }

    /**
     * Load [url] in a hidden WebView and return the raw outer HTML.
     * Useful for search-engine result pages that need engine-specific parsing.
     */
    suspend fun extractRawHtml(url: String): Result<String> = runCatching {
        withTimeout(WebConstants.WEBVIEW_TIMEOUT_MS) {
            val script = "(function() { return document.documentElement.outerHTML; })();"
            loadAndEvaluate(url, script)
        }
    }

    // ── internal helpers ────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun loadAndEvaluate(url: String, javascript: String): String =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val webView = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadsImagesAutomatically = false
                    settings.blockNetworkImage = true
                    settings.userAgentString = WebConstants.USER_AGENT
                }

                cont.invokeOnCancellation {
                    webView.stopLoading()
                    webView.destroy()
                }

                var finished = false

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, requestUrl: String?, favicon: Bitmap?) {
                        // no-op
                    }

                    override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                        if (finished) return
                        webView.evaluateJavascript(javascript) { rawValue ->
                            if (finished) return@evaluateJavascript
                            finished = true
                            val unescaped = unescapeJsString(rawValue)
                            webView.destroy()
                            cont.resume(unescaped)
                        }
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        // Only treat main-frame errors as fatal.
                        if (request?.isForMainFrame == true && !finished) {
                            finished = true
                            webView.destroy()
                            cont.resume("")
                        }
                    }
                }

                webView.loadUrl(url)
            }
        }

    private fun parseExtractionResult(rawJson: String, url: String): WebViewExtractionResult {
        if (rawJson.isBlank()) {
            throw RuntimeException("WebView returned empty result for $url")
        }

        val obj = try {
            json.parseToJsonElement(rawJson).jsonObject
        } catch (e: Exception) {
            throw RuntimeException("Failed to parse WebView extraction JSON: ${e.message}")
        }

        val error = obj["error"]?.jsonPrimitive?.content
        if (error != null) {
            // Readability failed — use plain text fallback from extract-content.js
            val fallbackTitle = obj["fallback"]?.jsonPrimitive?.content.orEmpty()
            val fallbackText = obj["text"]?.jsonPrimitive?.content.orEmpty()
            if (fallbackText.isBlank()) {
                throw RuntimeException("Extraction failed and no fallback text: $error")
            }
            val truncated = truncate(fallbackText)
            return WebViewExtractionResult(
                title = fallbackTitle,
                content = truncated,
                url = url,
            )
        }

        val title = obj["title"]?.jsonPrimitive?.content.orEmpty()
        val content = obj["content"]?.jsonPrimitive?.content.orEmpty()
        val byline = obj["byline"]?.jsonPrimitive?.content.orEmpty()
        val excerpt = obj["excerpt"]?.jsonPrimitive?.content.orEmpty()

        if (content.isBlank()) {
            throw RuntimeException("WebView extraction returned empty content for $url")
        }

        return WebViewExtractionResult(
            title = title,
            content = truncate(content),
            url = url,
            byline = byline,
            excerpt = excerpt,
        )
    }

    private fun truncate(text: String): String {
        return if (text.length > WebConstants.MAX_CONTENT_LENGTH) {
            text.take(WebConstants.MAX_CONTENT_LENGTH) + "\n... [truncated]"
        } else {
            text
        }
    }

    companion object {
        private const val TAG = "WebViewExtractor"

        /**
         * WebView's `evaluateJavascript` returns strings wrapped in double quotes
         * with internal characters escaped. This undoes that encoding.
         */
        fun unescapeJsString(raw: String?): String {
            if (raw.isNullOrEmpty() || raw == "null") return ""
            var s = raw
            // Strip surrounding quotes
            if (s.startsWith("\"") && s.endsWith("\"")) {
                s = s.substring(1, s.length - 1)
            }
            return s
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\/", "/")
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\u003C", "<")
                .replace("\\u003c", "<")
                .replace("\\u003E", ">")
                .replace("\\u003e", ">")
        }
    }
}
