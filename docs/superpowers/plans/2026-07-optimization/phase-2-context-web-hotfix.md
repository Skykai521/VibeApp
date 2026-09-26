# Phase 2: Context 与 Web 止血包 实施计划

> **执行者须知(任何模型/会话通用):**
> 1. 开工前先读同目录 `00-progress.md`,确认本 phase 状态与当前进度,遵守其中的接力协议;
> 2. 本文引用的 `file:line` 基于 `dev@be1f944`,代码可能已漂移——每个 Task 动手前先用 grep 重新定位;
> 3. 每完成一个 Task:勾选本文所有对应 checkbox → 跑"验证"节命令 → 独立 commit → 更新 `00-progress.md` 状态表"当前位置";
> 4. 任何偏离(改方案/跳步骤/发现计划错误)必须记入文末"实施记录",禁止静默偏离;
> 5. 建议用 `superpowers:executing-plans` 或 `superpowers:subagent-driven-development` 技能执行本计划。

**目标:** 修复上下文压缩链中"压缩不生效/压缩结果不合法"的四个缺陷(D1/D5/D7/D12 + web 工具不裁剪),并让 web 搜索失败变得可感知、可分类、可降级。

**评审依据:** `docs/optimization-review-2026-07.md` §3.3 第一步 + §2.2 B/C1。

**前置依赖:** 无(可与 Phase 1/3 并行)。

**涉及模块:** `app/feature/agent/loop`(coordinator、gateway)、`app/feature/agent/loop/compaction`、`app/feature/agent/tool`(FileTools、AgentToolExtensions)、`app/feature/agent/tool/web`。

**测试约束:** 项目测试栈只有 JUnit4 + kotlinx-coroutines-test(无 mockk/mockito,见 `app/build.gradle.kts:172-173`),所有测试用**手写 Fake / 纯函数抽取**,不引入新 mock 框架。

---

## Task 2.1: OpenAI Responses 旁路修复(评审 D1)

**现状与证据:**
- Coordinator 每次迭代对 `fullConversation` 做压缩,结果放进 `AgentModelRequest.fullConversation`(`DefaultAgentLoopCoordinator.kt:193-218`);
- 但 `OpenAiResponsesAgentGateway.streamTurn` 只读 `request.conversation`(delta)+ `previousResponseId`(`OpenAiResponsesAgentGateway.kt:58-59`),从不读 `fullConversation` —— 压缩对 OpenAI/OpenAI 兼容端点完全无效,服务端状态无限累积;
- `AgentModelRequest` 两个字段的语义见 `AgentContracts.kt:29-42`。

**修复方案(已定):** 压缩一旦生效,coordinator 将 `previousResponseId` 置 null(丢弃服务端会话链);gateway 在 `previousResponseId == null` 时发送完整的(已压缩)`fullConversation` 而非 delta。首轮 `previousResponseId` 本来就是 null,此时 `conversationDelta == initialConversation == fullConversation` 内容一致(若 Phase A 压缩过则 fullConversation 更小、更正确),行为兼容。

**改动文件:**
- Modify: `app/src/main/kotlin/com/vibe/app/feature/agent/loop/OpenAiResponsesAgentGateway.kt`
- Modify: `app/src/main/kotlin/com/vibe/app/feature/agent/loop/DefaultAgentLoopCoordinator.kt`(压缩调用处,约 :193-218)
- Create: `app/src/test/kotlin/com/vibe/app/feature/agent/loop/OpenAiResponsesInputSelectionTest.kt`

**接口(供后续使用):** `internal fun selectResponsesInput(previousResponseId: String?, delta: List<AgentConversationItem>, full: List<AgentConversationItem>): List<AgentConversationItem>`(顶层函数,置于 OpenAiResponsesAgentGateway.kt)。

- [x] **Step 1: 写失败测试**

```kotlin
package com.vibe.app.feature.agent.loop

import com.vibe.app.feature.agent.AgentConversationItem
import com.vibe.app.feature.agent.AgentMessageRole
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenAiResponsesInputSelectionTest {

    private val delta = listOf(AgentConversationItem(role = AgentMessageRole.TOOL, toolName = "t", toolCallId = "c1", text = "delta"))
    private val full = listOf(
        AgentConversationItem(role = AgentMessageRole.USER, text = "hi"),
        AgentConversationItem(role = AgentMessageRole.ASSISTANT, text = "compacted history"),
    )

    @Test
    fun `null previousResponseId sends full compacted conversation`() {
        assertEquals(full, selectResponsesInput(previousResponseId = null, delta = delta, full = full))
    }

    @Test
    fun `non-null previousResponseId keeps delta mode`() {
        assertEquals(delta, selectResponsesInput(previousResponseId = "resp_123", delta = delta, full = full))
    }
}
```

注:`AgentConversationItem` 构造参数以实际类为准(grep `data class AgentConversationItem`),缺省参数应可只填 role/text/toolName/toolCallId。

- [x] **Step 2: 跑测试确认编译失败**

Run: `./gradlew :app:testDebugUnitTest --tests "*.OpenAiResponsesInputSelectionTest"`
Expected: FAIL(`selectResponsesInput` 未定义)

- [x] **Step 3: 实现 gateway 侧选择函数并接线**

在 `OpenAiResponsesAgentGateway.kt` 文件末尾(类外)添加:

```kotlin
/**
 * Stateful Responses sessions accumulate history server-side, which client-side
 * compaction cannot touch. Whenever the coordinator resets the response chain
 * (previousResponseId == null), send the full — already compacted — history
 * so the new chain starts from the compacted state.
 */
internal fun selectResponsesInput(
    previousResponseId: String?,
    delta: List<AgentConversationItem>,
    full: List<AgentConversationItem>,
): List<AgentConversationItem> = if (previousResponseId == null) full else delta
```

`streamTurn` 中(约 :58)把:

```kotlin
input = request.conversation.map(::toResponseInputItem),
```

改为:

```kotlin
input = selectResponsesInput(request.previousResponseId, request.conversation, request.fullConversation)
    .map(::toResponseInputItem),
```

- [x] **Step 4: coordinator 在压缩生效时重置会话链**

`DefaultAgentLoopCoordinator.kt` 中,`conversationCompactor.compact(...)` 调用之后、`agentModelGateway.streamTurn(...)` 之前(现有诊断日志块 :199-211 内部或紧后)加入:

```kotlin
if (compactionResult.strategyUsed != CompactionStrategyType.NONE && previousResponseId != null) {
    // The server-side Responses chain still holds the uncompacted history.
    // Drop it so the compacted fullConversation is sent fresh this iteration.
    previousResponseId = null
}
```

同时确认 wind-down 收尾请求(grep `AgentModelRequest(` 的第二处构造,约 :440-455)同样传了 `previousResponseId` 与 `fullConversation` —— gateway 侧的 `selectResponsesInput` 对它自动生效,无需额外改动;若发现该处没传压缩结果,在实施记录中注明并同步修复。

- [x] **Step 5: 跑测试与全量编译**

Run: `./gradlew :app:testDebugUnitTest --tests "*.OpenAiResponsesInputSelectionTest" && ./gradlew :app:compileDebugKotlin`
Expected: PASS + 编译通过

