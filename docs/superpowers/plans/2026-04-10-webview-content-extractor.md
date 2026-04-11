# WebView Content Extractor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a WebView-based content extraction path that bypasses anti-crawling by using a real browser engine, with Readability.js + Turndown.js for clean Markdown output, falling back from the existing OkHttp path.

**Architecture:** A hidden off-screen WebView loads URLs with full JS execution. After page load, injected JS (Readability.js extracts article content, Turndown.js converts to Markdown) returns structured results via `evaluateJavascript`. A `WebContentFetcher` strategy class tries OkHttp first, detects anti-crawling failures (403, Cloudflare challenge pages, empty content), and falls back to the WebView path. The same approach enhances `WebSearchTool` for search engine pages that block HTTP clients.

**Tech Stack:** Android WebView, Readability.js (Apache-2.0), Turndown.js + turndown-plugin-gfm (MIT), Kotlin Coroutines, Hilt DI

---

## File Structure

### New files
| File | Responsibility |
|------|---------------|
| `app/src/main/assets/js/readability.js` | Mozilla Readability.js browser bundle |
| `app/src/main/assets/js/turndown.js` | Turndown.js browser bundle |
| `app/src/main/assets/js/turndown-plugin-gfm.js` | GFM plugin (tables, strikethrough) |
| `app/src/main/assets/js/extract-content.js` | Orchestration script: calls Readability + Turndown, returns JSON |
| `app/src/main/kotlin/com/vibe/app/feature/agent/tool/web/WebViewContentExtractor.kt` | Core class: manages hidden WebView, loads URL, injects JS, returns Markdown |
| `app/src/main/kotlin/com/vibe/app/feature/agent/tool/web/WebContentFetcher.kt` | Strategy: OkHttp-first with WebView fallback |
| `app/src/test/kotlin/com/vibe/app/feature/agent/tool/web/AntiCrawlDetectorTest.kt` | Unit tests for anti-crawl detection logic |

### Modified files
| File | Change |
|------|--------|
| `app/src/main/kotlin/com/vibe/app/feature/agent/tool/web/WebConstants.kt` | Add WebView timeout, anti-crawl detection constants |
| `app/src/main/kotlin/com/vibe/app/feature/agent/tool/FetchWebPageTool.kt` | Replace direct OkHttp+Readability4J with `WebContentFetcher` |
| `app/src/main/kotlin/com/vibe/app/feature/agent/tool/web/WebSearchExecutor.kt` | Add WebView fallback for search engine requests |
| `app/src/main/kotlin/com/vibe/app/di/AgentToolModule.kt` | Add `WebViewContentExtractor` and `WebContentFetcher` bindings |
| `app/src/main/kotlin/com/vibe/app/di/NetworkModule.kt` | Provide Application Context for WebView creation |

---

### Task 1: Download and bundle JS libraries into assets

**Files:**
- Create: `app/src/main/assets/js/readability.js`
- Create: `app/src/main/assets/js/turndown.js`
- Create: `app/src/main/assets/js/turndown-plugin-gfm.js`

- [ ] **Step 1: Download Readability.js**

```bash
curl -L -o app/src/main/assets/js/readability.js \
  "https://raw.githubusercontent.com/nicehash/nicehash-readability/main/Readability.js"
```

If the above URL is unavailable, use the official Mozilla release:
```bash
curl -L "https://github.com/nicehash/nicehash-readability/archive/refs/heads/main.zip" -o /tmp/readability.zip
# Or build from source:
cd /tmp && git clone https://github.com/nicehash/nicehash-readability.git && cp nicehash-readability/Readability.js app/src/main/assets/js/readability.js
```

**IMPORTANT**: The official `@nicehash/readability` repo may not exist. Use the actual Mozilla Readability standalone JS file from the `mozilla/readability` repository. The key file needed is `Readability.js` — a single self-contained file that works in browser contexts.

Correct approach:
```bash
cd /tmp && git clone --depth 1 https://github.com/nicehash/nicehash-readability.git 2>/dev/null || \
  git clone --depth 1 https://github.com/nicehash/nicehash-readability.git
```

