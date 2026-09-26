# Phase 5: Web 搜索 Provider 化与内容管理 实施计划

> **执行者须知(任何模型/会话通用):**
> 1. 开工前先读 `00-progress.md`,确认 **Phase 2 已完成**(本 phase 依赖其 `WebFailureKind` 与 `EngineCircuitBreaker`)及本 phase 当前进度;
> 2. 本文引用的 `file:line` 基于 `dev@be1f944`,代码可能已漂移——动手前用 grep 重新定位;
> 3. 每完成一个 Task:勾选本文 checkbox、更新 `00-progress.md` 状态表、按"验证"节跑测试、独立 commit;
> 4. 偏离计划时,在文末"实施记录"追加说明,不要静默偏离;
> 5. 建议使用 `superpowers:executing-plans` 或 `superpowers:subagent-driven-development` 执行。

**目标:** 搜索后端 Provider 化——正式搜索 API(博查/Tavily/Brave/SearXNG)优先、内置 SERP 爬取兜底,用户可在设置页配置;`fetch_web_page` 结果落盘分页,消灭一次性 8K 内容灌入 context 的问题。

**评审依据:** `docs/optimization-review-2026-07.md` §2.2 A(正式搜索 API)/ C2(fetch 分页)/ C3(落盘 + 按需读取)。

**前置依赖:** Phase 2 Task 2.6/2.7(`WebFailureKind`、`EngineCircuitBreaker`、`WebSearchExecutor` 的结构化失败)。若接手时发现 Phase 2 实际产出的类型与本文假设不符,以代码现实为准调整映射层(见 Task 5.1 集成契约),并记入"实施记录"。

**涉及模块:** `feature/agent/tool/web/`(新增 `provider/` 子包)、`feature/agent/tool/`(两个工具)、`data/datastore/`、`data/repository/`、`presentation/ui/setting/`、`feature/project/`(listFiles 排除)、`app/src/main/assets/agent-system-prompt.md`。

**已核实的代码现状(2026-07-03):**

| 事实 | 位置 |
|------|------|
| `WebSearchExecutor.search(query): Result<List<SearchResult>>`,三引擎硬编码顺序降级,`MAX_RESULTS = 5` | `WebSearchExecutor.kt:12-50` |
| `SearchResult(title, snippet, url)` 已存在 | `feature/agent/tool/web/SearchResult.kt` |
| `WebSearchTool` 直接注入 `WebSearchExecutor`;成功返回 JSON **数组** | `WebSearchTool.kt:16-57` |
| `FetchWebPageTool` 注入 `WebContentFetcher`,只有 `url` 一个参数,返回 `{title, content, url}` | `FetchWebPageTool.kt:15-50` |
| `WebViewContentExtractor.extract(url): Result<WebViewExtractionResult>`,内容截断 8000 字符 | `WebViewContentExtractor.kt:56,174-180`、`WebConstants.kt:11` |
| 工具注册:`@Binds @IntoSet`,无条件 | `di/AgentToolModule.kt:59-60` |
| 工作区根 = `projects/{id}/app`;`resolveFile` 有路径逃逸保护;`listFiles()` 仅跳过 `build` 目录 | `DefaultProjectWorkspace.kt:44-51,67-75` |
| 构建管线只读 `src/main/java|res|assets` + `build/gen` → **rootDir 下非 src 目录不会进 APK** | `build-engine/.../BuildWorkspace.kt:46-60,105-112` |
| `read_project_file` 支持 `start_line`/`end_line`(1-based,-1=EOF),返回 `total_lines` | `FileTools.kt:98-111` |
| DataStore 模式:`SettingDataSource(Impl)` + `SettingRepository`,preference key 常量 | `data/datastore/SettingDataSourceImpl.kt` |
| Ktor:`NetworkClient` 单例,`ContentNegotiation(json)` + 5min 超时(Phase 1 会分级) | `data/network/NetworkClient.kt` |
| 工具参数助手:`JsonElement.requireString(key)` / `optionalInt` / `optionalBoolean`、`call.result(JsonElement)` / `call.errorResult(String)`、`stringProp`/`intProp`/`requiredFields` | `AgentToolExtensions.kt` |
| 测试:JUnit4 + `kotlinx.coroutines.test.runTest`,已有 `FakeProjectManager`/`FakeWorkspace`;`app/src/test/.../tool/web/` 目录已存在(空) | `app/src/test/kotlin/com/vibe/app/feature/agent/tool/` |

---

## Task 5.1: `WebSearchProvider` 抽象与 `BuiltInSerpProvider`

行为不变的重构:把"搜索"从 `WebSearchExecutor` 具体类后面抽象出来,现有 SERP 爬取变成第一个 provider 实现。

**改动文件:**
- Create: `app/src/main/kotlin/com/vibe/app/feature/agent/tool/web/provider/WebSearchProvider.kt`
- Create: `app/src/main/kotlin/com/vibe/app/feature/agent/tool/web/provider/BuiltInSerpProvider.kt`
- Create: `app/src/main/kotlin/com/vibe/app/feature/agent/tool/web/provider/SearchProviderFactory.kt`
- Modify: `app/src/main/kotlin/com/vibe/app/feature/agent/tool/WebSearchTool.kt`
- Test: `app/src/test/kotlin/com/vibe/app/feature/agent/tool/web/BuiltInSerpProviderTest.kt`
- Test: `app/src/test/kotlin/com/vibe/app/feature/agent/tool/WebSearchToolTest.kt`

