# WebSearchTool & FetchWebPageTool Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `web_search` and `fetch_web_page` agent tools that let the AI retrieve real-time information from the web during code generation.

**Architecture:** Two tools (`WebSearchTool`, `FetchWebPageTool`) backed by a search engine abstraction layer (`WebSearchEngine` interface + Bing/Google/Baidu implementations) and a fallback coordinator (`WebSearchExecutor`). The tools use Ktor HttpClient for HTTP and jsoup for HTML parsing. Both are already project dependencies.

**Tech Stack:** Kotlin, Ktor HttpClient (OkHttp engine), jsoup, Hilt DI, existing `AgentTool` interface

---

### Task 1: SearchResult data class

**Files:**
- Create: `app/src/main/kotlin/com/vibe/app/feature/agent/tool/web/SearchResult.kt`

- [ ] **Step 1: Create SearchResult data class**

```kotlin
package com.vibe.app.feature.agent.tool.web

data class SearchResult(
    val title: String,
    val snippet: String,
    val url: String,
)
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/feature/agent/tool/web/SearchResult.kt
git commit -m "feat(agent): add SearchResult data class for web search"
```

---

### Task 2: WebSearchEngine interface

**Files:**
- Create: `app/src/main/kotlin/com/vibe/app/feature/agent/tool/web/WebSearchEngine.kt`

- [ ] **Step 1: Create WebSearchEngine interface**

```kotlin
package com.vibe.app.feature.agent.tool.web

interface WebSearchEngine {
    val name: String
    fun buildSearchUrl(query: String): String
    fun parseResults(html: String): List<SearchResult>
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/feature/agent/tool/web/WebSearchEngine.kt
git commit -m "feat(agent): add WebSearchEngine interface"
```

---

### Task 3: BingSearchEngine implementation

**Files:**
- Create: `app/src/main/kotlin/com/vibe/app/feature/agent/tool/web/BingSearchEngine.kt`

- [ ] **Step 1: Implement BingSearchEngine**

```kotlin
package com.vibe.app.feature.agent.tool.web

import org.jsoup.Jsoup
import java.net.URLEncoder

class BingSearchEngine : WebSearchEngine {

    override val name: String = "Bing"

    override fun buildSearchUrl(query: String): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return "https://www.bing.com/search?q=$encoded&setlang=en"
    }

    override fun parseResults(html: String): List<SearchResult> {
        val doc = Jsoup.parse(html)
        return doc.select("li.b_algo").mapNotNull { element ->
            val titleEl = element.selectFirst("h2 a") ?: return@mapNotNull null
            val title = titleEl.text().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val url = titleEl.attr("href").takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val snippet = element.selectFirst("div.b_caption p")?.text().orEmpty()
            SearchResult(title = title, snippet = snippet, url = url)
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/feature/agent/tool/web/BingSearchEngine.kt
git commit -m "feat(agent): add BingSearchEngine implementation"
```

---

### Task 4: GoogleSearchEngine implementation

**Files:**
- Create: `app/src/main/kotlin/com/vibe/app/feature/agent/tool/web/GoogleSearchEngine.kt`

- [ ] **Step 1: Implement GoogleSearchEngine**

```kotlin
package com.vibe.app.feature.agent.tool.web

import org.jsoup.Jsoup
import java.net.URLEncoder

class GoogleSearchEngine : WebSearchEngine {

    override val name: String = "Google"

    override fun buildSearchUrl(query: String): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return "https://www.google.com/search?q=$encoded&hl=en"
    }

    override fun parseResults(html: String): List<SearchResult> {
        val doc = Jsoup.parse(html)
        return doc.select("div.g").mapNotNull { element ->
            val titleEl = element.selectFirst("h3") ?: return@mapNotNull null
            val title = titleEl.text().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val linkEl = element.selectFirst("a[href^=http]") ?: return@mapNotNull null
            val url = linkEl.attr("href").takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val snippet = element.selectFirst("div[data-sncf], div.VwiC3b, span.aCOpRe")?.text().orEmpty()
            SearchResult(title = title, snippet = snippet, url = url)
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/feature/agent/tool/web/GoogleSearchEngine.kt
git commit -m "feat(agent): add GoogleSearchEngine implementation"
```

