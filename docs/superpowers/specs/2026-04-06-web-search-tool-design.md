# WebSearchTool & FetchWebPageTool Design Spec

## Overview

Add two agent tools to enable real-time web information retrieval:
- **`web_search`** — query search engines, return top 5 results (title + snippet + URL)
- **`fetch_web_page`** — fetch a URL, extract plain text content

Typical flow: model calls `web_search` to find relevant pages, then `fetch_web_page` to read specific ones.

## Decisions

| Decision | Choice |
|----------|--------|
| Search mechanism | HTTP + HTML parsing (no API keys) |
| Result granularity | Two-step: search list + on-demand page fetch |
| Engine fallback | Fixed priority: Bing → Google → Baidu |
| Page content format | Plain text extraction (no structure) |
| Result count | Fixed 5 results |

## Tool Definitions

### web_search

- **Name**: `web_search`
- **Description**: Search the web for real-time information using search engines
- **Parameters**:
  - `query` (string, required) — search keywords
- **Returns**: JSON array, each item: `{ "title", "snippet", "url" }`, up to 5 items
- **Error**: returns `errorResult` with reason if all engines fail

### fetch_web_page

- **Name**: `fetch_web_page`
- **Description**: Fetch and extract the main text content from a web page
- **Parameters**:
  - `url` (string, required) — target page URL
- **Returns**: JSON object `{ "title", "content", "url" }`
  - `content` is plain text, max 8000 characters
- **Errors**:
  - Network failure/timeout → `errorResult("Failed to fetch page: {reason}")`
  - Non-HTML response → `errorResult("URL does not point to an HTML page")`

## Search Engine Abstraction

### Interface

```kotlin
interface WebSearchEngine {
    val name: String
    fun buildSearchUrl(query: String): String
    fun parseResults(html: String): List<SearchResult>
}

data class SearchResult(
    val title: String,
    val snippet: String,
    val url: String
)
```

### Implementations

- **BingSearchEngine** — URL: `https://www.bing.com/search?q=...`, parse `li.b_algo`
- **GoogleSearchEngine** — URL: `https://www.google.com/search?q=...`, parse `div.g`
- **BaiduSearchEngine** — URL: `https://www.baidu.com/s?wd=...`, parse `div.result`

### WebSearchExecutor (fallback coordinator)

```kotlin
class WebSearchExecutor {
    private val engines: List<WebSearchEngine> = listOf(
        BingSearchEngine(), GoogleSearchEngine(), BaiduSearchEngine()
    )

    suspend fun search(query: String): List<SearchResult>
}
```

Fallback logic:
1. Try each engine in order
2. HTTP GET with browser User-Agent, 10s timeout
3. Parse HTML via engine's `parseResults`
4. If >= 1 result, return top 5
5. On failure (network error / timeout / empty results), log and try next engine
6. All failed → throw or return error

### FetchWebPageTool content extraction

1. HTTP GET target URL (10s timeout, browser User-Agent)
2. Parse with jsoup
3. Extract `<title>`
4. Remove non-content tags: `<script>`, `<style>`, `<nav>`, `<header>`, `<footer>`, `<aside>`
5. Extract `<body>.text()` as plain text
6. Truncate to 8000 characters
7. Return `{ title, content, url }`

## File Structure

### New files

```
app/src/main/kotlin/com/vibe/app/feature/agent/tool/
├── WebSearchTool.kt
├── FetchWebPageTool.kt
└── web/
    ├── SearchResult.kt
    ├── WebSearchEngine.kt
    ├── BingSearchEngine.kt
    ├── GoogleSearchEngine.kt
    ├── BaiduSearchEngine.kt
    └── WebSearchExecutor.kt
```

### Modified files

- **`AgentToolModule.kt`** — add `@Binds @IntoSet` for both tools
- **`agent-system-prompt.md`** — add tool usage guidance

## DI Integration

- `WebSearchExecutor` injected into `WebSearchTool` via `@Inject constructor`
- `WebSearchExecutor` holds fixed engine list internally
- `FetchWebPageTool` is standalone, uses Ktor HttpClient
- Reuse existing `NetworkClient` HttpClient or create lightweight instance in tools

## System Prompt Addition

Add to `agent-system-prompt.md`:

> You have access to `web_search` and `fetch_web_page` tools for retrieving real-time information from the internet. Use `web_search` when you need current data, unfamiliar concepts, or specific implementation details you are unsure about. If a search result looks relevant, use `fetch_web_page` to get the full page content. Do not search for basic programming knowledge you already know well.