**接口契约(后续 Task 全部依赖,不得改名):**

```kotlin
package com.vibe.app.feature.agent.tool.web.provider

import com.vibe.app.feature.agent.tool.web.SearchResult
import com.vibe.app.feature.agent.tool.web.WebFailureKind // Phase 2 产物

sealed interface SearchOutcome {
    data class Success(val results: List<SearchResult>) : SearchOutcome
    data class Failure(val kind: WebFailureKind, val detail: String) : SearchOutcome
}

interface WebSearchProvider {
    /** Stable id: "built_in" / "bocha" / "tavily" / "brave" / "searxng". */
    val id: String
    suspend fun search(query: String, maxResults: Int): SearchOutcome
}

/** What the tool actually uses. [notice] is set when factory fell back due to misconfig. */
data class ActiveSearch(
    val provider: WebSearchProvider,
    val notice: String? = null,
)
```

**与 Phase 2 的集成契约:** 本文假设 Phase 2 后 `WebSearchExecutor` 的失败以 `WebSearchException(kind: WebFailureKind, message: String)` 形式出现在 `Result.failure` 中。动手前先 grep `WebSearchException`/`WebFailureKind` 确认实际形态;若不同,只需调整 `BuiltInSerpProvider.mapFailure` 一处。

- [ ] **Step 1: 写失败映射的单元测试(先失败)**

```kotlin
// app/src/test/kotlin/com/vibe/app/feature/agent/tool/web/BuiltInSerpProviderTest.kt
package com.vibe.app.feature.agent.tool.web

import com.vibe.app.feature.agent.tool.web.provider.SearchOutcome
import com.vibe.app.feature.agent.tool.web.provider.mapSerpFailure
import org.junit.Assert.assertEquals
import org.junit.Test

class BuiltInSerpProviderTest {

    @Test
    fun `WebSearchException maps to its own kind`() {
        val outcome = mapSerpFailure(WebSearchException(WebFailureKind.BLOCKED, "Bing: captcha detected"))
        assertEquals(WebFailureKind.BLOCKED, (outcome as SearchOutcome.Failure).kind)
        assertEquals("Bing: captcha detected", outcome.detail)
    }

    @Test
    fun `generic exception maps to NETWORK_ERROR`() {
        val outcome = mapSerpFailure(RuntimeException("boom"))
        assertEquals(WebFailureKind.NETWORK_ERROR, (outcome as SearchOutcome.Failure).kind)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.vibe.app.feature.agent.tool.web.BuiltInSerpProviderTest"`
Expected: FAIL(`mapSerpFailure` 不存在)

- [ ] **Step 3: 实现接口文件与 BuiltInSerpProvider**

```kotlin
// provider/BuiltInSerpProvider.kt
package com.vibe.app.feature.agent.tool.web.provider

import com.vibe.app.feature.agent.tool.web.WebFailureKind
import com.vibe.app.feature.agent.tool.web.WebSearchException
import com.vibe.app.feature.agent.tool.web.WebSearchExecutor
import javax.inject.Inject
import javax.inject.Singleton

/** Wraps the existing three-engine SERP scraping (Bing/Baidu/Google + circuit breaker). */
@Singleton
class BuiltInSerpProvider @Inject constructor(
    private val executor: WebSearchExecutor,
) : WebSearchProvider {

    override val id: String = "built_in"

    override suspend fun search(query: String, maxResults: Int): SearchOutcome =
        executor.search(query).fold(
            onSuccess = { SearchOutcome.Success(it.take(maxResults)) },
            onFailure = { mapSerpFailure(it) },
        )
}

internal fun mapSerpFailure(e: Throwable): SearchOutcome.Failure = when (e) {
    is WebSearchException -> SearchOutcome.Failure(e.kind, e.message ?: "search failed")
    else -> SearchOutcome.Failure(WebFailureKind.NETWORK_ERROR, e.message ?: "search failed")
}
```

```kotlin
// provider/SearchProviderFactory.kt — 本 Task 的最小版,Task 5.4 扩展内部逻辑但不改签名
package com.vibe.app.feature.agent.tool.web.provider

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SearchProviderFactory @Inject constructor(
    private val builtIn: BuiltInSerpProvider,
) {
    suspend fun active(): ActiveSearch = ActiveSearch(builtIn)
}
```

- [ ] **Step 4: 改造 `WebSearchTool`**

返回结构从 JSON 数组改为对象 `{"results": [...], "notice"?: "..."}`(为 5.4 的回落提示预留;Phase 2.5 的裁剪分支是整体占位替换,不受形状影响):