---

### Task 5: BaiduSearchEngine implementation

**Files:**
- Create: `app/src/main/kotlin/com/vibe/app/feature/agent/tool/web/BaiduSearchEngine.kt`

- [ ] **Step 1: Implement BaiduSearchEngine**

```kotlin
package com.vibe.app.feature.agent.tool.web

import org.jsoup.Jsoup
import java.net.URLEncoder

class BaiduSearchEngine : WebSearchEngine {

    override val name: String = "Baidu"

    override fun buildSearchUrl(query: String): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return "https://www.baidu.com/s?wd=$encoded"
    }

    override fun parseResults(html: String): List<SearchResult> {
        val doc = Jsoup.parse(html)
        return doc.select("div.result, div.c-container").mapNotNull { element ->
            val titleEl = element.selectFirst("h3 a") ?: return@mapNotNull null
            val title = titleEl.text().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val url = titleEl.attr("href").takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val snippet = element.selectFirst("span.content-right_8Zs40, div.c-abstract, div.c-span-last")?.text().orEmpty()
            SearchResult(title = title, snippet = snippet, url = url)
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/feature/agent/tool/web/BaiduSearchEngine.kt
git commit -m "feat(agent): add BaiduSearchEngine implementation"
```

---

### Task 6: WebSearchExecutor fallback coordinator

**Files:**
- Create: `app/src/main/kotlin/com/vibe/app/feature/agent/tool/web/WebSearchExecutor.kt`

- [ ] **Step 1: Implement WebSearchExecutor**

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
import javax.inject.Singleton