- [x] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/feature/agent/loop/OpenAiResponsesAgentGateway.kt \
        app/src/main/kotlin/com/vibe/app/feature/agent/loop/DefaultAgentLoopCoordinator.kt \
        app/src/test/kotlin/com/vibe/app/feature/agent/loop/OpenAiResponsesInputSelectionTest.kt
git commit -m "fix(agent): route compacted history to OpenAI Responses on chain reset (opt task 2.1)"
```

**验收标准:** OpenAI 兼容 provider 上触发压缩后,下一次请求不带 `previous_response_id` 且 input 为完整压缩历史;未触发压缩时行为与现状一致。

---

## Task 2.2: 上游节流 — read_project_file 上限与 build 输出截断(评审 D12)

**现状与证据:**
- `ReadProjectFileTool` 全文件读取无任何上限(`FileTools.kt:95-126`,`useRange == false` 分支直接 `put("content", JsonPrimitive(fullContent))`);批量 `paths` 读取同样无上限(:128-149)。已有行区间参数 **`start_line` / `end_line`**(-1 表示 EOF,`sliceByLines` 见 :23-40);
- build 输出:`BuildResult.toFilteredJson` 中 `errorMessage` 与每条 log `message` 不截断(`AgentToolExtensions.kt:105-136`,已有 `MAX_TOOL_LOGS = 12` 条数限制)。

**改动文件:**
- Modify: `app/src/main/kotlin/com/vibe/app/feature/agent/tool/FileTools.kt`
- Modify: `app/src/main/kotlin/com/vibe/app/feature/agent/tool/AgentToolExtensions.kt`
- Create: `app/src/test/kotlin/com/vibe/app/feature/agent/tool/FileContentClampTest.kt`
- Create: `app/src/test/kotlin/com/vibe/app/feature/agent/tool/BuildResultJsonTest.kt`

**接口:** `internal fun clampFileContent(content: String, maxLines: Int = MAX_READ_LINES, maxChars: Int = MAX_READ_CHARS): ClampResult`;`internal data class ClampResult(val content: String, val truncated: Boolean, val totalLines: Int)`(顶层,置于 FileTools.kt,与 `sliceByLines` 并列)。常量 `MAX_READ_LINES = 2000`、`MAX_READ_CHARS = 50_000`。

- [x] **Step 1: 写失败测试(clamp 纯函数)**

```kotlin
package com.vibe.app.feature.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileContentClampTest {

    @Test
    fun `small file passes through unchanged`() {
        val content = (1..10).joinToString("\n") { "line $it" }
        val result = clampFileContent(content)
        assertFalse(result.truncated)
        assertEquals(content, result.content)
        assertEquals(10, result.totalLines)
    }

    @Test
    fun `file over line limit is cut to maxLines`() {
        val content = (1..3000).joinToString("\n") { "line $it" }
        val result = clampFileContent(content, maxLines = 2000, maxChars = 50_000)
        assertTrue(result.truncated)
        assertEquals(3000, result.totalLines)
        assertEquals(2000, result.content.lines().size)
        assertTrue(result.content.endsWith("line 2000"))
    }

    @Test
    fun `file over char limit is cut to maxChars`() {
        val content = "x".repeat(60_000) // 单行超长
        val result = clampFileContent(content, maxLines = 2000, maxChars = 50_000)
        assertTrue(result.truncated)
        assertEquals(50_000, result.content.length)
    }
}
```

- [x] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "*.FileContentClampTest"`
Expected: FAIL(`clampFileContent` 未定义)

- [x] **Step 3: 实现 clamp 并接入工具**

FileTools.kt 顶层添加:

```kotlin
internal const val MAX_READ_LINES = 2000
internal const val MAX_READ_CHARS = 50_000

internal data class ClampResult(val content: String, val truncated: Boolean, val totalLines: Int)

internal fun clampFileContent(
    content: String,
    maxLines: Int = MAX_READ_LINES,
    maxChars: Int = MAX_READ_CHARS,
): ClampResult {
    val lines = content.lines()
    val totalLines = if (lines.isNotEmpty() && lines.last().isEmpty()) lines.size - 1 else lines.size
    var clamped = content
    var truncated = false
    if (totalLines > maxLines) {
        clamped = lines.take(maxLines).joinToString("\n")
        truncated = true
    }
    if (clamped.length > maxChars) {
        clamped = clamped.take(maxChars)
        truncated = true
    }
    return ClampResult(clamped, truncated, totalLines)
}
```

`ReadProjectFileTool.execute` 单文件全量分支(:121-123 的 `else`)改为:

```kotlin
} else {
    val clamp = clampFileContent(fullContent)
    put("content", JsonPrimitive(clamp.content))
    if (clamp.truncated) {
        put("truncated", JsonPrimitive(true))
        put("total_lines", JsonPrimitive(clamp.totalLines))
        put(
            "hint",
            JsonPrimitive(
                "File truncated to the first $MAX_READ_LINES lines / $MAX_READ_CHARS chars. " +
                    "Use start_line/end_line to read the remaining ranges.",
            ),
        )
    }
}
```

行区间分支(`useRange == true`)在 `sliceByLines` 结果上同样套 `clampFileContent`(超大区间也要被夹住),truncated 时输出同样的三个字段。批量 `paths` 分支对每个文件的 `content.getOrThrow()` 同样套 clamp,truncated 时在该文件对象内加 `truncated`/`total_lines` 字段。

- [x] **Step 4: build 输出截断 + 测试**

`AgentToolExtensions.kt` `toFilteredJson` 中:

```kotlin
errorMessage?.let { put("errorMessage", JsonPrimitive(it.take(MAX_ERROR_MESSAGE_CHARS))) }
// ...
put("message", JsonPrimitive(log.message.take(MAX_LOG_MESSAGE_CHARS)))
```

文件底部常量区:

```kotlin
private const val MAX_TOOL_LOGS = 12
internal const val MAX_ERROR_MESSAGE_CHARS = 2_000
internal const val MAX_LOG_MESSAGE_CHARS = 500
```

测试(`BuildResultJsonTest.kt`;`BuildResult(status, artifacts, logs, errorMessage)` 与 `BuildLogEntry(stage, level, message, sourcePath, line)` 签名已核实于 `build-engine/.../model/BuildModels.kt:76`;`BuildStatus`/`BuildStage` 枚举值以该文件为准):

```kotlin
package com.vibe.app.feature.agent.tool

import com.vibe.build.engine.model.BuildLogEntry
import com.vibe.build.engine.model.BuildLogLevel
import com.vibe.build.engine.model.BuildResult
import com.vibe.build.engine.model.BuildStage
import com.vibe.build.engine.model.BuildStatus
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class BuildResultJsonTest {

    @Test
    fun `oversized error message and log messages are truncated`() {
        val result = BuildResult(
            status = BuildStatus.FAILED,
            artifacts = emptyList(),
            logs = listOf(
                BuildLogEntry(
                    stage = BuildStage.COMPILE,
                    level = BuildLogLevel.ERROR,
                    message = "e".repeat(5_000),
                ),
            ),
            errorMessage = "x".repeat(10_000),
        )
        val json = result.toFilteredJson()
        assertEquals(2_000, json["errorMessage"]!!.jsonPrimitive.content.length)
        val firstLog = json["logs"]!!.jsonArray.first().jsonObject
        assertEquals(500, firstLog["message"]!!.jsonPrimitive.content.length)
    }
}
```