If cloning fails, manually download from the GitHub releases page or npm:
```bash
npm pack @nicehash/readability 2>/dev/null || npm pack @nicehash/readability
# Extract and copy Readability.js
```

**Simplest reliable approach — use npm to get the files:**
```bash
cd /tmp
npm init -y
npm install @nicehash/readability turndown turndown-plugin-gfm
# Readability.js is at node_modules/@nicehash/readability/Readability.js
# turndown browser bundle is at node_modules/turndown/dist/turndown.js  
# gfm plugin is at node_modules/turndown-plugin-gfm/dist/turndown-plugin-gfm.js
```

- [ ] **Step 2: Download Turndown.js browser bundle**

```bash
cp /tmp/node_modules/turndown/dist/turndown.js app/src/main/assets/js/turndown.js
```

- [ ] **Step 3: Download turndown-plugin-gfm**

```bash
cp /tmp/node_modules/turndown-plugin-gfm/dist/turndown-plugin-gfm.js app/src/main/assets/js/turndown-plugin-gfm.js
```

- [ ] **Step 4: Verify files exist and are non-empty**

```bash
ls -la app/src/main/assets/js/
# Expected: readability.js (~40-60KB), turndown.js (~30-40KB), turndown-plugin-gfm.js (~5-10KB)
wc -l app/src/main/assets/js/*.js
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/assets/js/
git commit -m "feat: bundle Readability.js and Turndown.js for WebView content extraction"
```

---

### Task 2: Create the JS extraction orchestration script

**Files:**
- Create: `app/src/main/assets/js/extract-content.js`

This script is injected into WebView after page load. It calls Readability to extract article HTML, then Turndown to convert to Markdown.

- [ ] **Step 1: Write the extraction script**

```javascript
// extract-content.js
// Orchestrates Readability.js + Turndown.js inside a WebView.
// Returns a JSON string: { title, content, byline, excerpt, siteName, url }
// On failure: { error: "message" }
(function() {
  try {
    // 1. Extract article with Readability
    var docClone = document.cloneNode(true);
    var article = new Readability(docClone).parse();
    if (!article || !article.content) {
      return JSON.stringify({
        error: 'readability_extraction_failed',
        fallback: document.title || '',
        text: document.body ? document.body.innerText.substring(0, 12000) : ''
      });
    }

    // 2. Convert article HTML to Markdown with Turndown
    var turndownService = new TurndownService({
      headingStyle: 'atx',
      codeBlockStyle: 'fenced',
      bulletListMarker: '-',
      emDelimiter: '*',
      strongDelimiter: '**',
      linkStyle: 'inlined'
    });

    // Enable GFM plugin if available (tables, strikethrough, task lists)
    if (typeof turndownPluginGfm !== 'undefined') {
      turndownService.use(turndownPluginGfm.gfm);
    }

    // Remove images to keep output text-focused
    turndownService.addRule('removeImages', {
      filter: 'img',
      replacement: function() { return ''; }
    });

    var markdown = turndownService.turndown(article.content);

    return JSON.stringify({
      title: article.title || document.title || '',
      content: markdown,
      byline: article.byline || '',
      excerpt: article.excerpt || '',
      siteName: article.siteName || '',
      url: window.location.href
    });
  } catch (e) {
    return JSON.stringify({
      error: e.message || 'unknown_error',
      fallback: document.title || '',
      text: document.body ? document.body.innerText.substring(0, 12000) : ''
    });
  }
})();
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/assets/js/extract-content.js
git commit -m "feat: add JS extraction orchestration script for WebView"
```

---

### Task 3: Add anti-crawl detection constants to WebConstants

**Files:**
- Modify: `app/src/main/kotlin/com/vibe/app/feature/agent/tool/web/WebConstants.kt`
- Test: `app/src/test/kotlin/com/vibe/app/feature/agent/tool/web/AntiCrawlDetectorTest.kt`

- [ ] **Step 1: Write the anti-crawl detection test**