```kotlin
// WebSearchTool.kt — execute() 与 description 替换为:
override val definition = AgentToolDefinition(
    name = "web_search",
    description = "Search the web for real-time information. Returns up to 5 results " +
        "as {results: [{title, snippet, url}]}. Use fetch_web_page to read full page content.",
    inputSchema = /* 不变 */,
)

override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
    val query = call.arguments.requireString("query")
    val active = searchProviderFactory.active()
    return when (val outcome = active.provider.search(query, MAX_RESULTS)) {
        is SearchOutcome.Success -> call.result(buildJsonObject {
            active.notice?.let { put("notice", JsonPrimitive(it)) }
            put("results", buildJsonArray {
                for (item in outcome.results) add(buildJsonObject {
                    put("title", JsonPrimitive(item.title))
                    put("snippet", JsonPrimitive(item.snippet))
                    put("url", JsonPrimitive(item.url))
                })
            })
        })
        is SearchOutcome.Failure -> call.errorResult(
            "[${outcome.kind}] ${outcome.detail}" + when (outcome.kind) {
                WebFailureKind.BLOCKED ->
                    " (Search engines blocked the request. The user can configure a search API " +
                        "in Settings > Search Service for reliable results.)"
                else -> ""
            }
        )
    }
}

companion object { private const val MAX_RESULTS = 5 }
```

构造函数注入改为 `private val searchProviderFactory: SearchProviderFactory`。`WebSearchExecutor` 不再被工具直接引用(仅被 `BuiltInSerpProvider` 使用)。

- [ ] **Step 5: 写 WebSearchTool 的 fake-provider 测试**

```kotlin
// app/src/test/kotlin/com/vibe/app/feature/agent/tool/WebSearchToolTest.kt
// 用一个返回固定 SearchOutcome 的 FakeSearchProviderFactory(直接继承/构造 SearchProviderFactory
// 不可行——它是 final class,因此把 WebSearchTool 的依赖声明为 SearchProviderFactory 并在测试中
// 用真实 factory + fake BuiltInSerpProvider 不划算。做法:给 SearchProviderFactory 加 open 不必要,
// 改为让测试直接构造 ActiveSearch 并抽一个内部函数:
//   internal fun renderSearchOutcome(call, active, outcome): AgentToolResult
// WebSearchTool.execute 调它;测试只测 renderSearchOutcome 的 JSON 形状与错误文案。
@Test
fun `success renders results object`() { /* 构造 Success(listOf(SearchResult("t","s","u"))),断言 payload 含 results 数组与字段 */ }

@Test
fun `blocked failure appends settings hint`() { /* Failure(BLOCKED,"x") → errorResult 文本含 "[BLOCKED]" 与 "Settings > Search Service" */ }
```

(测试写成对 `renderSearchOutcome` 纯函数的断言,完整代码由执行者按上述两条用例展开——输入输出在本 Task 已完全定义。)

- [ ] **Step 6: 全部测试通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.vibe.app.feature.agent.tool.web.*" --tests "com.vibe.app.feature.agent.tool.WebSearchToolTest"`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/feature/agent/tool/web/provider/ \
  app/src/main/kotlin/com/vibe/app/feature/agent/tool/WebSearchTool.kt \
  app/src/test/kotlin/com/vibe/app/feature/agent/tool/
git commit -m "refactor(web): extract WebSearchProvider abstraction, wrap SERP scraping as BuiltInSerpProvider (opt task 5.1)"
```

**验收标准:** 搜索行为与重构前一致(仍走三引擎爬取);`WebSearchTool` 不再 import `WebSearchExecutor`;新增测试全绿。

---

## Task 5.2: `BochaSearchProvider` + `TavilySearchProvider`

**改动文件:**
- Create: `app/src/main/kotlin/com/vibe/app/feature/agent/tool/web/provider/BochaSearchProvider.kt`
- Create: `app/src/main/kotlin/com/vibe/app/feature/agent/tool/web/provider/TavilySearchProvider.kt`
- Test: `app/src/test/kotlin/com/vibe/app/feature/agent/tool/web/ApiProviderParsingTest.kt`

**接口契约:** 两个类实现 `WebSearchProvider`;API key 由 Task 5.4 的 factory 在构造时传入——因此本 Task 的类**不用 @Inject 单例**,而是普通类:`class BochaSearchProvider(private val apiKey: String, private val networkClient: NetworkClient) : WebSearchProvider`。解析逻辑是纯函数,供 TDD:

```kotlin
internal fun parseBochaResponse(body: String): List<SearchResult>
internal fun parseTavilyResponse(body: String): List<SearchResult>
```

> **外部 API 字段以真实响应为准**:下述字段映射来自两家当前公开文档;实现时先用 curl(或"测试连接"按钮,Task 5.4)打一发真实请求核对字段名,不符则改 DTO 并更新 fixture,记入"实施记录"。

- [ ] **Step 1: 写解析测试(先失败)**

```kotlin
// ApiProviderParsingTest.kt
package com.vibe.app.feature.agent.tool.web

import com.vibe.app.feature.agent.tool.web.provider.parseBochaResponse
import com.vibe.app.feature.agent.tool.web.provider.parseTavilyResponse
import org.junit.Assert.assertEquals
import org.junit.Test

class ApiProviderParsingTest {

    @Test
    fun `bocha response parses name summary url`() {
        val body = """
        {"code":200,"data":{"webPages":{"value":[
          {"name":"Kotlin 官网","summary":"Kotlin 是一门现代语言","url":"https://kotlinlang.org"},
          {"name":"第二条","snippet":"snippet 兜底","url":"https://example.com"}
        ]}}}
        """.trimIndent()
        val results = parseBochaResponse(body)
        assertEquals(2, results.size)
        assertEquals("Kotlin 官网", results[0].title)
        assertEquals("Kotlin 是一门现代语言", results[0].snippet)
        assertEquals("https://kotlinlang.org", results[0].url)
        assertEquals("snippet 兜底", results[1].snippet) // summary 缺失时用 snippet
    }