@Singleton
class WebSearchExecutor @Inject constructor(
    private val httpClient: HttpClient,
) {

    private val engines: List<WebSearchEngine> = listOf(
        BingSearchEngine(),
        GoogleSearchEngine(),
        BaiduSearchEngine(),
    )

    suspend fun search(query: String): Result<List<SearchResult>> {
        val errors = mutableListOf<String>()

        for (engine in engines) {
            try {
                val results = withTimeout(TIMEOUT_MS) {
                    val url = engine.buildSearchUrl(query)
                    val response = httpClient.get(url) {
                        header(HttpHeaders.UserAgent, USER_AGENT)
                        header(HttpHeaders.AcceptLanguage, "en-US,en;q=0.9,zh-CN;q=0.8,zh;q=0.7")
                    }
                    if (!response.status.isSuccess()) {
                        throw RuntimeException("HTTP ${response.status.value} from ${engine.name}")
                    }
                    val html = response.bodyAsText()
                    engine.parseResults(html)
                }
                if (results.isNotEmpty()) {
                    Log.d(TAG, "${engine.name} returned ${results.size} results for: $query")
                    return Result.success(results.take(MAX_RESULTS))
                }
                errors.add("${engine.name}: no results parsed")
            } catch (e: Exception) {
                Log.w(TAG, "${engine.name} failed for query: $query", e)
                errors.add("${engine.name}: ${e.message}")
            }
        }

        return Result.failure(
            RuntimeException("All search engines failed: ${errors.joinToString("; ")}")
        )
    }

    companion object {
        private const val TAG = "WebSearchExecutor"
        private const val TIMEOUT_MS = 10_000L
        private const val MAX_RESULTS = 5
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/feature/agent/tool/web/WebSearchExecutor.kt
git commit -m "feat(agent): add WebSearchExecutor with Bing/Google/Baidu fallback"
```

---

### Task 7: Provide HttpClient for WebSearchExecutor via DI

The existing `NetworkClient` wraps an HttpClient configured for JSON API calls (ContentNegotiation, SSE, 5-min timeout, JSON Content-Type header). Web search needs a plain HttpClient without ContentNegotiation or default JSON headers, since we're fetching HTML. We'll add a `@Named("web")` HttpClient in `NetworkModule`.

**Files:**
- Modify: `app/src/main/kotlin/com/vibe/app/di/NetworkModule.kt`
- Modify: `app/src/main/kotlin/com/vibe/app/feature/agent/tool/web/WebSearchExecutor.kt`

- [ ] **Step 1: Add @Named("web") HttpClient provider in NetworkModule**

Add this import and method to `NetworkModule.kt`:

```kotlin
// Add imports:
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import javax.inject.Named

// Add method inside NetworkModule object:
@Provides
@Singleton
@Named("web")
fun provideWebHttpClient(
    @ApplicationContext context: Context,
): HttpClient = HttpClient(OkHttp.create {
    addInterceptor(ChuckerInterceptor.Builder(context).build())
}) {
    expectSuccess = false
    install(HttpTimeout) {
        requestTimeoutMillis = 15_000
        connectTimeoutMillis = 10_000
        socketTimeoutMillis = 10_000
    }
}
```

- [ ] **Step 2: Update WebSearchExecutor constructor to use @Named("web")**

Change the constructor parameter annotation:

```kotlin
import javax.inject.Named

@Singleton
class WebSearchExecutor @Inject constructor(
    @Named("web") private val httpClient: HttpClient,
) {
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/di/NetworkModule.kt \
       app/src/main/kotlin/com/vibe/app/feature/agent/tool/web/WebSearchExecutor.kt
git commit -m "feat(di): add @Named(\"web\") HttpClient for web search tools"
```

---

### Task 8: WebSearchTool

**Files:**
- Create: `app/src/main/kotlin/com/vibe/app/feature/agent/tool/WebSearchTool.kt`

- [ ] **Step 1: Implement WebSearchTool**

```kotlin
package com.vibe.app.feature.agent.tool

import com.vibe.app.feature.agent.AgentTool
import com.vibe.app.feature.agent.AgentToolCall
import com.vibe.app.feature.agent.AgentToolContext
import com.vibe.app.feature.agent.AgentToolDefinition
import com.vibe.app.feature.agent.AgentToolResult
import com.vibe.app.feature.agent.tool.web.WebSearchExecutor
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebSearchTool @Inject constructor(
    private val webSearchExecutor: WebSearchExecutor,
) : AgentTool {

    override val definition = AgentToolDefinition(
        name = "web_search",
        description = "Search the web for real-time information using search engines. Returns up to 5 results with title, snippet, and URL. Use fetch_web_page to read full page content.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put("query", stringProp("Search keywords"))
                },
            )
            put("required", requiredFields("query"))
        },
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val query = call.arguments.requireString("query")

        val result = webSearchExecutor.search(query)

        return result.fold(
            onSuccess = { results ->
                val output = buildJsonArray {
                    for (item in results) {
                        add(buildJsonObject {
                            put("title", JsonPrimitive(item.title))
                            put("snippet", JsonPrimitive(item.snippet))
                            put("url", JsonPrimitive(item.url))
                        })
                    }
                }
                call.result(output)
            },
            onFailure = { e ->
                call.errorResult(e.message ?: "Web search failed")
            },
        )
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/feature/agent/tool/WebSearchTool.kt
git commit -m "feat(agent): add WebSearchTool"
```

---

### Task 9: FetchWebPageTool

**Files:**
- Create: `app/src/main/kotlin/com/vibe/app/feature/agent/tool/FetchWebPageTool.kt`

- [ ] **Step 1: Implement FetchWebPageTool**

```kotlin
package com.vibe.app.feature.agent.tool

import com.vibe.app.feature.agent.AgentTool
import com.vibe.app.feature.agent.AgentToolCall
import com.vibe.app.feature.agent.AgentToolContext
import com.vibe.app.feature.agent.AgentToolDefinition
import com.vibe.app.feature.agent.AgentToolResult
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.jsoup.Jsoup
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class FetchWebPageTool @Inject constructor(
    @Named("web") private val httpClient: HttpClient,
) : AgentTool {

    override val definition = AgentToolDefinition(
        name = "fetch_web_page",
        description = "Fetch and extract the main text content from a web page. Use after web_search to read full page content.",
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

        return try {
            val (title, content) = withTimeout(TIMEOUT_MS) {
                val response = httpClient.get(url) {
                    header(HttpHeaders.UserAgent, USER_AGENT)
                    header(HttpHeaders.AcceptLanguage, "en-US,en;q=0.9,zh-CN;q=0.8,zh;q=0.7")
                }
                if (!response.status.isSuccess()) {
                    throw RuntimeException("HTTP ${response.status.value}")
                }
                val responseContentType = response.contentType()
                if (responseContentType != null &&
                    !responseContentType.match(ContentType.Text.Html) &&
                    !responseContentType.match(ContentType.Text.Plain) &&
                    !responseContentType.match(ContentType.Application.Xml)
                ) {
                    throw RuntimeException("URL does not point to an HTML page (content-type: $responseContentType)")
                }
                val html = response.bodyAsText()
                extractContent(html)
            }

            call.result(buildJsonObject {
                put("title", JsonPrimitive(title))
                put("content", JsonPrimitive(content))
                put("url", JsonPrimitive(url))
            })
        } catch (e: Exception) {
            call.errorResult("Failed to fetch page: ${e.message}")
        }
    }

    private fun extractContent(html: String): Pair<String, String> {
        val doc = Jsoup.parse(html)
        val title = doc.title()

        // Remove non-content elements
        doc.select("script, style, nav, header, footer, aside, iframe, noscript, svg").remove()

        val body = doc.body() ?: return Pair(title, "")
        var text = body.text()

        // Truncate to max length
        if (text.length > MAX_CONTENT_LENGTH) {
            text = text.take(MAX_CONTENT_LENGTH) + "\n... [truncated]"
        }

        return Pair(title, text)
    }

    companion object {
        private const val TIMEOUT_MS = 10_000L
        private const val MAX_CONTENT_LENGTH = 8_000
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/feature/agent/tool/FetchWebPageTool.kt
git commit -m "feat(agent): add FetchWebPageTool"
```

---

### Task 10: Register tools in Hilt DI

**Files:**
- Modify: `app/src/main/kotlin/com/vibe/app/di/AgentToolModule.kt`

- [ ] **Step 1: Add bindings for WebSearchTool and FetchWebPageTool**

Add these imports at the top of `AgentToolModule.kt`:

```kotlin
import com.vibe.app.feature.agent.tool.WebSearchTool
import com.vibe.app.feature.agent.tool.FetchWebPageTool
```

Add these two lines inside the `AgentToolModule` abstract class, after the existing bindings:

```kotlin
@Binds @IntoSet abstract fun bindWebSearch(tool: WebSearchTool): AgentTool
@Binds @IntoSet abstract fun bindFetchWebPage(tool: FetchWebPageTool): AgentTool
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/di/AgentToolModule.kt
git commit -m "feat(di): register WebSearchTool and FetchWebPageTool"
```

---

### Task 11: Update agent system prompt

**Files:**
- Modify: `app/src/main/assets/agent-system-prompt.md`

- [ ] **Step 1: Add web search guidance to system prompt**

Add the following section after the `## Network Access (Jsoup)` section (before `## UI Tips`):

```markdown
## Web Search & Page Fetching

You have access to two tools for retrieving real-time information from the internet:

- **web_search** — Search the web by keywords. Returns up to 5 results with title, snippet, and URL.
- **fetch_web_page** — Fetch the full text content of a specific URL.

**When to use:**
- You need current/real-time data (e.g. latest API docs, current prices, live scores)
- The user asks about unfamiliar concepts, game rules, or specific implementation patterns you are unsure about
- You need to verify facts or check specific technical details

**When NOT to use:**
- Basic programming knowledge you already know well (Java syntax, Android APIs, common patterns)
- Information already provided in this system prompt or the project files

**Workflow:** Call `web_search` first → review results → call `fetch_web_page` on relevant URLs if you need more detail.
```

- [ ] **Step 2: Verify the file is well-formed**

Run: `wc -l app/src/main/assets/agent-system-prompt.md`
Expected: line count increased by ~15 lines from current 200

- [ ] **Step 3: Commit**

```bash
git add app/src/main/assets/agent-system-prompt.md
git commit -m "docs(agent): add web search tool guidance to system prompt"
```

---

### Task 12: Build and verify full compilation

**Files:** None (verification only)

- [ ] **Step 1: Run full debug build**

Run: `./gradlew assembleDebug 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Verify tool count in registry**

Run: `grep -c '@Binds @IntoSet' app/src/main/kotlin/com/vibe/app/di/AgentToolModule.kt`
Expected: 18 (16 existing + 2 new)

- [ ] **Step 3: Final commit (if any fixups needed)**

Only commit if build issues required fixes in previous tasks.