- [x] **Step 5: 跑测试**

Run: `./gradlew :app:testDebugUnitTest --tests "*.FileContentClampTest" --tests "*.BuildResultJsonTest"`
Expected: PASS

- [x] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/feature/agent/tool/FileTools.kt \
        app/src/main/kotlin/com/vibe/app/feature/agent/tool/AgentToolExtensions.kt \
        app/src/test/kotlin/com/vibe/app/feature/agent/tool/FileContentClampTest.kt \
        app/src/test/kotlin/com/vibe/app/feature/agent/tool/BuildResultJsonTest.kt
git commit -m "fix(agent): clamp read_project_file and build output sizes (opt task 2.2)"
```

**验收标准:** 读取 3000 行文件返回前 2000 行 + `truncated/total_lines/hint`;行区间与批量读取同样受限;build 失败结果中 errorMessage ≤ 2000 字符、单条日志 ≤ 500 字符。

---

## Task 2.3: 模型摘要结果预算检查(评审 D5 前半)

**现状与证据:** `ConversationCompactor.compact` 中 Strategy 3 的返回不做预算校验:`if (modelResult != null) return modelResult`(`ConversationCompactor.kt:81-83`)——摘要 + recent 回合仍超限时直接发出,兜底 `truncateToFitBudget` 被跳过。且最终兜底的 `bestResult = structuralResult ?: trimResult`(:87)不含 modelResult。

**改动文件:**
- Modify: `app/src/main/kotlin/com/vibe/app/feature/agent/loop/compaction/ConversationCompactor.kt`
- Create: `app/src/test/kotlin/com/vibe/app/feature/agent/loop/compaction/ConversationCompactorBudgetTest.kt`

- [x] **Step 1: 写失败测试**

`OpenAIAPI` 是接口(`data/network/OpenAIAPI.kt:13`,4 个方法 + 2 个 setter),手写 Fake 只实现 `completeQwenChatCompletion`。`PlatformV2` 必填参数为 `name/compatibleType/apiUrl/model`(已核实)。KIMI 预算 24_000 token、recentTurns 3(`ProviderContextBudget.kt`)。

```kotlin
package com.vibe.app.feature.agent.loop.compaction

import com.vibe.app.data.database.entity.PlatformV2
import com.vibe.app.data.dto.openai.request.ChatCompletionRequest
import com.vibe.app.data.dto.openai.request.ResponsesRequest
import com.vibe.app.data.dto.qwen.request.QwenChatCompletionRequest
import com.vibe.app.data.dto.qwen.response.QwenChatCompletionResponse
import com.vibe.app.data.model.ClientType
import com.vibe.app.data.network.OpenAIAPI
import com.vibe.app.feature.agent.AgentConversationItem
import com.vibe.app.feature.agent.AgentMessageRole
import com.vibe.app.feature.diagnostic.ModelExecutionTrace
import com.vibe.app.feature.diagnostic.ModelRequestDiagnosticContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fake that returns a fixed summary text. Streaming methods are unused
 * by the compaction path and fail loudly if touched.
 * 注意:QwenChatCompletionResponse/QwenChoice/QwenAssistantMessage 的构造以
 * data/dto/qwen/response 实际字段为准(choices[].message.content 已核实存在)。
 */
private class FakeOpenAIAPI(private val summary: String) : OpenAIAPI {
    override fun setToken(token: String?) = Unit
    override fun setAPIUrl(url: String) = Unit
    override fun streamChatCompletion(request: ChatCompletionRequest, diagnosticContext: ModelRequestDiagnosticContext?, trace: ModelExecutionTrace?) =
        error("unused in test")
    override fun streamResponses(request: ResponsesRequest, diagnosticContext: ModelRequestDiagnosticContext?, trace: ModelExecutionTrace?) =
        error("unused in test")
    override fun streamQwenChatCompletion(request: QwenChatCompletionRequest, diagnosticContext: ModelRequestDiagnosticContext?, trace: ModelExecutionTrace?) =
        error("unused in test")
    override suspend fun completeQwenChatCompletion(
        request: QwenChatCompletionRequest,
        diagnosticContext: ModelRequestDiagnosticContext?,
        trace: ModelExecutionTrace?,
    ): QwenChatCompletionResponse = qwenResponseWithContent(summary) // 测试内 helper:按 DTO 实际构造
}

class ConversationCompactorBudgetTest {

    private val platform = PlatformV2(
        name = "kimi-test", compatibleType = ClientType.KIMI,
        apiUrl = "https://example.invalid", model = "kimi-k2.5",
    )

    /** 5 个回合、每回合 assistant 文本 40k 字符 ≈ 10k token,总量远超 KIMI 24k 预算。 */
    private fun oversizedConversation(): List<AgentConversationItem> =
        (1..5).flatMap { i ->
            listOf(
                AgentConversationItem(role = AgentMessageRole.USER, text = "request $i"),
                AgentConversationItem(role = AgentMessageRole.ASSISTANT, text = "a".repeat(40_000)),
            )
        }

    @Test
    fun `oversized model summary must not be returned as final result`() = runBlocking {
        // 摘要本身 200k 字符 ≈ 50k token,加 recent 回合必然超预算
        val compactor = ConversationCompactor(FakeOpenAIAPI("s".repeat(200_000)))
        val result = compactor.compact(oversizedConversation(), ClientType.KIMI, platform)
        assertNotEquals(CompactionStrategyType.MODEL_SUMMARY, result.strategyUsed)
        assertTrue("final result must fit budget", result.estimatedTokens <= 24_000)
    }

    @Test
    fun `small model summary is accepted`() = runBlocking {
        val compactor = ConversationCompactor(FakeOpenAIAPI("short summary of earlier work"))
        val result = compactor.compact(oversizedConversation(), ClientType.KIMI, platform)
        // 小摘要 + recent 3 回合(3×40k 字符 ≈ 30k token)仍超预算 → 不强求 MODEL_SUMMARY,
        // 只断言最终结果预算合规(recent 回合的处理属于 Phase 4 范围)
        assertTrue(result.estimatedTokens <= 24_000 || result.strategyUsed == CompactionStrategyType.MODEL_SUMMARY)
    }
}
```

注:第一个测试是本 Task 的核心断言;若 `assertTrue(estimatedTokens <= 24_000)` 因 recent 回合过大而失败,把该断言放宽为 `assertNotEquals(MODEL_SUMMARY, ...)` 并在实施记录注明(recent 回合超限是 Phase 4/Task 4.1 的问题域)。

- [x] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "*.ConversationCompactorBudgetTest"`
Expected: 第一个测试 FAIL(oversized summary 被原样返回,strategyUsed == MODEL_SUMMARY)

- [x] **Step 3: 实现预算检查**

`ConversationCompactor.kt` :74-84 改为:

```kotlin
var modelResult: CompactionResult? = null
if (modelSummaryStrategy.isSupported(clientType) && platform != null) {
    modelSummaryStrategy.apiUrl = platform.apiUrl
    modelSummaryStrategy.token = platform.token
    modelSummaryStrategy.model = platform.model
    modelResult = modelSummaryStrategy.compact(afterTrim, budget.recentTurns, budget.maxTokens)
    if (modelResult != null && modelResult.estimatedTokens <= budget.maxTokens) {
        return modelResult
    }
}
```

并把 :87 的兜底选择改为(模型摘要虽超限但通常最小,截断从它继续):

```kotlin
val bestResult = modelResult ?: structuralResult ?: trimResult
```

- [x] **Step 4: 跑测试**

Run: `./gradlew :app:testDebugUnitTest --tests "*.ConversationCompactorBudgetTest" --tests "*.ToolResultTrimStrategyTest"`
Expected: 全部 PASS(存量测试不回归)

- [x] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/feature/agent/loop/compaction/ConversationCompactor.kt \
        app/src/test/kotlin/com/vibe/app/feature/agent/loop/compaction/ConversationCompactorBudgetTest.kt
git commit -m "fix(compaction): enforce budget on model summary results (opt task 2.3)"
```

**验收标准:** 超预算的模型摘要不再被直接返回;compact() 最终结果在预算内或已走 TEXT_TRUNCATION 兜底;存量压缩测试全绿。

---

## Task 2.4: 摘要角色修复 + 同角色消息防御性合并(评审 D7)

**现状与证据:**
- 两处摘要项都是 `role = USER`:`StructuralSummaryStrategy.kt:100-103`("[Compacted Turn]")与 `ModelSummaryStrategy.kt:63-66`("[Conversation Summary]");多个摘要 + 真实 user 消息在 Anthropic gateway 中各自成独立 user message(`AnthropicMessagesAgentGateway.kt:228-234`),对要求角色交替的 Messages API 有 400 风险。
- **本 Task 有两个连带陷阱,必须一并处理**(计划阶段已核实,不是猜测):
  1. `splitIntoTurns`(`ToolResultTrimStrategy.kt:168-178`)会**静默丢弃首个 USER 之前的所有项**——摘要改成 ASSISTANT 角色后位于列表头部,下一轮压缩会把它无声删除;
  2. `StructuralSummaryStrategy.summarizeTurn` 对没有 USER 项的回合返回 null 且被 `mapNotNull` 丢弃(`StructuralSummaryStrategy.kt:33,54`)——同样会吃掉头部摘要组。

**改动文件:**
- Modify: `app/src/main/kotlin/com/vibe/app/feature/agent/loop/compaction/ToolResultTrimStrategy.kt`(splitIntoTurns)
- Modify: `app/src/main/kotlin/com/vibe/app/feature/agent/loop/compaction/StructuralSummaryStrategy.kt`
- Modify: `app/src/main/kotlin/com/vibe/app/feature/agent/loop/compaction/ModelSummaryStrategy.kt`
- Modify: `app/src/main/kotlin/com/vibe/app/feature/agent/loop/AnthropicMessagesAgentGateway.kt`
- Create: `app/src/test/kotlin/com/vibe/app/feature/agent/loop/compaction/SummaryRoleAndPreambleTest.kt`
- Create: `app/src/test/kotlin/com/vibe/app/feature/agent/loop/AnthropicMessageMergeTest.kt`

**接口:** `internal fun mergeConsecutiveSameRole(messages: List<InputMessage>): List<InputMessage>`(顶层,置于 AnthropicMessagesAgentGateway.kt)。

- [x] **Step 1: 写失败测试(splitIntoTurns 前导项保留 + 摘要角色 + 结构化策略透传)**

```kotlin
package com.vibe.app.feature.agent.loop.compaction

import com.vibe.app.feature.agent.AgentConversationItem
import com.vibe.app.feature.agent.AgentMessageRole
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryRoleAndPreambleTest {

    private fun user(t: String) = AgentConversationItem(role = AgentMessageRole.USER, text = t)
    private fun assistant(t: String) = AgentConversationItem(role = AgentMessageRole.ASSISTANT, text = t)

    @Test
    fun `splitIntoTurns keeps leading non-user items as preamble group`() {
        val items = listOf(assistant("[Compacted context] earlier summary"), user("next"), assistant("reply"))
        val turns = ToolResultTrimStrategy.splitIntoTurns(items)
        assertEquals("all items must survive round-trip", items, turns.flatten())
    }

    @Test
    fun `structural summary emits assistant role with compacted prefix`() = runBlocking {
        val items = (1..4).flatMap { listOf(user("req $it"), assistant("resp $it")) }
        val result = StructuralSummaryStrategy().compact(items, recentTurnCount = 1, tokenBudget = Int.MAX_VALUE)!!
        val summary = result.items.first()
        assertEquals(AgentMessageRole.ASSISTANT, summary.role)
        assertTrue(summary.text!!.startsWith("[Compacted context]"))
    }

    @Test
    fun `structural summary passes preamble group through unchanged`() = runBlocking {
        val preamble = assistant("[Compacted context] old summary")
        val items = listOf(preamble) + (1..3).flatMap { listOf(user("req $it"), assistant("resp $it")) }
        val result = StructuralSummaryStrategy().compact(items, recentTurnCount = 1, tokenBudget = Int.MAX_VALUE)!!
        assertTrue("preamble summary must survive", result.items.any { it.text == preamble.text })
    }
}
```

- [x] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "*.SummaryRoleAndPreambleTest"`
Expected: 三个测试全 FAIL

- [x] **Step 3: 实现压缩侧修改**

`ToolResultTrimStrategy.splitIntoTurns`(:168-178)改为保留前导组:

```kotlin
fun splitIntoTurns(items: List<AgentConversationItem>): List<List<AgentConversationItem>> {
    val turns = mutableListOf<MutableList<AgentConversationItem>>()
    for (item in items) {
        if (item.role == AgentMessageRole.USER) {
            turns.add(mutableListOf(item))
        } else {
            // Leading non-USER items (e.g. compaction summaries) form a preamble
            // group so they are never silently dropped by re-compaction.
            if (turns.isEmpty()) turns.add(mutableListOf())
            turns.last().add(item)
        }
    }
    return turns
}
```

`StructuralSummaryStrategy.compact`(:33)摘要生成改为透传无 USER 的组:

```kotlin
val summaryItems = olderTurns.flatMap { turn ->
    summarizeTurn(turn)?.let { listOf(it) } ?: turn
}
```

`summarizeTurn` 返回项(:100-103)与摘要文本头(:87)改为:

```kotlin
append("[Compacted context]\n")   // 原 "[Compacted Turn]\n"
// ...
return AgentConversationItem(
    role = AgentMessageRole.ASSISTANT,
    text = summary,
)
```

`ModelSummaryStrategy`(:63-66)同样:

```kotlin
val summaryItem = AgentConversationItem(
    role = AgentMessageRole.ASSISTANT,
    text = "[Compacted context]\n$summary",
)
```