```kotlin
package com.vibe.app.feature.agent.tool.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AntiCrawlDetectorTest {

    @Test
    fun `detects Cloudflare challenge page`() {
        val html = """
            <html><head><title>Just a moment...</title></head>
            <body><div id="cf-challenge-running">Please wait...</div></body></html>
        """.trimIndent()
        assertTrue(WebConstants.isAntiCrawlResponse(403, html))
    }

    @Test
    fun `detects empty body as anti-crawl`() {
        assertTrue(WebConstants.isAntiCrawlResponse(200, ""))
        assertTrue(WebConstants.isAntiCrawlResponse(200, "   "))
    }

    @Test
    fun `detects 403 with short body as anti-crawl`() {
        assertTrue(WebConstants.isAntiCrawlResponse(403, "<html><body>Forbidden</body></html>"))
    }

    @Test
    fun `does not flag normal 200 response`() {
        val html = """
            <html><head><title>Hello</title></head>
            <body><p>This is a normal article with enough content to pass the check.</p>
            <p>More content here to make the body long enough.</p></body></html>
        """.trimIndent()
        assertFalse(WebConstants.isAntiCrawlResponse(200, html))
    }

    @Test
    fun `detects captcha page`() {
        val html = """
            <html><head><title>Verify</title></head>
            <body><div class="captcha">Please verify you are human</div></body></html>
        """.trimIndent()
        assertTrue(WebConstants.isAntiCrawlResponse(200, html))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :app:testDebugUnitTest --tests "com.vibe.app.feature.agent.tool.web.AntiCrawlDetectorTest" 2>&1 | tail -5
```
Expected: Compilation error — `isAntiCrawlResponse` does not exist yet.

- [ ] **Step 3: Update WebConstants with anti-crawl detection and WebView constants**

Replace the full file content of `app/src/main/kotlin/com/vibe/app/feature/agent/tool/web/WebConstants.kt`:

```kotlin
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

    /** WebView page-load timeout in milliseconds. */
    const val WEBVIEW_TIMEOUT_MS = 20_000L

    /** Minimum body length to consider a page successfully fetched via OkHttp. */
    private const val MIN_BODY_LENGTH = 200

    /** Patterns that indicate an anti-crawl challenge or block page. */
    private val ANTI_CRAWL_PATTERNS = listOf(
        "cf-challenge-running",     // Cloudflare JS challenge
        "cf-turnstile",             // Cloudflare Turnstile
        "challenge-platform",       // Cloudflare challenge
        "captcha",                  // Generic CAPTCHA
        "recaptcha",                // Google reCAPTCHA
        "hcaptcha",                 // hCaptcha
        "verify you are human",     // Common verification text
        "请完成安全验证",              // Chinese: "please complete security verification"
        "access denied",            // Generic block
    )

    /**
     * Returns true if the HTTP response looks like an anti-crawl block rather than
     * real page content. Used to decide whether to fall back to WebView.
     */
    fun isAntiCrawlResponse(statusCode: Int, body: String): Boolean {
        // Empty or near-empty body
        if (body.isBlank()) return true

        // 403/503 with any body is likely a block
        if (statusCode == 403 || statusCode == 503) return true

        // Short body with anti-crawl patterns
        val lowerBody = body.lowercase()
        if (lowerBody.length < MIN_BODY_LENGTH) return true

        return ANTI_CRAWL_PATTERNS.any { pattern -> lowerBody.contains(pattern) }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --tests "com.vibe.app.feature.agent.tool.web.AntiCrawlDetectorTest" 2>&1 | tail -10
```
Expected: All 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/feature/agent/tool/web/WebConstants.kt \
       app/src/test/kotlin/com/vibe/app/feature/agent/tool/web/AntiCrawlDetectorTest.kt