    @Test
    fun `tavily response parses title content url`() {
        val body = """
        {"query":"q","results":[
          {"title":"T1","content":"C1","url":"https://a.com","score":0.9}
        ]}
        """.trimIndent()
        val results = parseTavilyResponse(body)
        assertEquals(1, results.size)
        assertEquals("T1", results[0].title)
        assertEquals("C1", results[0].snippet)
    }

    @Test
    fun `malformed body returns empty list`() {
        assertEquals(0, parseBochaResponse("not json").size)
        assertEquals(0, parseTavilyResponse("{}").size)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.vibe.app.feature.agent.tool.web.ApiProviderParsingTest"`
Expected: FAIL(函数不存在)

- [ ] **Step 3: 实现两个 provider**

```kotlin
// BochaSearchProvider.kt
package com.vibe.app.feature.agent.tool.web.provider

import com.vibe.app.data.network.NetworkClient
import com.vibe.app.feature.agent.tool.web.SearchResult
import com.vibe.app.feature.agent.tool.web.WebFailureKind
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class BochaSearchProvider(
    private val apiKey: String,
    private val networkClient: NetworkClient,
) : WebSearchProvider {

    override val id: String = "bocha"

    override suspend fun search(query: String, maxResults: Int): SearchOutcome = apiSearch {
        val response = networkClient().post("https://api.bochaai.com/v1/web-search") {
            header("Authorization", "Bearer $apiKey")
            timeout { requestTimeoutMillis = API_TIMEOUT_MS }
            setBody(BochaRequest(query = query, count = maxResults))
        }
        response.status.value to response.bodyAsText()
    }.mapBody(::parseBochaResponse)

    @Serializable
    private data class BochaRequest(val query: String, val count: Int, val summary: Boolean = true)
}

@Serializable internal data class BochaPage(val name: String? = null, val summary: String? = null, val snippet: String? = null, val url: String? = null)
@Serializable internal data class BochaPages(val value: List<BochaPage> = emptyList())
@Serializable internal data class BochaData(val webPages: BochaPages? = null)
@Serializable internal data class BochaResponse(val data: BochaData? = null)

internal fun parseBochaResponse(body: String): List<SearchResult> = runCatching {
    Json { ignoreUnknownKeys = true; isLenient = true }
        .decodeFromString<BochaResponse>(body)
        .data?.webPages?.value.orEmpty()
        .mapNotNull { p ->
            val url = p.url ?: return@mapNotNull null
            SearchResult(
                title = p.name.orEmpty(),
                snippet = p.summary ?: p.snippet ?: "",
                url = url,
            )
        }
}.getOrDefault(emptyList())
```

```kotlin
// TavilySearchProvider.kt — 同构,要点:
// POST https://api.tavily.com/search
// body: {"api_key": apiKey, "query": query, "max_results": maxResults, "include_answer": false}
// DTO: TavilyResult(title/content/url), TavilyResponse(results)
// parseTavilyResponse 与 parseBochaResponse 同模式(runCatching + orEmpty + mapNotNull)
```

两家共用的 HTTP 结果 → `SearchOutcome` 骨架(放在 `WebSearchProvider.kt` 同文件或新建 `ApiSearchSupport.kt`):

```kotlin
internal const val API_TIMEOUT_MS = 10_000L

internal data class ApiHttpResult(val status: Int, val body: String)

/** Runs [block], mapping exceptions/status codes to SearchOutcome.Failure. */
internal suspend fun apiSearch(block: suspend () -> Pair<Int, String>): ApiSearchStep = try {
    val (status, body) = block()
    when {
        status == 401 || status == 403 -> ApiSearchStep.Fail(
            WebFailureKind.BLOCKED,
            "API key rejected (HTTP $status). Check Settings > Search Service.",
        )
        status == 429 -> ApiSearchStep.Fail(WebFailureKind.BLOCKED, "Rate limited / quota exhausted (HTTP 429).")
        status !in 200..299 -> ApiSearchStep.Fail(WebFailureKind.NETWORK_ERROR, "HTTP $status")
        else -> ApiSearchStep.Body(body)
    }
} catch (e: io.ktor.client.plugins.HttpRequestTimeoutException) {
    ApiSearchStep.Fail(WebFailureKind.TIMEOUT, "Search API timed out after ${API_TIMEOUT_MS / 1000}s")
} catch (e: java.io.IOException) {
    ApiSearchStep.Fail(WebFailureKind.NETWORK_ERROR, e.message ?: "network error")
}

internal sealed interface ApiSearchStep {
    data class Body(val body: String) : ApiSearchStep
    data class Fail(val kind: WebFailureKind, val detail: String) : ApiSearchStep
}

internal fun ApiSearchStep.mapBody(parse: (String) -> List<SearchResult>): SearchOutcome = when (this) {
    is ApiSearchStep.Fail -> SearchOutcome.Failure(kind, detail)
    is ApiSearchStep.Body -> parse(body).let {
        if (it.isEmpty()) SearchOutcome.Failure(WebFailureKind.NO_RESULTS, "API returned no results")
        else SearchOutcome.Success(it)
    }
}
```

- [ ] **Step 4: 测试通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.vibe.app.feature.agent.tool.web.ApiProviderParsingTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/feature/agent/tool/web/provider/ app/src/test/
git commit -m "feat(web): add Bocha and Tavily search providers (opt task 5.2)"
```

**验收标准:** 解析测试全绿;两个 provider 的失败路径(401/403/429/超时/断网/空结果)都映射到明确的 `WebFailureKind`,401/403 的 detail 含设置页指引。

---

## Task 5.3: `BraveSearchProvider` + `SearxngSearchProvider`

**改动文件:**
- Create: `.../web/provider/BraveSearchProvider.kt`
- Create: `.../web/provider/SearxngSearchProvider.kt`
- Test: 追加用例到 `ApiProviderParsingTest.kt`

- [ ] **Step 1: 追加解析测试(先失败)**

```kotlin
@Test
fun `brave response parses web results`() {
    val body = """{"web":{"results":[{"title":"B1","description":"D1","url":"https://b.com"}]}}"""
    val results = parseBraveResponse(body)
    assertEquals(1, results.size)
    assertEquals("D1", results[0].snippet)
}

@Test
fun `searxng response parses results`() {
    val body = """{"results":[{"title":"S1","content":"C1","url":"https://s.com"}]}"""
    val results = parseSearxngResponse(body)
    assertEquals("S1", results[0].title)
}
```

- [ ] **Step 2: 确认失败后实现**

```kotlin
// BraveSearchProvider.kt — 要点:
// GET https://api.search.brave.com/res/v1/web/search?q=<urlencoded>&count=<maxResults>
//   header("X-Subscription-Token", apiKey); header("Accept", "application/json")
// DTO: BraveItem(title/description/url), BraveWeb(results), BraveResponse(web)
// parseBraveResponse: web.results → SearchResult(title, description, url)

// SearxngSearchProvider.kt — 要点:
// class SearxngSearchProvider(private val baseUrl: String, private val networkClient: NetworkClient)
// GET "${baseUrl.trimEnd('/')}/search?q=<urlencoded>&format=json"
// DTO: SearxngItem(title/content/url), SearxngResponse(results)
// 注意 1:公共实例大多禁用 format=json(返回 403)——403 时 detail 写明
//   "This SearXNG instance blocks JSON API; use a self-hosted instance with format json enabled."
// 注意 2:baseUrl 为空/非法时直接返回 Failure(NETWORK_ERROR, "SearXNG endpoint not configured")
// URL 编码统一用 java.net.URLEncoder.encode(query, "UTF-8")
```

HTTP → 失败映射复用 Task 5.2 的 `apiSearch { ... }.mapBody(...)` 骨架。

- [ ] **Step 3: 测试通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.vibe.app.feature.agent.tool.web.ApiProviderParsingTest"`
Expected: PASS(含 5.2 的旧用例)

- [ ] **Step 4: Commit**

```bash
git commit -am "feat(web): add Brave and SearXNG search providers (opt task 5.3)"
```

**验收标准:** 四个 API provider 共用同一失败映射骨架,无复制粘贴的 try/catch;SearXNG 支持自定义基址并处理尾斜杠。

---

## Task 5.4: 设置持久化 + 设置 UI + Factory 回落逻辑

**改动文件:**
- Create: `app/src/main/kotlin/com/vibe/app/data/model/SearchProviderType.kt`
- Modify: `app/src/main/kotlin/com/vibe/app/data/datastore/SettingDataSource.kt`(+Impl)
- Modify: `app/src/main/kotlin/com/vibe/app/data/repository/SettingRepository.kt`(+Impl,grep 定位 Impl 文件)
- Modify: `.../web/provider/SearchProviderFactory.kt`
- Modify: `app/src/main/kotlin/com/vibe/app/presentation/ui/setting/SettingScreen.kt`、`SettingViewModelV2.kt`
- Modify: `app/src/main/res/values/strings.xml`、`values-zh-rCN/strings.xml`(其余 17 个语言目录回落英文即可,不必逐个补)
- Test: `app/src/test/kotlin/com/vibe/app/feature/agent/tool/web/SearchProviderFactoryTest.kt`

- [ ] **Step 1: 定义类型与持久化**

```kotlin
// data/model/SearchProviderType.kt
package com.vibe.app.data.model

enum class SearchProviderType {
    BUILT_IN, BOCHA, TAVILY, BRAVE, SEARXNG;

    companion object {
        fun fromName(name: String?): SearchProviderType =
            entries.firstOrNull { it.name == name } ?: BUILT_IN
    }
}
```

`SettingDataSource` 接口追加(Impl 按 `debugModeKey` 现有模式,`stringPreferencesKey("search_provider_type" / "search_api_key" / "search_endpoint")`):

```kotlin
suspend fun updateSearchProvider(type: String, apiKey: String, endpoint: String)
suspend fun getSearchProviderType(): String?
suspend fun getSearchApiKey(): String
suspend fun getSearchEndpoint(): String
```

`SettingRepository`(+Impl 透传)追加同名四个方法,返回值把 type 转成 `SearchProviderType`。

- [ ] **Step 2: 写 Factory 回落逻辑测试(先失败)**

`SearchProviderFactory` 重构为依赖 `SettingRepository`;为可测性,把选择逻辑抽成纯函数:

```kotlin
// SearchProviderFactoryTest.kt — 测纯函数 resolveSearchChoice
@Test
fun `built_in selected returns builtin without notice`() {
    val c = resolveSearchChoice(SearchProviderType.BUILT_IN, apiKey = "", endpoint = "")
    assertEquals(SearchChoice.BuiltIn(notice = null), c)
}

@Test
fun `api provider without key falls back with notice`() {
    val c = resolveSearchChoice(SearchProviderType.TAVILY, apiKey = "", endpoint = "")
    assertTrue((c as SearchChoice.BuiltIn).notice!!.contains("API key"))
}

@Test
fun `searxng needs endpoint not key`() {
    assertTrue(resolveSearchChoice(SearchProviderType.SEARXNG, "", "") is SearchChoice.BuiltIn)
    assertTrue(resolveSearchChoice(SearchProviderType.SEARXNG, "", "https://sx.example.com") is SearchChoice.Api)
}
```

- [ ] **Step 3: 实现 Factory**

```kotlin
// SearchProviderFactory.kt(重构,公开签名 active(): ActiveSearch 不变)
@Singleton
class SearchProviderFactory @Inject constructor(
    private val builtIn: BuiltInSerpProvider,
    private val networkClient: NetworkClient,
    private val settingRepository: SettingRepository,
) {
    suspend fun active(): ActiveSearch {
        val type = settingRepository.getSearchProviderType()
        val key = settingRepository.getSearchApiKey()
        val endpoint = settingRepository.getSearchEndpoint()
        return when (val choice = resolveSearchChoice(type, key, endpoint)) {
            is SearchChoice.BuiltIn -> ActiveSearch(builtIn, choice.notice)
            is SearchChoice.Api -> ActiveSearch(createProvider(choice), notice = null)
        }
    }

    private fun createProvider(choice: SearchChoice.Api): WebSearchProvider = when (choice.type) {
        SearchProviderType.BOCHA -> BochaSearchProvider(choice.apiKey, networkClient)
        SearchProviderType.TAVILY -> TavilySearchProvider(choice.apiKey, networkClient)
        SearchProviderType.BRAVE -> BraveSearchProvider(choice.apiKey, networkClient)
        SearchProviderType.SEARXNG -> SearxngSearchProvider(choice.endpoint, networkClient)
        SearchProviderType.BUILT_IN -> builtIn // unreachable, keeps when exhaustive
    }
}

sealed interface SearchChoice {
    data class BuiltIn(val notice: String?) : SearchChoice
    data class Api(val type: SearchProviderType, val apiKey: String, val endpoint: String) : SearchChoice
}

internal fun resolveSearchChoice(
    type: SearchProviderType,
    apiKey: String,
    endpoint: String,
): SearchChoice = when {
    type == SearchProviderType.BUILT_IN -> SearchChoice.BuiltIn(notice = null)
    type == SearchProviderType.SEARXNG ->
        if (endpoint.isBlank()) SearchChoice.BuiltIn(
            "SearXNG endpoint not configured; using built-in search. Configure it in Settings > Search Service.")
        else SearchChoice.Api(type, apiKey, endpoint)
    apiKey.isBlank() -> SearchChoice.BuiltIn(
        "${type.name} API key not configured; using built-in search (less reliable). " +
            "Configure it in Settings > Search Service.")
    else -> SearchChoice.Api(type, apiKey, endpoint)
}
```

- [ ] **Step 4: 测试通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.vibe.app.feature.agent.tool.web.SearchProviderFactoryTest"`
Expected: PASS

- [ ] **Step 5: 设置 UI**

`SettingViewModelV2` 追加(照 `debugMode` 模式):`searchSetting: StateFlow<SearchSettingState>`(`data class SearchSettingState(val type: SearchProviderType = BUILT_IN, val apiKey: String = "", val endpoint: String = "", val testResult: String? = null, val testing: Boolean = false)`)、`fun updateSearchSetting(type, key, endpoint)`、`fun testSearchConnection()`(调 `searchProviderFactory.active().provider.search("test", 1)`,Success → "OK (n results)",Failure → "[kind] detail";ViewModel 需注入 `SearchProviderFactory`)。

`SettingScreen.kt` 在 Debug 项附近追加入口(照 `ThemeSettingItem` 模式):

```kotlin
SettingItem(
    title = stringResource(R.string.search_service),
    description = stringResource(R.string.search_service_description),
    onClick = { viewModel.openSearchDialog() },
)
```

弹窗照 `PlatformSettingDialogs.kt` 的现有对话框风格实现 `SearchSettingDialog`:provider 单选(5 项 RadioButton)、`OutlinedTextField` 输 API key(SEARXNG 时显示 endpoint 输入框代替 key)、"测试连接"`TextButton`(点击后显示 `testResult`)、确认/取消。**动手前先读 `PlatformSettingDialogs.kt`,复用其布局与状态处理惯例。**

strings(`values/strings.xml` 与 `values-zh-rCN/strings.xml`):

```xml
<string name="search_service">Search Service</string>                <!-- zh: 搜索服务 -->
<string name="search_service_description">Configure web search backend for the agent</string> <!-- zh: 配置 Agent 的联网搜索后端 -->
<string name="search_provider_built_in">Built-in (free, less reliable)</string> <!-- zh: 内置(免费,稳定性一般) -->
<string name="search_api_key">API Key</string>
<string name="search_endpoint">SearXNG Endpoint</string>
<string name="search_test_connection">Test connection</string>       <!-- zh: 测试连接 -->
```

- [ ] **Step 6: 设备验证**

Run: `./gradlew assembleDebug` 并安装。设置页配置 Tavily key → 聊天里让 agent 搜索 → 日志确认走 `TavilySearchProvider`;清空 key → 再搜索 → 工具结果含 "using built-in search" notice。

- [ ] **Step 7: Commit**

```bash
git commit -am "feat(setting): search service provider settings with fallback to built-in (opt task 5.4)"
```

**验收标准:** 不配置任何东西时行为与 Phase 5 之前完全一致(内置爬取、无 notice);配 key 后走 API provider;配置无效时回落且模型能在工具结果里看到原因。

---

## Task 5.5: `WebFetchCache` — fetch 落盘 + 分页读取

**改动文件:**
- Create: `app/src/main/kotlin/com/vibe/app/feature/agent/tool/web/WebFetchCache.kt`
- Modify: `app/src/main/kotlin/com/vibe/app/feature/agent/tool/FetchWebPageTool.kt`
- Modify: `.../web/WebViewContentExtractor.kt`(截断上限 8000 → 60000)与 `WebConstants.kt`
- Modify: `app/src/main/kotlin/com/vibe/app/feature/project/DefaultProjectWorkspace.kt`(listFiles 排除 `web-cache`)
- Modify: `app/src/main/assets/agent-system-prompt.md`(web 工具使用说明,约 52-57 行区域)
- Test: `app/src/test/kotlin/com/vibe/app/feature/agent/tool/web/WebFetchCacheTest.kt`

**目录选型(已核实,勿改):** 缓存放 `<workspace rootDir>/web-cache/<sha1(url)>.md`,即 `projects/{id}/app/web-cache/`。理由:① `read_project_file` 的 `resolveFile` 只允许 rootDir 之内的路径,放 `.vibe/`(rootDir 之父)模型读不到;② 构建管线只收集 `src/main/*` 与 `build/gen`(`BuildWorkspace.kt:46-60`),该目录不会进 APK;③ `listFiles()` 需追加排除,避免污染文件列表与 outline。

- [ ] **Step 1: 写 WebFetchCache 测试(先失败)**

```kotlin
// WebFetchCacheTest.kt
package com.vibe.app.feature.agent.tool.web

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WebFetchCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `write then read roundtrip with stable path`() {
        val cache = WebFetchCache(tmp.root)
        val path1 = cache.write("https://a.com/x", "Title", "content body")
        val path2 = cache.pathFor("https://a.com/x")
        assertEquals(path1, path2)                      // sha1 稳定
        assertTrue(path1.startsWith("web-cache/"))
        assertTrue(path1.endsWith(".md"))
        assertEquals("content body", cache.readContent("https://a.com/x"))
    }

    @Test
    fun `fresh entry is reused, stale is not`() {
        val cache = WebFetchCache(tmp.root)
        cache.write("https://a.com", "T", "C")
        assertNotNull(cache.readFresh("https://a.com", maxAgeMs = 60_000))
        assertNull(cache.readFresh("https://a.com", maxAgeMs = -1)) // 一切都过期
    }

    @Test
    fun `slice respects offset and maxChars`() {
        assertEquals("cde", sliceContent("abcdefg", offset = 2, maxChars = 3))
        assertEquals("", sliceContent("abc", offset = 10, maxChars = 3))
    }
}
```

- [ ] **Step 2: 跑测试确认失败,然后实现**

```kotlin
// WebFetchCache.kt
package com.vibe.app.feature.agent.tool.web

import java.io.File
import java.security.MessageDigest

/**
 * Persists fetched page content under `<workspaceRoot>/web-cache/<sha1(url)>.md`
 * so the agent can page through it with read_project_file instead of receiving
 * the whole page in one tool result. The directory is invisible to the build
 * pipeline (which only reads src/main/* and build/gen).
 */
class WebFetchCache(private val workspaceRoot: File) {

    fun pathFor(url: String): String = "web-cache/${sha1(url)}.md"

    /** Writes content, returns the workspace-relative path. */
    fun write(url: String, title: String, content: String): String {
        val rel = pathFor(url)
        val file = File(workspaceRoot, rel)
        file.parentFile?.mkdirs()
        // Header lines let a human (and the model, via read_project_file) identify the page.
        file.writeText("<!-- url: $url -->\n<!-- title: $title -->\n$content")
        return rel
    }

    fun readContent(url: String): String? {
        val file = File(workspaceRoot, pathFor(url))
        if (!file.exists()) return null
        return file.readText().substringAfter("-->\n").substringAfter("-->\n")
    }

    fun readFresh(url: String, maxAgeMs: Long): String? {
        val file = File(workspaceRoot, pathFor(url))
        if (!file.exists()) return null
        if (System.currentTimeMillis() - file.lastModified() > maxAgeMs) return null
        return readContent(url)
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}

internal fun sliceContent(content: String, offset: Int, maxChars: Int): String {
    if (offset >= content.length) return ""
    return content.substring(offset, minOf(content.length, offset + maxChars))
}
```

- [ ] **Step 3: 常量调整与 listFiles 排除**

`WebConstants.kt`:

```kotlin
/** Max chars extracted from a page and persisted to web-cache. */
const val MAX_EXTRACTED_LENGTH = 60_000
/** Max chars returned to the agent per fetch_web_page call (also the max_chars cap). */
const val MAX_CONTENT_LENGTH = 8_000
/** Default slice size returned when max_chars is not specified. */
const val DEFAULT_FETCH_CHARS = 1_500
/** Reuse a cached page within this window instead of re-fetching. */
const val FETCH_CACHE_TTL_MS = 30 * 60 * 1000L
```

`WebViewContentExtractor` 的截断(`:174-180` 一带,grep `MAX_CONTENT_LENGTH`)改用 `MAX_EXTRACTED_LENGTH`。

`DefaultProjectWorkspace.listFiles()`(`DefaultProjectWorkspace.kt:44-51`):

```kotlin
.onEnter { it.name != "build" && it.name != "web-cache" }
```

- [ ] **Step 4: 重写 FetchWebPageTool**

```kotlin
// FetchWebPageTool.kt — schema 增加两个可选参数:
put("max_chars", intProp("Max characters to return (default 1500, cap 8000)."))
put("offset", intProp("Character offset to start from (default 0). Use with the total_chars from a previous call to page through long content."))

// execute():
override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
    val url = call.arguments.requireString("url")
    val maxChars = call.arguments.optionalInt("max_chars", WebConstants.DEFAULT_FETCH_CHARS)
        .coerceIn(1, WebConstants.MAX_CONTENT_LENGTH)
    val offset = call.arguments.optionalInt("offset", 0).coerceAtLeast(0)

    val workspace = projectManager.openWorkspace(context.projectId)   // 新增注入 ProjectManager
    val cache = WebFetchCache(workspace.rootDir)

    val cached = cache.readFresh(url, WebConstants.FETCH_CACHE_TTL_MS)
    val (title, content) = if (cached != null) {
        "(cached)" to cached
    } else {
        val fetched = webContentFetcher.fetch(url).getOrElse {
            return call.errorResult("Failed to fetch page: ${it.message}")
        }
        cache.write(url, fetched.title, fetched.content)
        fetched.title to fetched.content
    }

    val slice = sliceContent(content, offset, maxChars)
    return call.result(buildJsonObject {
        put("title", JsonPrimitive(title))
        put("url", JsonPrimitive(url))
        put("total_chars", JsonPrimitive(content.length))
        put("offset", JsonPrimitive(offset))
        put("content", JsonPrimitive(slice))
        put("cache_path", JsonPrimitive(cache.pathFor(url)))
        if (offset + slice.length < content.length) {
            put("hint", JsonPrimitive(
                "Content truncated. Call again with offset=${offset + slice.length}, " +
                    "or use read_project_file on cache_path with start_line/end_line."))
        }
    })
}
```

工具 description 更新:"Fetch a web page as Markdown. Returns a slice of the content (default 1500 chars) plus total_chars and a cache_path readable via read_project_file. Page through long content with offset."

- [ ] **Step 5: 更新 agent-system-prompt.md**

定位 web 工具说明段(grep `web_search`,约 52-57 行),替换 fetch 相关描述为:

```markdown
- fetch_web_page returns a SLICE of the page (default 1500 chars) with total_chars.
  For long pages, either call it again with offset, or read the cached file at
  cache_path via read_project_file (supports start_line/end_line). Never re-fetch
  the same URL just to see more content.
```

- [ ] **Step 6: 全部测试 + 设备验证**

Run: `./gradlew :app:testDebugUnitTest --tests "com.vibe.app.feature.agent.tool.web.*"`
Expected: PASS
设备:让 agent fetch 一个长文页面 → 确认工具结果只有 ~1500 字符 + total_chars + cache_path;`projects/{id}/app/web-cache/` 下有 `.md` 文件;随后 `run_build_pipeline` 构建成功(缓存目录未干扰构建);`list_project_files` 不显示 web-cache。

- [ ] **Step 7: Commit**

```bash
git commit -am "feat(web): persist fetched pages to web-cache with paged reads (opt task 5.5)"
```

**验收标准:** 单次 fetch 进入 context 的内容从最多 8000 字符降到默认 1500;30 分钟内重复 fetch 同一 URL 不再启动 WebView;构建、文件列表、快照(snapshot 只关心 src 下源码,如有疑问 grep SnapshotStorage 确认收集范围并记录)均不受缓存目录影响。

---

## Phase 完成检查

- [ ] 全量验证:`./gradlew test && ./gradlew assembleDebug` 通过
- [ ] 人工验证清单(Android 10+ 真机/模拟器):
  - [ ] 未配置任何搜索设置:web_search 行为与 Phase 2 结束时一致(内置爬取);
  - [ ] 配置 Tavily(或博查)key:搜索走 API,返回 5 条结果;
  - [ ] 故意填错 key:工具结果为 `[BLOCKED] ... Check Settings > Search Service`;
  - [ ] 清空 key:回落内置 + notice 提示;
  - [ ] "测试连接"按钮对有效/无效 key 分别显示成功/失败;
  - [ ] fetch 长页面 → 分页读取(offset 或 read_project_file cache_path)可拿到后续内容;
  - [ ] fetch 后立即 build:成功,APK 不含 web-cache 内容。
- [ ] 更新 `00-progress.md`:Phase 5 状态 → ✅ 已完成,填完成日期
- [ ] `git commit -m "docs: mark optimization phase 5 complete"`

## 实施记录(执行时追加)

| 日期 | 执行者 | 完成内容 | 偏离/备注 |
|------|--------|----------|-----------|
| | | | |