注意:`StructuralSummaryStrategy` 内部还有超限丢弃循环(:37-42,`mutableResult.removeAt(0)`)——保持不动,它现在丢的是 ASSISTANT 摘要,行为不变。

- [x] **Step 4: 写失败测试(gateway 合并)并实现**

`AnthropicMessageMergeTest.kt`(InputMessage/MessageRole/TextContent 为 Anthropic DTO,以 `data/dto/anthropic` 实际包路径为准,grep `class InputMessage`):

```kotlin
package com.vibe.app.feature.agent.loop

import com.vibe.app.data.dto.anthropic.request.InputMessage
import com.vibe.app.data.dto.anthropic.request.MessageRole
import com.vibe.app.data.dto.anthropic.request.TextContent
import org.junit.Assert.assertEquals
import org.junit.Test

class AnthropicMessageMergeTest {

    @Test
    fun `consecutive same-role messages are merged into one`() {
        val messages = listOf(
            InputMessage(role = MessageRole.ASSISTANT, content = listOf(TextContent("summary 1"))),
            InputMessage(role = MessageRole.ASSISTANT, content = listOf(TextContent("summary 2"))),
            InputMessage(role = MessageRole.USER, content = listOf(TextContent("hi"))),
            InputMessage(role = MessageRole.USER, content = listOf(TextContent("tool results"))),
        )
        val merged = mergeConsecutiveSameRole(messages)
        assertEquals(2, merged.size)
        assertEquals(2, merged[0].content.size)
        assertEquals(MessageRole.ASSISTANT, merged[0].role)
        assertEquals(MessageRole.USER, merged[1].role)
    }

    @Test
    fun `alternating roles are untouched`() {
        val messages = listOf(
            InputMessage(role = MessageRole.USER, content = listOf(TextContent("a"))),
            InputMessage(role = MessageRole.ASSISTANT, content = listOf(TextContent("b"))),
        )
        assertEquals(messages, mergeConsecutiveSameRole(messages))
    }
}
```

实现(AnthropicMessagesAgentGateway.kt 顶层):

```kotlin
/**
 * Anthropic Messages API requires alternating user/assistant roles.
 * Compaction summaries can produce consecutive same-role messages —
 * merge their content blocks into a single message defensively.
 */
internal fun mergeConsecutiveSameRole(messages: List<InputMessage>): List<InputMessage> {
    val merged = mutableListOf<InputMessage>()
    for (msg in messages) {
        val last = merged.lastOrNull()
        if (last != null && last.role == msg.role) {
            merged[merged.size - 1] = last.copy(content = last.content + msg.content)
        } else {
            merged += msg
        }
    }
    return merged
}
```

`buildMessages`(:217)返回处改为 `return mergeConsecutiveSameRole(messages)`。若 `InputMessage` 不是 data class(无 copy),改用手动重建并在实施记录注明。

- [x] **Step 5: 跑测试**

Run: `./gradlew :app:testDebugUnitTest --tests "*.SummaryRoleAndPreambleTest" --tests "*.AnthropicMessageMergeTest" --tests "*.ToolResultTrimStrategyTest"`
Expected: 全部 PASS

- [x] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/feature/agent/loop/compaction/ \
        app/src/main/kotlin/com/vibe/app/feature/agent/loop/AnthropicMessagesAgentGateway.kt \
        app/src/test/kotlin/com/vibe/app/feature/agent/loop/
git commit -m "fix(compaction): assistant-role summaries, preamble preservation, role merge (opt task 2.4)"
```

**验收标准:** 摘要项为 ASSISTANT 角色且带 `[Compacted context]` 前缀;头部摘要经再次压缩不丢失(round-trip 测试);Anthropic 请求中不存在连续同角色消息。

---

## Task 2.5: 压缩链补 web 工具裁剪分支(评审 §2.2 C1)

**现状与证据:** `trimToolPayload`(`ToolResultTrimStrategy.kt:74-93`)对 8 种工具有裁剪分支,web 工具走 `else -> payload` 原样保留。**注意两个 payload 形状**(已核实):`web_search` 的 payload 是 **JsonArray**(`WebSearchTool.kt:42-51`),会被 :75 的 `if (payload !is JsonObject) return payload` 提前放行,所以 web_search 分支必须在该守卫**之前**;`fetch_web_page` 是 JsonObject `{title, content, url}`(`FetchWebPageTool.kt:39-43`),content 最大 8000 字符。

**改动文件:**
- Modify: `app/src/main/kotlin/com/vibe/app/feature/agent/loop/compaction/ToolResultTrimStrategy.kt`
- Modify: `app/src/test/kotlin/com/vibe/app/feature/agent/loop/compaction/ToolResultTrimStrategyTest.kt`

- [x] **Step 1: 写失败测试(追加到既有测试类,沿用其 helper 风格)**

```kotlin
@Test
fun `web_search array payload in older turns is trimmed to a note`() = runBlocking {
    val searchPayload = buildJsonArray {
        repeat(5) { i ->
            add(buildJsonObject {
                put("title", JsonPrimitive("result $i"))
                put("snippet", JsonPrimitive("snippet ".repeat(100)))
                put("url", JsonPrimitive("https://example.com/$i"))
            })
        }
    }
    val items = listOf(
        userItem("search something"),
        assistantItem("searching"),
        AgentConversationItem(role = AgentMessageRole.TOOL, toolName = "web_search", payload = searchPayload),
        userItem("recent turn"),
        assistantItem("ok"),
    )
    val result = strategy.compact(items, recentTurnCount = 1, tokenBudget = Int.MAX_VALUE)
    assertNotNull(result)
    val trimmed = result!!.items[2].payload!!.jsonObject
    val note = trimmed["note"]?.jsonPrimitive?.content
    assertEquals("[Search results trimmed (5 results) — run web_search again if needed]", note)
}

@Test
fun `fetch_web_page content in older turns is replaced, title and url kept`() = runBlocking {
    val items = listOf(
        userItem("read the page"),
        assistantItem("fetching"),
        AgentConversationItem(
            role = AgentMessageRole.TOOL,
            toolName = "fetch_web_page",
            payload = buildJsonObject {
                put("title", JsonPrimitive("Doc Title"))
                put("content", JsonPrimitive("m".repeat(8_000)))
                put("url", JsonPrimitive("https://example.com/doc"))
            },
        ),
        userItem("recent"),
        assistantItem("ok"),
    )
    val result = strategy.compact(items, recentTurnCount = 1, tokenBudget = Int.MAX_VALUE)
    assertNotNull(result)
    val trimmed = result!!.items[2].payload!!.jsonObject
    assertEquals("Doc Title", trimmed["title"]?.jsonPrimitive?.content)
    assertEquals("https://example.com/doc", trimmed["url"]?.jsonPrimitive?.content)
    assertEquals(
        "[Web page: https://example.com/doc, 8000 chars — trimmed, re-fetch if needed]",
        trimmed["content"]?.jsonPrimitive?.content,
    )
}
```

(测试文件需补 import:`kotlinx.serialization.json.buildJsonArray`。)

- [x] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "*.ToolResultTrimStrategyTest"`
Expected: 两个新测试 FAIL(payload 原样保留)