git commit -m "feat: add anti-crawl detection logic to WebConstants"
```

---

### Task 4: Implement WebViewContentExtractor

**Files:**
- Create: `app/src/main/kotlin/com/vibe/app/feature/agent/tool/web/WebViewContentExtractor.kt`

This is the core class that manages a hidden WebView, loads a URL, waits for page completion, injects the JS extraction pipeline, and returns structured content.

- [ ] **Step 1: Write WebViewContentExtractor**

```kotlin
package com.vibe.app.feature.agent.tool.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
    private val jsLibraries: String by lazy { loadJsLibraries() }

    suspend fun extract(url: String): Result<WebViewExtractionResult> = withContext(Dispatchers.Main) {
        try {
            withTimeout(WebConstants.WEBVIEW_TIMEOUT_MS) {
                doExtract(url)
            }
        } catch (e: Exception) {
            Log.w(TAG, "WebView extraction failed for: $url", e)
            Result.failure(e)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun doExtract(url: String): Result<WebViewExtractionResult> =
        suspendCancellableCoroutine { cont ->
            val webView = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadsImagesAutomatically = false
                settings.blockNetworkImage = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                settings.userAgentString = WebConstants.USER_AGENT
                settings.cacheMode = WebSettings.LOAD_DEFAULT
            }

            cont.invokeOnCancellation {
                webView.stopLoading()
                webView.destroy()
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, loadedUrl: String) {
                    // Inject JS libraries then extraction script
                    view.evaluateJavascript(jsLibraries) {
                        view.evaluateJavascript(loadExtractScript()) { rawResult ->
                            webView.destroy()
                            val result = parseResult(rawResult, url)
                            if (cont.isActive) cont.resume(result)
                        }
                    }
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    // Only handle main frame errors
                    if (request.isForMainFrame && cont.isActive) {
                        webView.destroy()
                        cont.resume(
                            Result.failure(
                                RuntimeException("WebView error: ${error.description} (${error.errorCode})")
                            )
                        )
                    }
                }
            }

            webView.loadUrl(url)
        }

    private fun parseResult(rawJson: String?, url: String): Result<WebViewExtractionResult> {
        if (rawJson == null || rawJson == "null") {
            return Result.failure(RuntimeException("JS extraction returned null"))
        }
        // evaluateJavascript wraps result in quotes and escapes inner quotes
        val jsonStr = if (rawJson.startsWith("\"") && rawJson.endsWith("\"")) {
            rawJson
                .substring(1, rawJson.length - 1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\/", "/")
                .replace("\\n", "\n")
                .replace("\\t", "\t")
        } else {
            rawJson
        }

        return try {
            val json = Json.parseToJsonElement(jsonStr) as JsonObject
            val error = json["error"]?.jsonPrimitive?.content
            if (error != null) {
                // Readability failed but we have fallback text
                val fallbackText = json["text"]?.jsonPrimitive?.content.orEmpty()
                if (fallbackText.isNotBlank()) {
                    val fallbackTitle = json["fallback"]?.jsonPrimitive?.content.orEmpty()
                    Result.success(
                        WebViewExtractionResult(
                            title = fallbackTitle,
                            content = truncate(fallbackText),
                            url = url,
                        )
                    )
                } else {
                    Result.failure(RuntimeException("Extraction failed: $error"))
                }
            } else {
                Result.success(
                    WebViewExtractionResult(
                        title = json["title"]?.jsonPrimitive?.content.orEmpty(),
                        content = truncate(json["content"]?.jsonPrimitive?.content.orEmpty()),
                        url = json["url"]?.jsonPrimitive?.content ?: url,
                        byline = json["byline"]?.jsonPrimitive?.content.orEmpty(),
                        excerpt = json["excerpt"]?.jsonPrimitive?.content.orEmpty(),
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(RuntimeException("Failed to parse extraction result: ${e.message}"))
        }
    }

    private fun truncate(text: String): String {
        return if (text.length > WebConstants.MAX_CONTENT_LENGTH) {
            text.take(WebConstants.MAX_CONTENT_LENGTH) + "\n... [truncated]"
        } else {
            text
        }
    }

    private fun loadJsLibraries(): String {
        val readability = context.assets.open("js/readability.js").bufferedReader().readText()
        val turndown = context.assets.open("js/turndown.js").bufferedReader().readText()
        val gfm = try {
            context.assets.open("js/turndown-plugin-gfm.js").bufferedReader().readText()
        } catch (_: Exception) {
            "" // GFM plugin is optional
        }
        return "$readability\n$turndown\n$gfm"
    }

    private fun loadExtractScript(): String {
        return context.assets.open("js/extract-content.js").bufferedReader().readText()
    }

    companion object {
        private const val TAG = "WebViewExtractor"
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
./gradlew :app:compileDebugKotlin 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/feature/agent/tool/web/WebViewContentExtractor.kt
git commit -m "feat: implement WebViewContentExtractor with Readability.js + Turndown.js"
```

---

### Task 5: Implement WebContentFetcher strategy (OkHttp → WebView fallback)

**Files:**
- Create: `app/src/main/kotlin/com/vibe/app/feature/agent/tool/web/WebContentFetcher.kt`

- [ ] **Step 1: Write WebContentFetcher**

```kotlin
package com.vibe.app.feature.agent.tool.web

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.withTimeout
import net.dankito.readability4j.Readability4J
import org.jsoup.Jsoup
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

data class FetchedContent(
    val title: String,
    val content: String,
    val url: String,
)

@Singleton
class WebContentFetcher @Inject constructor(
    @Named("web") private val httpClient: HttpClient,
    private val webViewExtractor: WebViewContentExtractor,
) {

    suspend fun fetch(url: String): Result<FetchedContent> {
        // Fast path: try OkHttp first
        val okHttpResult = tryOkHttp(url)
        if (okHttpResult != null) {
            return Result.success(okHttpResult)
        }

        // Slow path: fall back to WebView
        Log.d(TAG, "OkHttp path failed or blocked, falling back to WebView for: $url")
        return webViewExtractor.extract(url).map { result ->
            FetchedContent(
                title = result.title,
                content = result.content,
                url = result.url,
            )
        }
    }

    private suspend fun tryOkHttp(url: String): FetchedContent? {
        return try {
            withTimeout(WebConstants.TIMEOUT_MS) {
                val response = httpClient.get(url) {
                    header(HttpHeaders.UserAgent, WebConstants.USER_AGENT)
                    header(HttpHeaders.AcceptLanguage, WebConstants.ACCEPT_LANGUAGE)
                    header(HttpHeaders.Accept, WebConstants.ACCEPT)
                    header("Sec-Fetch-Dest", WebConstants.SEC_FETCH_DEST)
                    header("Sec-Fetch-Mode", WebConstants.SEC_FETCH_MODE)
                    header("Sec-Fetch-Site", WebConstants.SEC_FETCH_SITE)
                    header("Sec-Fetch-User", WebConstants.SEC_FETCH_USER)
                }

                val statusCode = response.status.value
                if (!response.status.isSuccess() && statusCode != 403 && statusCode != 503) {
                    // Hard failure (404, 500, etc.) — don't bother with WebView
                    return@withTimeout null
                }

                val responseContentType = response.contentType()
                if (responseContentType != null &&
                    !responseContentType.match(ContentType.Text.Html) &&
                    !responseContentType.match(ContentType.Text.Plain) &&
                    !responseContentType.match(ContentType.Application.Xml)
                ) {
                    return@withTimeout null
                }

                val html = response.bodyAsText()

                // Check for anti-crawl
                if (WebConstants.isAntiCrawlResponse(statusCode, html)) {
                    return@withTimeout null // Will fall back to WebView
                }

                // Extract content with Readability4J
                extractWithReadability(url, html)
            }
        } catch (e: Exception) {
            Log.d(TAG, "OkHttp fetch failed for $url: ${e.message}")
            null
        }
    }

    private fun extractWithReadability(url: String, html: String): FetchedContent? {
        // Try Readability4J
        try {
            val article = Readability4J(url, html).parse()
            val title = article.title.orEmpty().ifBlank { Jsoup.parse(html).title() }
            val text = article.textContent?.trim().orEmpty()
            if (text.isNotBlank()) {
                val truncated = if (text.length > WebConstants.MAX_CONTENT_LENGTH) {
                    text.take(WebConstants.MAX_CONTENT_LENGTH) + "\n... [truncated]"
                } else {
                    text
                }
                return FetchedContent(title = title, content = truncated, url = url)
            }
        } catch (_: Exception) {
            // Fall through to Jsoup
        }

        // Jsoup fallback
        val doc = Jsoup.parse(html)
        val title = doc.title()
        doc.select("script, style, nav, header, footer, aside, iframe, noscript, svg").remove()
        val body = doc.body() ?: return null
        var text = body.text()
        if (text.isBlank()) return null
        if (text.length > WebConstants.MAX_CONTENT_LENGTH) {
            text = text.take(WebConstants.MAX_CONTENT_LENGTH) + "\n... [truncated]"
        }
        return FetchedContent(title = title, content = text, url = url)
    }

    companion object {
        private const val TAG = "WebContentFetcher"
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
./gradlew :app:compileDebugKotlin 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/feature/agent/tool/web/WebContentFetcher.kt
git commit -m "feat: implement WebContentFetcher with OkHttp-first + WebView fallback"
```

---

### Task 6: Refactor FetchWebPageTool to use WebContentFetcher

**Files:**
- Modify: `app/src/main/kotlin/com/vibe/app/feature/agent/tool/FetchWebPageTool.kt`

- [ ] **Step 1: Replace FetchWebPageTool implementation**

Replace the entire file content:

```kotlin
package com.vibe.app.feature.agent.tool

import com.vibe.app.feature.agent.AgentTool
import com.vibe.app.feature.agent.AgentToolCall
import com.vibe.app.feature.agent.AgentToolContext
import com.vibe.app.feature.agent.AgentToolDefinition
import com.vibe.app.feature.agent.AgentToolResult
import com.vibe.app.feature.agent.tool.web.WebContentFetcher
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FetchWebPageTool @Inject constructor(
    private val webContentFetcher: WebContentFetcher,
) : AgentTool {

    override val definition = AgentToolDefinition(
        name = "fetch_web_page",
        description = "Fetch and extract the main text content from a web page. Returns clean Markdown-formatted content. Use after web_search to read full page content.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put("url", stringProp("The URL of the web page to fetch"))
                },
            )
            put("required", requiredFields("url"))
        },
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val url = call.arguments.requireString("url")

        return webContentFetcher.fetch(url).fold(
            onSuccess = { fetched ->
                call.result(buildJsonObject {
                    put("title", JsonPrimitive(fetched.title))
                    put("content", JsonPrimitive(fetched.content))
                    put("url", JsonPrimitive(fetched.url))
                })
            },
            onFailure = { e ->
                call.errorResult("Failed to fetch page: ${e.message}")
            },
        )
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
./gradlew :app:compileDebugKotlin 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/feature/agent/tool/FetchWebPageTool.kt
git commit -m "refactor: FetchWebPageTool delegates to WebContentFetcher with WebView fallback"
```

---

### Task 7: Add WebView fallback to WebSearchExecutor

**Files:**
- Modify: `app/src/main/kotlin/com/vibe/app/feature/agent/tool/web/WebSearchExecutor.kt`

Search engine pages are also increasingly blocking HTTP scraping. Add WebView fallback for search as well.

- [ ] **Step 1: Update WebSearchExecutor**

Replace the entire file content:

```kotlin
package com.vibe.app.feature.agent.tool.web

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class WebSearchExecutor @Inject constructor(
    @Named("web") private val httpClient: HttpClient,
    private val webViewExtractor: WebViewContentExtractor,
) {

    private val engines: List<WebSearchEngine> = listOf(
        BingSearchEngine(),
        BaiduSearchEngine(),
        GoogleSearchEngine(),
    )

    suspend fun search(query: String): Result<List<SearchResult>> {
        val errors = mutableListOf<String>()

        for (engine in engines) {
            // Try OkHttp first
            try {
                val results = trySearchWithOkHttp(engine, query)
                if (results.isNotEmpty()) {
                    Log.d(TAG, "${engine.name} (OkHttp) returned ${results.size} results for: $query")
                    return Result.success(results.take(MAX_RESULTS))
                }
                errors.add("${engine.name} (OkHttp): no results parsed")
            } catch (e: Exception) {
                Log.w(TAG, "${engine.name} OkHttp failed for query: $query", e)
                errors.add("${engine.name} (OkHttp): ${e.message}")
            }

            // Try WebView fallback
            try {
                val results = trySearchWithWebView(engine, query)
                if (results.isNotEmpty()) {
                    Log.d(TAG, "${engine.name} (WebView) returned ${results.size} results for: $query")
                    return Result.success(results.take(MAX_RESULTS))
                }
                errors.add("${engine.name} (WebView): no results parsed")
            } catch (e: Exception) {
                Log.w(TAG, "${engine.name} WebView failed for query: $query", e)
                errors.add("${engine.name} (WebView): ${e.message}")
            }
        }

        return Result.failure(
            RuntimeException("All search engines failed: ${errors.joinToString("; ")}")
        )
    }

    private suspend fun trySearchWithOkHttp(
        engine: WebSearchEngine,
        query: String,
    ): List<SearchResult> = withTimeout(WebConstants.TIMEOUT_MS) {
        val url = engine.buildSearchUrl(query)
        val response = httpClient.get(url) {
            header(HttpHeaders.UserAgent, WebConstants.USER_AGENT)
            header(HttpHeaders.AcceptLanguage, WebConstants.ACCEPT_LANGUAGE)
            header(HttpHeaders.Accept, WebConstants.ACCEPT)
            header("Sec-Fetch-Dest", WebConstants.SEC_FETCH_DEST)
            header("Sec-Fetch-Mode", WebConstants.SEC_FETCH_MODE)
            header("Sec-Fetch-Site", WebConstants.SEC_FETCH_SITE)
            header("Sec-Fetch-User", WebConstants.SEC_FETCH_USER)
        }
        if (!response.status.isSuccess()) {
            throw RuntimeException("HTTP ${response.status.value} from ${engine.name}")
        }
        val html = response.bodyAsText()
        engine.parseResults(html)
    }

    private suspend fun trySearchWithWebView(
        engine: WebSearchEngine,
        query: String,
    ): List<SearchResult> {
        val url = engine.buildSearchUrl(query)
        val result = webViewExtractor.extractRawHtml(url)
        val html = result.getOrThrow()
        return engine.parseResults(html)
    }

    companion object {
        private const val TAG = "WebSearchExecutor"
        private const val MAX_RESULTS = 5
    }
}
```

- [ ] **Step 2: Add `extractRawHtml` method to WebViewContentExtractor**

Add this method to `WebViewContentExtractor.kt` after the existing `extract` method:

```kotlin
    /**
     * Loads a URL in WebView and returns the raw rendered HTML (document.documentElement.outerHTML).
     * Used for search result pages where we need HTML for engine-specific parsing.
     */
    suspend fun extractRawHtml(url: String): Result<String> = withContext(Dispatchers.Main) {
        try {
            withTimeout(WebConstants.WEBVIEW_TIMEOUT_MS) {
                doExtractRawHtml(url)
            }
        } catch (e: Exception) {
            Log.w(TAG, "WebView raw HTML extraction failed for: $url", e)
            Result.failure(e)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun doExtractRawHtml(url: String): Result<String> =
        suspendCancellableCoroutine { cont ->
            val webView = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadsImagesAutomatically = false
                settings.blockNetworkImage = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                settings.userAgentString = WebConstants.USER_AGENT
                settings.cacheMode = WebSettings.LOAD_DEFAULT
            }

            cont.invokeOnCancellation {
                webView.stopLoading()
                webView.destroy()
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, loadedUrl: String) {
                    view.evaluateJavascript("document.documentElement.outerHTML") { rawResult ->
                        webView.destroy()
                        if (rawResult == null || rawResult == "null") {
                            if (cont.isActive) cont.resume(Result.failure(RuntimeException("Empty HTML")))
                        } else {
                            // Unescape the JS string
                            val html = if (rawResult.startsWith("\"") && rawResult.endsWith("\"")) {
                                rawResult
                                    .substring(1, rawResult.length - 1)
                                    .replace("\\\"", "\"")
                                    .replace("\\\\", "\\")
                                    .replace("\\/", "/")
                                    .replace("\\n", "\n")
                                    .replace("\\t", "\t")
                                    .replace("\\u003C", "<")
                                    .replace("\\u003E", ">")
                            } else {
                                rawResult
                            }
                            if (cont.isActive) cont.resume(Result.success(html))
                        }
                    }
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    if (request.isForMainFrame && cont.isActive) {
                        webView.destroy()
                        cont.resume(
                            Result.failure(
                                RuntimeException("WebView error: ${error.description} (${error.errorCode})")
                            )
                        )
                    }
                }
            }

            webView.loadUrl(url)
        }
```

- [ ] **Step 3: Verify compilation**

```bash
./gradlew :app:compileDebugKotlin 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/feature/agent/tool/web/WebSearchExecutor.kt \
       app/src/main/kotlin/com/vibe/app/feature/agent/tool/web/WebViewContentExtractor.kt
git commit -m "feat: add WebView fallback to WebSearchExecutor for search engine anti-crawl"
```

---

### Task 8: Update DI module

**Files:**
- Modify: `app/src/main/kotlin/com/vibe/app/di/AgentToolModule.kt`

No changes needed to `AgentToolModule` — `WebViewContentExtractor` and `WebContentFetcher` are both `@Singleton` classes with `@Inject constructor`, so Hilt auto-discovers them. `FetchWebPageTool` now depends on `WebContentFetcher` instead of `HttpClient`, and `WebSearchExecutor` now depends on `WebViewContentExtractor` — both satisfied by Hilt constructor injection.

- [ ] **Step 1: Verify full build**

```bash
./gradlew :app:assembleDebug 2>&1 | tail -15
```
Expected: BUILD SUCCESSFUL. Hilt code generation resolves all dependencies.

- [ ] **Step 2: Run all unit tests**

```bash
./gradlew :app:testDebugUnitTest 2>&1 | tail -15
```
Expected: All tests pass.

- [ ] **Step 3: Commit (if any fixes were needed)**

```bash
git add -u
git commit -m "fix: resolve any DI or compilation issues from WebView integration"
```

---

### Task 9: Update FetchWebPageTool description in agent system prompt

**Files:**
- Modify: `app/src/main/assets/agent-system-prompt.md`

- [ ] **Step 1: Check the current tool descriptions in the system prompt**

Read `app/src/main/assets/agent-system-prompt.md` and locate any mention of `fetch_web_page`. If the tool description there mentions specific limitations about JavaScript-rendered pages, update it to reflect the new WebView capability. If there's no specific tool documentation, skip this step.

- [ ] **Step 2: Commit if changes were made**

```bash
git add app/src/main/assets/agent-system-prompt.md
git commit -m "docs: update agent system prompt for WebView-enhanced fetch_web_page"
```

---

### Task 10: Manual verification on device

This task must be performed on a real Android device or emulator (API 29+).

- [ ] **Step 1: Install debug build**

```bash
./gradlew :app:installDebug
```

- [ ] **Step 2: Test FetchWebPageTool with a Cloudflare-protected site**

In the app chat, trigger the agent to fetch a page known to block HTTP scraping. Examples:
- A medium.com article (often has JS challenges)
- A site behind Cloudflare (e.g., many news sites)

Verify the agent returns Markdown-formatted content instead of an error.

- [ ] **Step 3: Test WebSearchTool fallback**

Trigger a web search query. Check Logcat for `WebSearchExecutor` logs:
- If OkHttp succeeds: should see `"Bing (OkHttp) returned N results"`
- If OkHttp fails and WebView kicks in: should see `"Bing (WebView) returned N results"`

- [ ] **Step 4: Test a normal non-protected page**

Verify the fast OkHttp path still works for simple pages (should not invoke WebView for pages that return content normally).

---

## Cleanup Note

After this integration is stable, consider removing the direct `readability4j` dependency from `app/build.gradle.kts` since the WebView path uses the JS Readability. However, the OkHttp fast path still uses it, so **keep it for now**.