- [x] **Step 3: 实现**

`trimToolPayload` 重构(web_search 提到 JsonObject 守卫之前):

```kotlin
private fun trimToolPayload(toolName: String?, payload: JsonElement): JsonElement {
    when (toolName) {
        "web_search" -> return trimWebSearchPayload(payload)
        "fetch_web_page" -> if (payload is JsonObject) return trimFetchPagePayload(payload)
    }
    if (payload !is JsonObject) return payload
    return when (toolName) {
        // ……既有 8 个分支原样保留……
        else -> payload
    }
}

private fun trimWebSearchPayload(payload: JsonElement): JsonElement {
    val count = (payload as? JsonArray)?.size
    return buildJsonObject {
        put("note", JsonPrimitive("[Search results trimmed (${count ?: 0} results) — run web_search again if needed]"))
    }
}

private fun trimFetchPagePayload(payload: JsonObject): JsonElement {
    val url = payload["url"]?.jsonPrimitive?.content ?: "unknown"
    val chars = payload["content"]?.jsonPrimitive?.content?.length ?: 0
    return buildJsonObject {
        payload["title"]?.let { put("title", it) }
        put("url", JsonPrimitive(url))
        put("content", JsonPrimitive("[Web page: $url, $chars chars — trimmed, re-fetch if needed]"))
    }
}
```

- [x] **Step 4: 跑测试**

Run: `./gradlew :app:testDebugUnitTest --tests "*.ToolResultTrimStrategyTest"`
Expected: 全部 PASS(含存量用例)

- [x] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/feature/agent/loop/compaction/ToolResultTrimStrategy.kt \
        app/src/test/kotlin/com/vibe/app/feature/agent/loop/compaction/ToolResultTrimStrategyTest.kt
git commit -m "fix(compaction): trim web_search and fetch_web_page payloads in older turns (opt task 2.5)"
```

**验收标准:** 旧回合中 web_search(数组)与 fetch_web_page 的大 payload 被替换为占位对象;近期回合不受影响;既有测试全绿。

---

## Task 2.6: Web 拦截识别与结构化失败(评审 §2.2 B1/B2)

**现状与证据:** `WebViewContentExtractor` 只重写了 `onReceivedError`(网络层主帧错误 → resume 空串,:111-122),**没有 `onReceivedHttpError`** —— 403/429/验证码页被当正常页面;`WebSearchExecutor` 对 0 结果记 `"no results parsed"`(:35),全部失败时模型只看到笼统的 `"All search engines failed: ..."`(:42-44),无法区分被拦与真无结果。

**改动文件:**
- Create: `app/src/main/kotlin/com/vibe/app/feature/agent/tool/web/WebFailure.kt`
- Create: `app/src/main/kotlin/com/vibe/app/feature/agent/tool/web/BlockedPageDetector.kt`
- Modify: `app/src/main/kotlin/com/vibe/app/feature/agent/tool/web/WebViewContentExtractor.kt`
- Modify: `app/src/main/kotlin/com/vibe/app/feature/agent/tool/web/WebSearchExecutor.kt`
- Create: `app/src/test/kotlin/com/vibe/app/feature/agent/tool/web/BlockedPageDetectorTest.kt`

**接口(00-progress.md §4 已注册,Phase 5 复用):**

```kotlin
enum class WebFailureKind { BLOCKED, NO_RESULTS, TIMEOUT, NETWORK_ERROR }
data class EngineFailure(val engine: String, val kind: WebFailureKind, val detail: String)
class WebSearchFailedException(val failures: List<EngineFailure>) : RuntimeException(...)
class WebHttpBlockedException(val statusCode: Int) : RuntimeException(...)
object BlockedPageDetector { fun isBlockedPage(html: String): Boolean }
```

- [x] **Step 1: 写失败测试(检测器纯函数)**

```kotlin
package com.vibe.app.feature.agent.tool.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockedPageDetectorTest {

    @Test
    fun `cloudflare challenge page is detected`() {
        assertTrue(BlockedPageDetector.isBlockedPage("<html><title>Just a moment...</title><div class=\"cf-chl-widget\"></div></html>"))
    }

    @Test
    fun `google unusual traffic page is detected`() {
        assertTrue(BlockedPageDetector.isBlockedPage("<html>Our systems have detected unusual traffic from your computer network.</html>"))
    }

    @Test
    fun `baidu security verification page is detected`() {
        assertTrue(BlockedPageDetector.isBlockedPage("<html><title>百度安全验证</title>网络不给力，请稍后重试</html>"))
    }

    @Test
    fun `normal serp page is not flagged`() {
        assertFalse(BlockedPageDetector.isBlockedPage("<html><li class=\"b_algo\"><h2><a href=\"https://a.com\">Result</a></h2></li></html>"))
    }

    @Test
    fun `blank html is not flagged as blocked`() {
        assertFalse(BlockedPageDetector.isBlockedPage(""))
    }
}
```

- [x] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "*.BlockedPageDetectorTest"`
Expected: FAIL(类不存在)

- [x] **Step 3: 实现 WebFailure.kt 与 BlockedPageDetector.kt**

```kotlin
// WebFailure.kt
package com.vibe.app.feature.agent.tool.web

enum class WebFailureKind { BLOCKED, NO_RESULTS, TIMEOUT, NETWORK_ERROR }

data class EngineFailure(val engine: String, val kind: WebFailureKind, val detail: String) {
    fun describe(): String = when (kind) {
        WebFailureKind.BLOCKED -> "$engine blocked the request ($detail)"
        WebFailureKind.NO_RESULTS -> "$engine returned no parseable results"
        WebFailureKind.TIMEOUT -> "$engine timed out"
        WebFailureKind.NETWORK_ERROR -> "$engine network error ($detail)"
    }
}

class WebHttpBlockedException(val statusCode: Int) :
    RuntimeException("Main frame returned HTTP $statusCode")

class WebSearchFailedException(val failures: List<EngineFailure>) : RuntimeException(
    buildString {
        append("All search engines failed: ")
        append(failures.joinToString("; ") { it.describe() })
        append(". ")
        append(
            if (failures.any { it.kind == WebFailureKind.BLOCKED }) {
                "Search engines are rate-limiting this device right now — retry later, or proceed without web data."
            } else {
                "Consider rephrasing the query with different keywords."
            },
        )
    },
)
```

```kotlin
// BlockedPageDetector.kt
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
```

- [x] **Step 4: Extractor 感知 HTTP 错误**

`WebViewContentExtractor.loadAndEvaluate` 的 WebViewClient 增加(与 `onReceivedError` 并列;import `android.webkit.WebResourceResponse` 与 `kotlin.coroutines.resumeWithException`):

```kotlin
override fun onReceivedHttpError(
    view: WebView?,
    request: WebResourceRequest?,
    errorResponse: WebResourceResponse?,
) {
    if (request?.isForMainFrame == true && !finished) {
        val status = errorResponse?.statusCode ?: return
        if (status == 403 || status == 429 || status == 503) {
            finished = true
            webView.destroy()
            cont.resumeWithException(WebHttpBlockedException(status))
        }
    }
}
```

同时把 `onReceivedError` 的 `cont.resume("")`(:120)改为 `cont.resumeWithException(java.io.IOException(error?.description?.toString() ?: "WebView network error"))`,让 NETWORK_ERROR 可分类(`extractRawHtml`/`extract` 外层 `runCatching` 会把它变成 `Result.failure`,调用方语义不变——空串本来也走失败分支)。

- [x] **Step 5: Executor 结构化失败分类**

`WebSearchExecutor.search` 重写循环体(`kotlinx.coroutines.TimeoutCancellationException` 需要显式识别;非超时的 `CancellationException` 必须重新抛出,不得吞掉协程取消):

```kotlin
suspend fun search(query: String): Result<List<SearchResult>> {
    val failures = mutableListOf<EngineFailure>()

    for (engine in engines) {
        val url = engine.buildSearchUrl(query)
        val htmlResult = webViewExtractor.extractRawHtml(url)
        val error = htmlResult.exceptionOrNull()
        if (error != null) {
            if (error is kotlinx.coroutines.CancellationException &&
                error !is kotlinx.coroutines.TimeoutCancellationException
            ) throw error
            failures += when (error) {
                is WebHttpBlockedException ->
                    EngineFailure(engine.name, WebFailureKind.BLOCKED, "HTTP ${error.statusCode}")
                is kotlinx.coroutines.TimeoutCancellationException ->
                    EngineFailure(engine.name, WebFailureKind.TIMEOUT, "${WebConstants.WEBVIEW_TIMEOUT_MS / 1000}s")
                else ->
                    EngineFailure(engine.name, WebFailureKind.NETWORK_ERROR, error.message ?: "unknown")
            }
            continue
        }
        val html = htmlResult.getOrNull().orEmpty()
        val results = runCatching { engine.parseResults(html) }.getOrElse { e ->
            failures += EngineFailure(engine.name, WebFailureKind.NETWORK_ERROR, "parse error: ${e.message}")
            continue
        }
        if (results.isNotEmpty()) {
            Log.d(TAG, "${engine.name} returned ${results.size} results for: $query")
            return Result.success(results.take(MAX_RESULTS))
        }
        failures += if (BlockedPageDetector.isBlockedPage(html)) {
            EngineFailure(engine.name, WebFailureKind.BLOCKED, "captcha/anti-bot page")
        } else {
            EngineFailure(engine.name, WebFailureKind.NO_RESULTS, "no results parsed")
        }
    }

    return Result.failure(WebSearchFailedException(failures))
}
```

`WebSearchTool` 的 `onFailure` 分支无需改动(`e.message` 现在是可行动的结构化描述)。

- [x] **Step 6: 跑测试与编译**

Run: `./gradlew :app:testDebugUnitTest --tests "*.BlockedPageDetectorTest" && ./gradlew :app:compileDebugKotlin`
Expected: PASS + 编译通过

- [x] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/feature/agent/tool/web/ \
        app/src/test/kotlin/com/vibe/app/feature/agent/tool/web/BlockedPageDetectorTest.kt
git commit -m "feat(web): detect blocked pages and report structured search failures (opt task 2.6)"
```

**验收标准:** 主帧 403/429/503 触发 BLOCKED;0 结果 + 验证码特征 → BLOCKED,否则 NO_RESULTS;超时 → TIMEOUT;全失败时工具错误信息逐引擎给出原因与行动建议;协程取消不被吞。

---

## Task 2.7: 引擎熔断 + 渲染等待(评审 §2.2 B3/B4)

**现状与证据:** 引擎顺序硬编码、每次都从 Bing 开始(`WebSearchExecutor.kt:12-16`)——被拦的引擎每次白等最多 20s;`onPageFinished` 立即取 HTML(`WebViewContentExtractor.kt:100-109`),JS 后渲染的结果会漏。

**改动文件:**
- Create: `app/src/main/kotlin/com/vibe/app/feature/agent/tool/web/EngineCircuitBreaker.kt`
- Modify: `app/src/main/kotlin/com/vibe/app/feature/agent/tool/web/WebSearchExecutor.kt`
- Modify: `app/src/main/kotlin/com/vibe/app/feature/agent/tool/web/WebViewContentExtractor.kt`
- Modify: `app/src/main/kotlin/com/vibe/app/feature/agent/tool/web/WebSearchEngine.kt`(+ Bing/Baidu/Google 三个实现)
- Create: `app/src/test/kotlin/com/vibe/app/feature/agent/tool/web/EngineCircuitBreakerTest.kt`

**接口(00-progress.md §4 已注册):** `class EngineCircuitBreaker(cooldownMs, clock)`;`WebSearchEngine` 接口新增 `val resultsSelector: String`;`WebViewContentExtractor.extractRawHtml(url, waitForSelector: String? = null)`。

- [x] **Step 1: 写失败测试(熔断器,注入时钟)**

```kotlin
package com.vibe.app.feature.agent.tool.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineCircuitBreakerTest {

    @Test
    fun `engine is skipped during cooldown and usable after`() {
        var now = 0L
        val breaker = EngineCircuitBreaker(cooldownMs = 300_000, clock = { now })
        assertFalse(breaker.isOpen("Bing"))
        breaker.recordBlocked("Bing")
        assertTrue(breaker.isOpen("Bing"))
        assertFalse("other engines unaffected", breaker.isOpen("Baidu"))
        now = 299_999
        assertTrue(breaker.isOpen("Bing"))
        now = 300_000
        assertFalse(breaker.isOpen("Bing"))
    }
}
```

- [x] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "*.EngineCircuitBreakerTest"`
Expected: FAIL(类不存在)

- [x] **Step 3: 实现熔断器并接入 Executor**

```kotlin
// EngineCircuitBreaker.kt
package com.vibe.app.feature.agent.tool.web

/** In-memory cooldown for engines that returned a BLOCKED response. */
class EngineCircuitBreaker(
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val blockedUntil = mutableMapOf<String, Long>()

    @Synchronized
    fun recordBlocked(engine: String) {
        blockedUntil[engine] = clock() + cooldownMs
    }

    @Synchronized
    fun isOpen(engine: String): Boolean = clock() < (blockedUntil[engine] ?: 0L)

    companion object {
        const val DEFAULT_COOLDOWN_MS = 5 * 60 * 1000L
    }
}
```

`WebSearchExecutor` 增加字段 `private val circuitBreaker = EngineCircuitBreaker()`,循环开头改为:

```kotlin
val eligible = engines.filterNot { circuitBreaker.isOpen(it.name) }.ifEmpty { engines }
for (engine in eligible) {
```

并在 Task 2.6 产生 `WebFailureKind.BLOCKED` 的两处(HTTP blocked、验证码页)后追加 `circuitBreaker.recordBlocked(engine.name)`。

- [x] **Step 4: 渲染等待(选择器轮询)**

`WebSearchEngine` 接口加字段,三个实现补值:

```kotlin
interface WebSearchEngine {
    val name: String
    /** CSS selector that appears once results have rendered. */
    val resultsSelector: String
    fun buildSearchUrl(query: String): String
    fun parseResults(html: String): List<SearchResult>
}
// Bing:   override val resultsSelector = "li.b_algo"
// Baidu:  override val resultsSelector = "div.c-result, div.result, div.c-container"
// Google: override val resultsSelector = "div.g"
```

`WebViewContentExtractor.extractRawHtml` 增加参数并把选择器传进 `loadAndEvaluate`;`onPageFinished` 改为轮询后再取值(全部在主线程,`webView.postDelayed` 驱动;`org.json.JSONObject.quote` 转义选择器):

```kotlin
suspend fun extractRawHtml(url: String, waitForSelector: String? = null): Result<String> = runCatching {
    withTimeout(WebConstants.WEBVIEW_TIMEOUT_MS) {
        val script = "(function() { return document.documentElement.outerHTML; })();"
        loadAndEvaluate(url, script, waitForSelector)
    }
}

// loadAndEvaluate(url, javascript, waitForSelector = null) 内 onPageFinished:
override fun onPageFinished(view: WebView?, finishedUrl: String?) {
    if (finished) return
    if (waitForSelector == null) {
        grabResult()
        return
    }
    fun poll(attempt: Int) {
        if (finished) return
        val probe = "document.querySelector(${org.json.JSONObject.quote(waitForSelector)}) != null"
        webView.evaluateJavascript(probe) { present ->
            if (finished) return@evaluateJavascript
            if (present == "true" || attempt >= MAX_SELECTOR_POLLS) {
                grabResult()
            } else {
                webView.postDelayed({ poll(attempt + 1) }, SELECTOR_POLL_INTERVAL_MS)
            }
        }
    }
    poll(0)
}
```

其中 `grabResult()` 是把现有"evaluateJavascript(javascript) → unescape → destroy → resume"逻辑提取成的局部函数;常量 `MAX_SELECTOR_POLLS = 10`、`SELECTOR_POLL_INTERVAL_MS = 300L` 放 companion。Executor 调用处改为 `webViewExtractor.extractRawHtml(url, engine.resultsSelector)`。`fetch_web_page` 路径(`extract()`)不传选择器,行为不变。

- [x] **Step 5: 跑测试与编译**

Run: `./gradlew :app:testDebugUnitTest --tests "*.EngineCircuitBreakerTest" && ./gradlew :app:compileDebugKotlin`
Expected: PASS + 编译通过

- [x] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/feature/agent/tool/web/ \
        app/src/test/kotlin/com/vibe/app/feature/agent/tool/web/EngineCircuitBreakerTest.kt
git commit -m "feat(web): engine cooldown circuit breaker and render-wait polling (opt task 2.7)"
```

**验收标准:** BLOCKED 引擎 5 分钟内被跳过(全部冷却时退化为全试);搜索页在选择器出现后(或 3 秒兜底)才取 HTML;fetch_web_page 行为不变。

---

## Phase 完成检查

- [x] 全部 7 个 Task 的 checkbox 已勾选,每个 Task 至少一个独立 commit(9 个 Task commit + 2 个终审修复 commit,详见"实施记录")
- [x] 测试通过(重点:compaction 包全部测试)—— `./gradlew test`(全模块)在 vendored `build-tools/android-common-resources` **早于本分支既有**地编译失败,与 Phase 1 同因;本 phase 以 `:app:testDebugUnitTest`(含 compaction 包全部测试)+ `:build-engine:test` 为准,`--rerun-tasks` 强制全量重跑全绿
- [x] `./gradlew assembleDebug` 通过(`:app:assembleDebug` BUILD SUCCESSFUL)
- [ ] **人工验证清单**(Android 10+ 真机/模拟器)—— **待用户在真机执行**,不可自动化:
  - [ ] 配置任一 OpenAI 兼容 provider,连续多轮长对话(读大文件 + build 数次),诊断日志确认:压缩触发后下一次请求不带 `previous_response_id`,请求体为压缩后历史;
  - [ ] 让 agent 读取一个 >2000 行的文件(可先让它生成),确认返回带 `truncated/hint`,agent 能用 start_line/end_line 续读;
  - [ ] 让 agent 连续 web_search 数次直至某引擎被拦,确认错误消息逐引擎给出原因(blocked/no results/timeout),且被拦引擎在 5 分钟内不再被首选;
  - [ ] fetch_web_page 一个正常网页,功能不回归。
- [ ] 更新 `00-progress.md`:Phase 2 状态 → ✅ 已完成,填完成日期 —— **待真机 4 项验证后**;当前已更新为"代码完成·已审查·PR→dev,待真机验证"(比照 Phase 1)
- [x] 在下方"实施记录"追加总结行
- [x] docs commit(代码完成·已审查·待真机验证;非"mark complete",因设备验证未过)

## 实施记录(执行时追加)

| 日期 | 执行者 | 完成内容 | 偏离/备注 |
|------|--------|----------|-----------|
| 2026-07-04 | Claude Opus 4.8(subagent-driven-development 编排) | 7 个 Task 全部实现+逐 Task 审查通过,分支 `opt/phase-2-context-web-hotfix`(基线 dev@66fac86)。2.1 Responses 压缩路由(含收尾请求);2.2 read_project_file 三分支夹取+build 输出截断;2.3 模型摘要预算门控;2.4 ASSISTANT 角色摘要+前导组保留+同角色合并;2.5 web_search/fetch_web_page 裁剪;2.6 拦截识别+结构化失败;2.7 引擎熔断+渲染等待。 | **漂移修正(动手前 grep 重定位,均记录在案)**:(a) Phase 1 已删除 `OpenAIAPI.setToken/setAPIUrl` 并给 4 方法加 `token/apiUrl` 参数 → Task 2.3 的 Fake 按当前接口重写;(b) `MessageRole`/`TextContent` 实为 `...anthropic.common` 包,brief 写成 `.request` → Task 2.4 测试 import 已改。**计划外补强(合并前)**:2.1 收尾请求同步重置 previousResponseId;2.2 修复 `range.end` 在夹取后谎报(抽 `deliveredRangeEnd`/`logicalLineCount` 纯函数+5 测试)。 |
| 2026-07-04 | Claude Opus 4.8(整支终审+修复) | 整支 whole-branch review(Opus)= Ready to merge **With fixes**。修复 1 个合并前回归 + 1 个 Minor,复审 Approved。最终 HEAD `3411fb5`(11 commits)。 | **终审发现的跨任务回归(已修 @fe266e0)**:Task 2.4 改摘要为 ASSISTANT 角色后,压缩后 fullConversation 可能以 assistant 开头 → Anthropic "首条须 user" 400(本分支引入,恰背离 2.4 初衷);修法:`AnthropicMessagesAgentGateway.ensureLeadingUserMessage` 守卫(merge 后)+ 3 测试。**Minor(已修 @3411fb5)**:Responses 诊断 `messageCount` 在发送全量历史时少报 → 复用 `selectResponsesInput` 结果计数。其余 roll-up 项(见 SDD 账本)终审判 follow-up/won't-fix,非阻塞。**验证**:`:app:testDebugUnitTest`+`:build-engine:test`+`:app:assembleDebug` 全绿(`--rerun-tasks` 强制)。**待办**:真机 4 项人工清单(用户执行)。 |
