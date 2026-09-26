# Phase 1: Agent Loop 可靠性止血 实施计划

> **执行者须知(任何模型/会话通用):**
> 1. 开工前先读同目录 `00-progress.md`,确认前置依赖与当前进度;
> 2. 本文 file:line 基于 dev@be1f944,动手前用 grep 重新定位;
> 3. 每完成一个 Task:勾选本文 checkbox → 跑该 Task"验证"→ 独立 commit → 更新 00-progress.md 状态表;
> 4. 任何偏离写入文末"实施记录"表,禁止静默偏离;
> 5. 推荐使用 superpowers:executing-plans 或 superpowers:subagent-driven-development 技能执行。

**目标:** 消除 agent loop 的七类"单点即全灭"故障:模型请求零重试、SSE 截断被当成功、超时一刀切、取消丢快照、工具挂死冻结整轮、edit 工具假成功、参数解析静默降级;并修复 API 单例凭证竞态与 release 日志泄漏。

**评审依据:** `docs/optimization-review-2026-07.md` §4(问题 1、2、3、4、5、6、7、11、12)

**前置依赖:** 无(本 phase 是全工程的第一步)

**涉及模块:** `app/src/main/kotlin/com/vibe/app/feature/agent/`(loop、tool)、`app/src/main/kotlin/com/vibe/app/data/network/`、`app/src/main/kotlin/com/vibe/app/data/dto/`

**建议分支:** `opt/phase-1-reliability`

**Task 依赖顺序:** 1.1 → 1.2(1.2 产生的错误依赖 1.1 的分类与重试);1.7 依赖 1.1(复用 `Completed` 事件扩展)。其余(1.3、1.4、1.5、1.6、1.8、1.9)相互独立,可任意顺序执行。

---

## Task 1.1: 模型请求可重试化(错误分类 + 迭代内指数退避)

**现状与证据:**
- `AgentModelEvent.Failed` 只有 `message: String` 一个字段(`feature/agent/AgentContracts.kt:63-65`);
- coordinator 收到 Failed 即 `emit(LoopFailed)` + `return@flow`,整轮终止(`feature/agent/loop/DefaultAgentLoopCoordinator.kt:247-272`);
- 各 API impl 已把 HTTP 状态码放进错误 chunk:Anthropic 走 `ErrorResponseChunk(ErrorDetail(type, message))`(`data/network/AnthropicAPIImpl.kt:125`,但**没带状态码**);OpenAI ChatCompletions 的 `ErrorDetail.code` 已是状态码字符串(`data/network/OpenAIAPIImpl.kt:317-326`);Responses 走 `ResponseErrorEvent(message, code)`(`OpenAIAPIImpl.kt:427`)。
- 全库无任何 retry/backoff 逻辑。

**改动文件:**
- Modify: `app/src/main/kotlin/com/vibe/app/feature/agent/AgentContracts.kt`(Failed 事件加字段)
- Modify: `app/src/main/kotlin/com/vibe/app/data/dto/anthropic/response/ErrorDetail.kt`(加 statusCode/retryAfterSeconds)
- Modify: `app/src/main/kotlin/com/vibe/app/data/network/AnthropicAPIImpl.kt`(错误分支填状态码与 Retry-After)
- Modify: `app/src/main/kotlin/com/vibe/app/data/dto/openai/response/ChatCompletionChunk.kt`(ErrorDetail 加 retryAfterSeconds)
- Modify: `app/src/main/kotlin/com/vibe/app/data/network/OpenAIAPIImpl.kt`(错误分支填 Retry-After)
- Create: `app/src/main/kotlin/com/vibe/app/feature/agent/loop/ModelFailureClassifier.kt`
- Modify: 5 个 gateway 的 Failed 构造处:`AnthropicMessagesAgentGateway.kt:193-196`、`KimiChatCompletionsAgentGateway.kt:141-148`、`QwenChatCompletionsAgentGateway.kt`、`DeepSeekChatCompletionsAgentGateway.kt`、`OpenAiResponsesAgentGateway.kt`(各自的 error→Failed 映射处,grep `AgentModelEvent.Failed(`)
- Modify: `app/src/main/kotlin/com/vibe/app/feature/agent/loop/DefaultAgentLoopCoordinator.kt:165-272`(迭代内重试环)
- Test: `app/src/test/kotlin/com/vibe/app/feature/agent/loop/ModelFailureClassifierTest.kt`

**接口(后续 Task 依赖):** `AgentModelEvent.Failed(message, statusCode: Int?, retryable: Boolean, retryAfterSeconds: Int?)`;错误类型字符串常量 `"stream_interrupted"`(Task 1.2 使用,分类为可重试)。

**步骤:**

- [ ] **Step 1: 写失败分类器的失败测试**

创建 `app/src/test/kotlin/com/vibe/app/feature/agent/loop/ModelFailureClassifierTest.kt`:

```kotlin
package com.vibe.app.feature.agent.loop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelFailureClassifierTest {

    @Test
    fun `429 and 5xx are retryable`() {
        assertTrue(ModelFailureClassifier.isRetryable(statusCode = 429, errorType = "http_error"))
        assertTrue(ModelFailureClassifier.isRetryable(statusCode = 500, errorType = "api_error"))
        assertTrue(ModelFailureClassifier.isRetryable(statusCode = 529, errorType = "api_error"))
        assertTrue(ModelFailureClassifier.isRetryable(statusCode = 408, errorType = "http_error"))
    }

    @Test
    fun `4xx client errors are not retryable`() {
        assertFalse(ModelFailureClassifier.isRetryable(statusCode = 400, errorType = "http_error"))
        assertFalse(ModelFailureClassifier.isRetryable(statusCode = 401, errorType = "api_error"))
        assertFalse(ModelFailureClassifier.isRetryable(statusCode = 404, errorType = "http_error"))
    }

    @Test
    fun `network and stream interruption errors are retryable regardless of status`() {
        assertTrue(ModelFailureClassifier.isRetryable(statusCode = null, errorType = "network_error"))
        assertTrue(ModelFailureClassifier.isRetryable(statusCode = null, errorType = "stream_interrupted"))
    }

    @Test
    fun `unknown error without status is not retryable`() {
        assertFalse(ModelFailureClassifier.isRetryable(statusCode = null, errorType = "provider_error"))
        assertFalse(ModelFailureClassifier.isRetryable(statusCode = null, errorType = null))
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
./gradlew :app:testDebugUnitTest --tests "com.vibe.app.feature.agent.loop.ModelFailureClassifierTest"
```
预期:编译失败(`ModelFailureClassifier` 不存在)。

- [ ] **Step 3: 实现分类器**

创建 `app/src/main/kotlin/com/vibe/app/feature/agent/loop/ModelFailureClassifier.kt`:

```kotlin
package com.vibe.app.feature.agent.loop

/**
 * Classifies model-request failures into retryable (transient) vs fatal.
 * Retryable: rate limits, server errors, network hiccups, interrupted SSE streams.
 * Fatal: auth errors, bad requests, anything we cannot fix by waiting.
 */
object ModelFailureClassifier {

    const val TYPE_STREAM_INTERRUPTED = "stream_interrupted"

    private val retryableTypes = setOf("network_error", TYPE_STREAM_INTERRUPTED)

    fun isRetryable(statusCode: Int?, errorType: String?): Boolean {
        if (errorType in retryableTypes) return true
        val code = statusCode ?: return false
        return code == 408 || code == 429 || code >= 500
    }
}
```

- [ ] **Step 4: 运行确认通过**

```bash
./gradlew :app:testDebugUnitTest --tests "com.vibe.app.feature.agent.loop.ModelFailureClassifierTest"
```
预期:PASS(4 个测试)。

- [ ] **Step 5: 扩展 Failed 事件与错误 DTO**

`AgentContracts.kt:63-65` 的 Failed 改为:

```kotlin
    data class Failed(
        val message: String,
        val statusCode: Int? = null,
        val retryable: Boolean = false,
        val retryAfterSeconds: Int? = null,
    ) : AgentModelEvent
```

`data/dto/anthropic/response/ErrorDetail.kt` 加两个仅本地填充的字段(有默认值,不影响反序列化):

```kotlin
@Serializable
data class ErrorDetail(
    @SerialName("type")
    val type: String,
    @SerialName("message")
    val message: String,
    // Filled locally by AnthropicAPIImpl from the HTTP response; never sent by the server.
    val statusCode: Int? = null,
    val retryAfterSeconds: Int? = null,
)
```

`data/dto/openai/response/ChatCompletionChunk.kt:79-87` 的 ErrorDetail 同样追加 `val retryAfterSeconds: Int? = null`(`code` 字段已存在,继续承载状态码字符串)。

- [ ] **Step 6: API impl 填充状态码与 Retry-After**

`AnthropicAPIImpl.kt` 非 2xx 分支(:104-127)的 emit 改为:

```kotlin
                    emit(
                        ErrorResponseChunk(
                            error = ErrorDetail(
                                type = "api_error",
                                message = errorMessage,
                                statusCode = response.status.value,
                                retryAfterSeconds = response.headers["Retry-After"]?.toIntOrNull(),
                            ),
                        ),
                    )
```

网络异常分支(:146-157)保持 `type = "network_error"`、statusCode 为 null。`OpenAIAPIImpl.kt` 的 `streamChatCompletion`(:297-327)与 `streamQwenChatCompletion`(:88-101)非 2xx 分支在 ErrorDetail 上补 `retryAfterSeconds = response.headers["Retry-After"]?.toIntOrNull()`。

- [ ] **Step 7: 五个 gateway 构造带分类的 Failed**

Anthropic gateway(`AnthropicMessagesAgentGateway.kt:193-196`):

```kotlin
                is ErrorResponseChunk -> {
                    trace.markFailed("provider_error", chunk.error.message)
                    emit(
                        AgentModelEvent.Failed(
                            message = chunk.error.message,
                            statusCode = chunk.error.statusCode,
                            retryable = ModelFailureClassifier.isRetryable(chunk.error.statusCode, chunk.error.type),
                            retryAfterSeconds = chunk.error.retryAfterSeconds,
                        ),
                    )
                }
```

Kimi/Qwen/DeepSeek 三个 ChatCompletions gateway 的错误路径都是 `chunk.error != null → streamError = ...`(如 `KimiChatCompletionsAgentGateway.kt:111-115`、:141-148)。把 `streamError` 从 `String?` 改为 `ErrorDetail?`(保存整个 `chunk.error`),emit 处:

```kotlin
        streamError?.let { error ->
            if (requestContext != null) {
                diagnosticLogger.logModelResponse(requestContext, trace, success = false)
                diagnosticLogger.logLatencyBreakdown(requestContext, trace)
            }
            val status = error.code?.toIntOrNull()
            emit(
                AgentModelEvent.Failed(
                    message = error.message,
                    statusCode = status,
                    retryable = ModelFailureClassifier.isRetryable(status, error.type),
                    retryAfterSeconds = error.retryAfterSeconds,
                ),
            )
            return@flow
        }
```

OpenAI Responses gateway 对 `ResponseErrorEvent` 同理(`code` 是字符串,`toIntOrNull()` 后分类)。三个文件的改法完全一致,不要漏掉任何一个(grep `AgentModelEvent.Failed(` 确认全部换完)。

- [ ] **Step 8: coordinator 迭代内重试环**

`DefaultAgentLoopCoordinator.kt`:把每次迭代的"缓冲区声明 + streamTurn/collect"段(:179-251)改造为重试环。关键点:每次 attempt 清空缓冲;仅当失败可重试且未超次数时重试;重试提示走 ThinkingDelta(进思考区,不污染正文)。

```kotlin
                val pendingToolResults = mutableListOf<AgentToolResult>()
                val pendingCalls = mutableListOf<com.vibe.app.feature.agent.AgentToolCall>()
                val outputBuilder = StringBuilder()
                var failure: AgentModelEvent.Failed? = null
                var turnReasoningContent: String? = null

                val effectivePolicy = /* 原逻辑不变 (:187-191) */
                val compactionResult = /* 原逻辑不变 (:193-211) */

                var attempt = 0
                while (true) {
                    pendingCalls.clear()
                    outputBuilder.clear()
                    failure = null
                    turnReasoningContent = null

                    agentModelGateway.streamTurn(
                        AgentModelRequest(/* 原参数不变 (:214-223) */),
                    ).collect { event ->
                        when (event) {
                            is AgentModelEvent.ThinkingDelta -> emit(AgentLoopEvent.ThinkingDelta(iteration, event.delta))
                            is AgentModelEvent.OutputDelta -> {
                                outputBuilder.append(event.delta)
                                emit(AgentLoopEvent.OutputDelta(iteration, event.delta))
                            }
                            is AgentModelEvent.ToolCallReady -> {
                                pendingCalls += event.call
                                emit(AgentLoopEvent.ToolCallDiscovered(iteration, event.call))
                            }
                            is AgentModelEvent.Completed -> {
                                previousResponseId = event.responseId ?: previousResponseId
                                if (event.reasoningContent != null) turnReasoningContent = event.reasoningContent
                            }
                            is AgentModelEvent.Failed -> {
                                failure = event
                            }
                        }
                    }

                    val f = failure
                    if (f == null || !f.retryable || attempt >= MAX_MODEL_RETRIES) break

                    attempt++
                    val backoffMs = RETRY_DELAYS_MS[attempt - 1]
                    val delayMs = maxOf(f.retryAfterSeconds?.times(1000L) ?: 0L, backoffMs)
                    emit(
                        AgentLoopEvent.ThinkingDelta(
                            iteration,
                            "\n[Transient model error, retrying $attempt/$MAX_MODEL_RETRIES in ${delayMs / 1000}s: ${f.message.take(120)}]\n",
                        ),
                    )
                    request.diagnosticContext?.copy(platformUid = request.platform.uid)?.let { ctx ->
                        diagnosticLogger.logAgentLoopEvent(
                            context = ctx,
                            action = "model_retry",
                            level = DiagnosticLevels.WARN,
                            summary = "Retry $attempt/$MAX_MODEL_RETRIES after: ${f.message.take(120)}",
                            payload = buildJsonObject {
                                put("action", "model_retry")
                                put("iteration", iteration)
                                put("attempt", attempt)
                                put("statusCode", f.statusCode ?: -1)
                                put("delayMs", delayMs)
                            },
                        )
                    }
                    kotlinx.coroutines.delay(delayMs)
                }

                val finalFailure = failure
                if (finalFailure != null) {
                    // 原 :253-272 的 loop_failed 日志与 LoopFailed emit,message 取 finalFailure.message
                    ...
                    emit(AgentLoopEvent.LoopFailed(message = finalFailure.message, iteration = iteration))
                    return@flow
                }
```

companion 中新增:

```kotlin
        private const val MAX_MODEL_RETRIES = 2
        private val RETRY_DELAYS_MS = listOf(1_000L, 4_000L)
```

**已知取舍(写进代码注释):** 若失败前已流出部分正文,重试会把重新生成的正文追加在其后,UI 上可能出现重复段落。相比"整轮作废",这是可接受的次要代价;彻底解决需要 UI 侧"重置当前消息"事件,不在本 phase 范围。

- [ ] **Step 9: 全量单测 + 编译**

```bash
./gradlew :app:testDebugUnitTest && ./gradlew assembleDebug
```
预期:全部 PASS,编译成功。

- [ ] **Step 10: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/feature/agent/ app/src/main/kotlin/com/vibe/app/data/ app/src/test/kotlin/com/vibe/app/feature/agent/loop/ModelFailureClassifierTest.kt
git commit -m "feat(agent): retry transient model failures with backoff (opt task 1.1)"
```

**验收标准:** 429/5xx/网络异常触发最多 2 次退避重试(优先 Retry-After),思考区可见重试提示;401/400 等不重试直接 LoopFailed;`ModelFailureClassifierTest` 全绿。

---

## Task 1.2: SSE 终止事件校验(截断不再伪装成功)

**现状与证据:** 三个流式读取循环都是 `readUTF8Line() ?: break`(`AnthropicAPIImpl.kt:133`、`OpenAIAPIImpl.kt:333`、`OpenAIAPIImpl.kt:435`)——连接中断时循环安静退出,不 emit 任何错误;coordinator collect 正常结束后把截断文本当完整回答存库。Anthropic 的终止信号是 `message_stop` chunk(`MessageStopResponseChunk`);ChatCompletions 是 `data: [DONE]`(`handleChatCompletionSseEvent` 返回 true,:507-509)或 `finish_reason` 非空;Responses 是 `[DONE]` 或 `ResponseCompletedEvent`(`data/dto/openai/response/ResponsesStreamEvent.kt:134-135`,`@SerialName("response.completed")`)。

**改动文件:**
- Modify: `app/src/main/kotlin/com/vibe/app/data/network/AnthropicAPIImpl.kt:129-144`
- Modify: `app/src/main/kotlin/com/vibe/app/data/network/OpenAIAPIImpl.kt`(`streamChatCompletion` :329-345、`streamQwenChatCompletion` :103-118、`streamResponses` :431-447)

**接口:** 消费 Task 1.1 的 `ModelFailureClassifier.TYPE_STREAM_INTERRUPTED`(该类型被分类为可重试,因此截断会自动走重试)。

**步骤:**

- [ ] **Step 1: Anthropic 流终止检测**

`AnthropicAPIImpl.kt` SSE 读取段(:129-144)改为:

```kotlin
                // Success - read SSE stream
                val channel = response.bodyAsChannel()
                val eventLines = mutableListOf<String>()
                var sawTerminal = false
                val trackingEmit: suspend (MessageResponseChunk) -> Unit = { chunk ->
                    if (chunk is com.vibe.app.data.dto.anthropic.response.MessageStopResponseChunk) {
                        sawTerminal = true
                    }
                    emit(chunk)
                }
                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break
                    if (line.isBlank()) {
                        handleAnthropicSseEvent(endpoint, eventLines, trackingEmit)
                        eventLines.clear()
                        continue
                    }
                    eventLines += line
                }
                if (eventLines.isNotEmpty()) {
                    handleAnthropicSseEvent(endpoint, eventLines, trackingEmit)
                }
                if (!sawTerminal) {
                    emit(
                        ErrorResponseChunk(
                            error = ErrorDetail(
                                type = "stream_interrupted",
                                message = "SSE stream ended without message_stop — response was truncated by a dropped connection.",
                            ),
                        ),
                    )
                }
```

(import `MessageStopResponseChunk` 到文件头部,不用全限定名。)

- [ ] **Step 2: ChatCompletions 两个方法的终止检测**

`streamChatCompletion`(:329-345)与 `streamQwenChatCompletion`(:103-118)结构相同,统一改为:

```kotlin
                val channel = response.bodyAsChannel()
                val eventLines = mutableListOf<String>()
                var sawTerminal = false
                val trackingEmit: suspend (ChatCompletionChunk) -> Unit = { chunk ->
                    if (chunk.choices?.firstOrNull()?.finishReason != null) sawTerminal = true
                    emit(chunk)
                }
                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break
                    if (line.isBlank()) {
                        val shouldStop = handleChatCompletionSseEvent(endpoint, eventLines, trackingEmit)
                        eventLines.clear()
                        if (shouldStop) {
                            sawTerminal = true
                            break
                        }
                        continue
                    }
                    eventLines += line
                }
                if (eventLines.isNotEmpty()) {
                    handleChatCompletionSseEvent(endpoint, eventLines, trackingEmit)
                }
                if (!sawTerminal) {
                    emit(
                        ChatCompletionChunk(
                            error = ErrorDetail(
                                message = "SSE stream ended without [DONE]/finish_reason — response was truncated.",
                                type = "stream_interrupted",
                            ),
                        ),
                    )
                }
```

- [ ] **Step 3: Responses 方法的终止检测**

`streamResponses`(:431-447)同法:`trackingEmit` 检测 `chunk is ResponseCompletedEvent`,`[DONE]`(shouldStop)同样置位;未终止时 `emit(ResponseErrorEvent(message = "SSE stream ended without response.completed — response was truncated.", code = "stream_interrupted"))`。注意 `OpenAiResponsesAgentGateway` 把 `ResponseErrorEvent.code` 转 Int 失败得 null,错误类型分类需要 gateway 侧把 `code == "stream_interrupted"` 映射为 `errorType`——在该 gateway 的 Failed 构造处(Task 1.1 Step 7 已改)传 `ModelFailureClassifier.isRetryable(status, event.code)`(classifier 对 `"stream_interrupted"` 字符串生效,无论它来自 type 还是 code 字段)。

- [ ] **Step 4: 编译 + 全量单测**

```bash
./gradlew :app:testDebugUnitTest && ./gradlew assembleDebug
```
预期:PASS。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/data/network/ app/src/main/kotlin/com/vibe/app/feature/agent/loop/OpenAiResponsesAgentGateway.kt
git commit -m "fix(network): detect interrupted SSE streams instead of silent truncation (opt task 1.2)"
```

**验收标准:** 流中断(未见终止信号)时 emit `stream_interrupted` 错误 → gateway 产出 `retryable=true` 的 Failed → coordinator 自动重试;不再出现"半截回答被标记成功存库"。

---

## Task 1.3: 网络超时分级

**现状与证据:** `NetworkClient.kt:32-36,47` connect/socket/request 三个超时统一 5 分钟。配错 endpoint 要等 5 分钟才报错;思考型模型长回答超 5 分钟被 requestTimeout 硬切(结合 1.2 修复前还被当成功)。

**改动文件:**
- Modify: `app/src/main/kotlin/com/vibe/app/data/network/NetworkClient.kt:32-36,46-48`
- Modify: `app/src/main/kotlin/com/vibe/app/data/network/OpenAIAPIImpl.kt`(`completeQwenChatCompletion` 加 per-request 超时)

**步骤:**

- [ ] **Step 1: 分级超时**

`NetworkClient.kt`:

```kotlin
            install(HttpTimeout) {
                // Fail fast on unreachable endpoints.
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                // SSE liveness: if no bytes arrive for this long, the stream is dead.
                socketTimeoutMillis = SOCKET_TIMEOUT_MS
                // No overall request timeout — streaming responses legitimately run for many minutes.
                requestTimeoutMillis = null
            }
```

companion:

```kotlin
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val SOCKET_TIMEOUT_MS = 120_000L
```

- [ ] **Step 2: 非流式调用恢复整体超时**

`completeQwenChatCompletion`(`OpenAIAPIImpl.kt:171-176`)是非流式(压缩摘要用),给它 per-request 上限,防止无限挂:

```kotlin
import io.ktor.client.plugins.timeout

            networkClient().preparePost(endpoint) {
                timeout { requestTimeoutMillis = 300_000 }
                contentType(ContentType.Application.Json)
                accept(ContentType.Application.Json)
                setBody(requestBody)
                token?.let { bearerAuth(it) }
            }
```

- [ ] **Step 3: 验证**

```bash
./gradlew assembleDebug
```
预期:编译成功。设备验证归入 Phase 完成检查(配一个不可达 apiUrl,期望 ~15 秒内报连接错误而不是 5 分钟)。

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/data/network/
git commit -m "fix(network): tiered timeouts - fast connect, SSE liveness, no stream cap (opt task 1.3)"
```

**验收标准:** 不可达 endpoint 约 15s 报错;SSE 静默 120s 判死(经 1.2 转为 stream_interrupted → 重试);长流式回答不再被 5 分钟硬切。

---

## Task 1.4: 快照落盘 NonCancellable

**现状与证据:** FINALIZE 在 flow 的 finally 中(`DefaultAgentLoopCoordinator.kt:522-604`),`snapshotHandle.commit()/finalize()`、`outlineGenerator.regenerate()` 都是挂起函数;`AgentSessionManager.stopSession` 直接 `job.cancel()`(`AgentSessionManager.kt:178-182`)。取消后 finally 里第一个挂起点抛 `CancellationException`,被 `runCatching` 吞掉——本轮工具已改的文件没有对应快照,undo 链静默断裂。

**改动文件:**
- Modify: `app/src/main/kotlin/com/vibe/app/feature/agent/loop/DefaultAgentLoopCoordinator.kt:522-604`

**步骤:**

- [ ] **Step 1: NonCancellable 包裹 FINALIZE**

finally 块整体包一层(块内只有 snapshot/outline/diagnostic 调用,没有 `emit`,所以合法——**不要**在 NonCancellable 里加 emit):

```kotlin
        } finally {
            // ─── FINALIZE ─────────────────────────────────────────────────────────
            // Must survive job.cancel() from stopSession — the turn's file mutations
            // already happened, so the snapshot MUST be committed or undo breaks.
            if (turnContext != null) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    runCatching {
                        /* 原 :525-588 内容原样搬入,不改 */
                    }.onFailure { e ->
                        /* 原 :589-602 内容原样搬入,不改 */
                    }
                }
            }
        }
```

(顶部 import `kotlinx.coroutines.NonCancellable` 与 `kotlinx.coroutines.withContext`,代码里用短名。)

- [ ] **Step 2: 验证**

```bash
./gradlew :app:testDebugUnitTest --tests "com.vibe.app.feature.project.snapshot.*" && ./gradlew assembleDebug
```
预期:既有快照测试全绿,编译成功。

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/feature/agent/loop/DefaultAgentLoopCoordinator.kt
git commit -m "fix(agent): finalize turn snapshot under NonCancellable so stop keeps undo intact (opt task 1.4)"
```

**验收标准:** 会话运行中(已发生文件写入)点停止,项目快照列表仍生成本轮 TURN 快照,undo 可回滚本轮修改。(设备验证在 Phase 完成检查执行。)

---

## Task 1.5: 工具执行超时

**现状与证据:** `tool.execute` 只有 `runCatching` 包裹、无超时(`DefaultAgentLoopCoordinator.kt:340-359`)。跨进程的 `launch_app`/`inspect_ui`(AIDL)与持 `BuildMutex` 全局锁的 build(`feature/agent/service/BuildMutex.kt`)挂死会冻结整轮,用户只能手动取消(在 1.4 修复前还连带丢快照)。

**改动文件:**
- Modify: `app/src/main/kotlin/com/vibe/app/feature/agent/loop/DefaultAgentLoopCoordinator.kt:340-359` + companion

**步骤:**

- [ ] **Step 1: 按工具类型包 withTimeout**

替换 :340-359 的 `runCatching` 段。注意异常次序:`TimeoutCancellationException` 必须在 `CancellationException` **之前** catch(前者是后者的子类);外部真取消必须重新抛出,否则破坏协程取消:

```kotlin
                    val result = try {
                        kotlinx.coroutines.withTimeout(toolTimeoutMillis(call.name)) {
                            tool.execute(
                                call = call,
                                context = com.vibe.app.feature.agent.AgentToolContext(
                                    chatId = request.chatId,
                                    platformUid = request.platform.uid,
                                    iteration = iteration,
                                    projectId = request.projectId ?: "",
                                ),
                            )
                        }
                    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                        AgentToolResult(
                            toolCallId = call.id,
                            toolName = call.name,
                            output = buildJsonObject {
                                put(
                                    "error",
                                    JsonPrimitive(
                                        "Tool '${call.name}' timed out after ${toolTimeoutMillis(call.name) / 1000}s. " +
                                            "Do not immediately retry the same call — try a different approach.",
                                    ),
                                )
                            },
                            isError = true,
                        )
                    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AgentToolResult(
                            toolCallId = call.id,
                            toolName = call.name,
                            output = buildJsonObject {
                                put("error", JsonPrimitive(e.message ?: "Tool execution failed"))
                            },
                            isError = true,
                        )
                    }
```

companion 加超时表(BuildMutex 的 `Mutex.withLock` 在取消时于 finally 释放锁,超时安全):

```kotlin
        private val TOOL_TIMEOUTS_MS: Map<String, Long> = mapOf(
            "run_build_pipeline" to 10 * 60_000L,
            "launch_app" to 45_000L,
            "inspect_ui" to 20_000L,
            "interact_ui" to 30_000L,
            "close_app" to 15_000L,
            "web_search" to 90_000L,   // worst case: 3 engines x 20s + parsing
            "fetch_web_page" to 40_000L,
        )
        private const val DEFAULT_TOOL_TIMEOUT_MS = 60_000L

        private fun toolTimeoutMillis(toolName: String): Long =
            TOOL_TIMEOUTS_MS[toolName] ?: DEFAULT_TOOL_TIMEOUT_MS
```

(companion 里放 `private fun` 需要挪进 `companion object` 主体;或直接作为类私有方法放 companion 外,两者皆可,保持文件内一致风格。)

- [ ] **Step 2: 验证**

```bash
./gradlew :app:testDebugUnitTest && ./gradlew assembleDebug
```
预期:PASS。

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/feature/agent/loop/DefaultAgentLoopCoordinator.kt
git commit -m "feat(agent): per-tool execution timeouts so a hung tool cannot freeze the loop (opt task 1.5)"
```

**验收标准:** 任一工具超过其时限即返回 isError 结果(loop 继续,模型可改道);外部取消(stopSession)仍正常传播。

---

## Task 1.6: edit_project_file 语义修复(TDD)

**现状与证据:** `FileTools.kt:224-266`——未匹配的 edit 只记 `matched:false` 继续;全部未匹配也**无条件写回文件并返回成功**(:258-265);`replaceFirst`(:249)对多处出现的 old_string 静默替换第一处,可能改错位置。模型以为改上了 → 直接 build → 白付一次全量构建。

**改动文件:**
- Modify: `app/src/main/kotlin/com/vibe/app/feature/agent/tool/FileTools.kt:182-267`(EditProjectFileTool)
- Test: `app/src/test/kotlin/com/vibe/app/feature/agent/tool/EditProjectFileToolTest.kt`(新建)

**目标行为规格:**
1. 每条 edit 统计 `occurrences`;
2. `occurrences == 0` → 该条 `matched=false, applied=false`;
3. `occurrences > 1` 且未指定 `replace_all:true` → 不应用,`applied=false, reason="ambiguous: N occurrences, provide longer old_string or set replace_all"`;
4. `replace_all:true` → 全部替换;否则(occurrences==1)替换该一处;
5. 所有 edit 都未应用 → **不写文件**,返回 `isError=true`;部分应用 → 写文件、`isError=false`,结果含 `applied_count`/`failed_count` 与逐条明细;
6. schema 的 edit item 增加可选 `replace_all` 布尔字段。

**步骤:**

- [ ] **Step 1: 写失败测试**

创建 `app/src/test/kotlin/com/vibe/app/feature/agent/tool/EditProjectFileToolTest.kt`(自带内存 workspace fake,不复用 `FakeWorkspace`——它的 read/write 是 `error("not used")`):

```kotlin
package com.vibe.app.feature.agent.tool

import com.vibe.app.data.database.entity.Project
import com.vibe.app.feature.agent.AgentToolCall
import com.vibe.app.feature.agent.AgentToolContext
import com.vibe.app.feature.project.ProjectManager
import com.vibe.app.feature.project.ProjectWorkspace
import com.vibe.build.engine.model.BuildResult
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditProjectFileToolTest {

    private class MemoryWorkspace(initial: Map<String, String>) : ProjectWorkspace {
        val files = initial.toMutableMap()
        override val projectId: String = "test"
        override val rootDir: File = File("/tmp/unused")
        override val project: Project get() = error("not used")
        override suspend fun readTextFile(relativePath: String): String =
            files[relativePath] ?: error("no file: $relativePath")
        override suspend fun writeTextFile(relativePath: String, content: String) {
            files[relativePath] = content
        }
        override suspend fun deleteFile(relativePath: String) = error("not used")
        override suspend fun listFiles(): List<String> = files.keys.toList()
        override suspend fun cleanBuildCache() = error("not used")
        override suspend fun buildProject(): BuildResult = error("not used")
        override suspend fun resolveFile(relativePath: String): File = File(rootDir, relativePath)
    }

    private class MemoryProjectManager(private val ws: MemoryWorkspace) : ProjectManager {
        override suspend fun createProject(enabledPlatforms: List<String>, name: String?): Project = error("not used")
        override suspend fun openWorkspace(projectId: String): ProjectWorkspace = ws
        override fun observeProject(projectId: String): Flow<Project?> = emptyFlow()
        override suspend fun deleteProject(projectId: String) = error("not used")
        override suspend fun generateProjectId(date: LocalDate): String = error("not used")
    }

    private val context = AgentToolContext(chatId = 1, platformUid = "p", iteration = 1, projectId = "test")

    private fun call(vararg edits: Triple<String, String, Boolean?>): AgentToolCall = AgentToolCall(
        id = "c1",
        name = "edit_project_file",
        arguments = buildJsonObject {
            put("path", JsonPrimitive("Main.java"))
            put(
                "edits",
                buildJsonArray {
                    edits.forEach { (old, new, replaceAll) ->
                        add(
                            buildJsonObject {
                                put("old_string", JsonPrimitive(old))
                                put("new_string", JsonPrimitive(new))
                                replaceAll?.let { put("replace_all", JsonPrimitive(it)) }
                            },
                        )
                    }
                },
            )
        },
    )

    @Test
    fun `zero matches returns isError and does not write file`() = runBlocking {
        val ws = MemoryWorkspace(mapOf("Main.java" to "int a = 1;"))
        val tool = EditProjectFileTool(MemoryProjectManager(ws))

        val result = tool.execute(call(Triple("does-not-exist", "x", null)), context)

        assertTrue(result.isError)
        assertEquals("int a = 1;", ws.files["Main.java"])
        val edits = result.output.jsonObject["edits"]!!.jsonArray
        assertEquals("false", edits[0].jsonObject["matched"]!!.jsonPrimitive.content)
    }

    @Test
    fun `ambiguous match without replace_all is rejected with occurrence count`() = runBlocking {
        val ws = MemoryWorkspace(mapOf("Main.java" to "foo(); foo();"))
        val tool = EditProjectFileTool(MemoryProjectManager(ws))

        val result = tool.execute(call(Triple("foo()", "bar()", null)), context)

        assertTrue(result.isError)
        assertEquals("foo(); foo();", ws.files["Main.java"])
        val edit = result.output.jsonObject["edits"]!!.jsonArray[0].jsonObject
        assertEquals("2", edit["occurrences"]!!.jsonPrimitive.content)
        assertTrue(edit["reason"]!!.jsonPrimitive.content.contains("ambiguous"))
    }

    @Test
    fun `replace_all replaces every occurrence`() = runBlocking {
        val ws = MemoryWorkspace(mapOf("Main.java" to "foo(); foo();"))
        val tool = EditProjectFileTool(MemoryProjectManager(ws))

        val result = tool.execute(call(Triple("foo()", "bar()", true)), context)

        assertFalse(result.isError)
        assertEquals("bar(); bar();", ws.files["Main.java"])
        // applied_count counts EDITS applied (1), not occurrences replaced (2).
        assertEquals("1", result.output.jsonObject["applied_count"]!!.jsonPrimitive.content)
    }

    @Test
    fun `unique match applies and partial failure still writes with counts`() = runBlocking {
        val ws = MemoryWorkspace(mapOf("Main.java" to "int a = 1;"))
        val tool = EditProjectFileTool(MemoryProjectManager(ws))

        val result = tool.execute(
            call(Triple("int a = 1;", "int a = 2;", null), Triple("missing", "x", null)),
            context,
        )

        assertFalse(result.isError)
        assertEquals("int a = 2;", ws.files["Main.java"])
        assertEquals("1", result.output.jsonObject["applied_count"]!!.jsonPrimitive.content)
        assertEquals("1", result.output.jsonObject["failed_count"]!!.jsonPrimitive.content)
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
./gradlew :app:testDebugUnitTest --tests "com.vibe.app.feature.agent.tool.EditProjectFileToolTest"
```
预期:FAIL(现实现零匹配也返回成功)。若 `ProjectWorkspace`/`ProjectManager` 接口方法与 fake 不符,以真实接口为准修 fake(先 `grep -n "suspend fun" app/src/main/kotlin/com/vibe/app/feature/project/ProjectWorkspace.kt`)。

- [ ] **Step 3: 实现新语义**

`EditProjectFileTool.execute`(:224-266)替换为:

```kotlin
    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val path = call.arguments.requireString("path")
        val editsArray = call.arguments.jsonObject["edits"]
            ?: throw IllegalArgumentException("Missing required field: edits")

        val workspace = projectManager.openWorkspace(context.projectId)
        var content = workspace.readTextFile(path)
        val results = mutableListOf<kotlinx.serialization.json.JsonObject>()
        var appliedCount = 0

        for (editElement in (editsArray as JsonArray)) {
            val edit = editElement.jsonObject
            val oldString = edit["old_string"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Each edit must have old_string")
            val newString = edit["new_string"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Each edit must have new_string")
            val replaceAll = edit["replace_all"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false

            val occurrences = countOccurrences(content, oldString)
            when {
                occurrences == 0 -> results.add(
                    editResult(oldString, matched = false, applied = false, occurrences = 0,
                        reason = "old_string not found in file"),
                )
                occurrences > 1 && !replaceAll -> results.add(
                    editResult(oldString, matched = true, applied = false, occurrences = occurrences,
                        reason = "ambiguous: $occurrences occurrences, provide longer old_string or set replace_all"),
                )
                else -> {
                    content = if (replaceAll) content.replace(oldString, newString)
                    else content.replaceFirst(oldString, newString)
                    appliedCount++
                    results.add(editResult(oldString, matched = true, applied = true, occurrences = occurrences))
                }
            }
        }

        val failedCount = results.size - appliedCount
        if (appliedCount > 0) {
            workspace.writeTextFile(path, content)
        }

        val output = buildJsonObject {
            put("path", JsonPrimitive(path))
            put("applied_count", JsonPrimitive(appliedCount))
            put("failed_count", JsonPrimitive(failedCount))
            if (appliedCount == 0) {
                put("error", JsonPrimitive("No edits were applied — the file is unchanged. Re-read the file and retry with exact text."))
            }
            put("edits", buildJsonArray { results.forEach { add(it) } })
        }
        return call.result(output, isError = appliedCount == 0)
    }

    private fun editResult(
        oldString: String,
        matched: Boolean,
        applied: Boolean,
        occurrences: Int,
        reason: String? = null,
    ): kotlinx.serialization.json.JsonObject = buildJsonObject {
        put("old_string", JsonPrimitive(oldString.take(80)))
        put("matched", JsonPrimitive(matched))
        put("applied", JsonPrimitive(applied))
        put("occurrences", JsonPrimitive(occurrences))
        reason?.let { put("reason", JsonPrimitive(it)) }
    }

    private fun countOccurrences(content: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var index = content.indexOf(needle)
        while (index >= 0) {
            count++
            index = content.indexOf(needle, index + needle.length)
        }
        return count
    }
```

schema(:198-217)的 edit item properties 增加:

```kotlin
                                            put("old_string", stringProp("Exact text to find."))
                                            put("new_string", stringProp("Replacement text."))
                                            put("replace_all", booleanProp("Replace ALL occurrences. Without this, old_string must match exactly once."))
```

description(:189-190)改为:

```kotlin
        description = "Apply search-and-replace edits to an existing project file. " +
            "Each old_string must match EXACTLY ONE location unless replace_all is set. " +
            "Returns per-edit results; if no edit applies, the call fails and the file is unchanged.",
```

- [ ] **Step 4: 运行确认通过**

```bash
./gradlew :app:testDebugUnitTest --tests "com.vibe.app.feature.agent.tool.EditProjectFileToolTest"
```
预期:PASS(4 个测试)。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/feature/agent/tool/FileTools.kt app/src/test/kotlin/com/vibe/app/feature/agent/tool/EditProjectFileToolTest.kt
git commit -m "fix(tools): edit_project_file fails on zero matches, guards ambiguous edits, adds replace_all (opt task 1.6)"
```

**验收标准:** 规格 1-6 全部由测试覆盖并通过;`edit_project_file` 不再出现"没改上但返回成功"。

---

## Task 1.7: 工具参数解析失败显式化 + max_tokens 截断处理

**现状与证据:**
- Anthropic gateway 参数 JSON 解析失败静默降级为 `{}`(`AnthropicMessagesAgentGateway.kt:167-170`);Kimi/Qwen/DeepSeek 降级为 `{"raw": ...}`(`KimiChatCompletionsAgentGateway.kt:153-157` 等);OpenAI Responses 同类。工具随后拿到残缺参数执行,模型收到 "Missing required string field" 之类的间接报错,浪费迭代。
- Anthropic `stopReason` 被记录但从未消费(:184-186);`maxTokens` 硬编码 16000(:300)。输出被截断时参数 JSON 必然残缺,正好落入静默降级。

**改动文件:**
- Modify: `app/src/main/kotlin/com/vibe/app/feature/agent/AgentModels.kt`(顶层常量)
- Modify: `app/src/main/kotlin/com/vibe/app/feature/agent/AgentContracts.kt`(Completed 加字段)
- Modify: 5 个 gateway 的参数解析 `getOrElse` 处 + Anthropic gateway 的 MessageStop 处
- Modify: `app/src/main/kotlin/com/vibe/app/feature/agent/loop/DefaultAgentLoopCoordinator.kt`(哨兵检查 + 截断续写)

**步骤:**

- [ ] **Step 1: 定义哨兵常量与 Completed 扩展**

`AgentModels.kt` 顶部(enum 之前)加:

```kotlin
/**
 * Sentinel key marking a tool call whose arguments failed to parse as JSON
 * (typically because the model output was truncated by max_tokens).
 * The coordinator turns such calls into an isError tool result instead of executing them.
 */
const val INVALID_TOOL_ARGUMENTS_KEY = "__invalid_arguments__"
```

`AgentContracts.kt` 的 Completed(:57-61)加字段:

```kotlin
    data class Completed(
        val finalText: String? = null,
        val responseId: String? = null,
        val reasoningContent: String? = null,
        val truncatedByMaxTokens: Boolean = false,
    ) : AgentModelEvent
```

- [ ] **Step 2: gateway 侧改哨兵 + 消费 stopReason**

Anthropic gateway :167-170 改为:

```kotlin
                        val arguments = block.inputBuilder.toString()
                            .takeIf { it.isNotBlank() }
                            ?.let { raw ->
                                runCatching { json.parseToJsonElement(raw) }.getOrElse {
                                    buildJsonObject { put(INVALID_TOOL_ARGUMENTS_KEY, JsonPrimitive(raw.take(2000))) }
                                }
                            }
                            ?: buildJsonObject {}
```

(import `com.vibe.app.feature.agent.INVALID_TOOL_ARGUMENTS_KEY` 与 `kotlinx.serialization.json.JsonPrimitive`。)

MessageStop 处(:188-191)带上截断标记:

```kotlin
                is MessageStopResponseChunk -> {
                    trace.markCompleted(stopReason?.name?.lowercase())
                    emit(AgentModelEvent.Completed(truncatedByMaxTokens = stopReason == StopReason.MAX_TOKENS))
                }
```

Kimi/Qwen/DeepSeek 三个 gateway 的 `getOrElse { buildJsonObject { put("raw", ...) } }`(如 `KimiChatCompletionsAgentGateway.kt:153-157`)统一改为哨兵形式(同上);它们的 `finishReason == "length"` 即 max_tokens 截断,在各自的 `trace.markCompleted(finishReason)` 附近找到 emit `Completed` 的位置(grep `AgentModelEvent.Completed`),补 `truncatedByMaxTokens = finishReason == "length"`。OpenAI Responses gateway 的参数解析处(grep `parseToJsonElement`)同样改哨兵。

- [ ] **Step 3: coordinator 消费哨兵与截断**

工具执行处,在 `val tool = agentToolRegistry.findTool(call.name)`(:309)**之前**插入:

```kotlin
                pendingCalls.forEach { call ->
                    val invalidRaw = (call.arguments as? kotlinx.serialization.json.JsonObject)
                        ?.get(com.vibe.app.feature.agent.INVALID_TOOL_ARGUMENTS_KEY)
                    if (invalidRaw != null) {
                        val result = AgentToolResult(
                            toolCallId = call.id,
                            toolName = call.name,
                            output = buildJsonObject {
                                put(
                                    "error",
                                    JsonPrimitive(
                                        "Tool call arguments were not valid JSON (likely truncated by the output token limit). " +
                                            "Re-issue this tool call with complete, valid JSON arguments.",
                                    ),
                                )
                            },
                            isError = true,
                        )
                        pendingToolResults += result
                        collectedToolResults += result
                        emit(AgentLoopEvent.ToolExecutionFinished(iteration, result))
                        return@forEach
                    }
                    val tool = agentToolRegistry.findTool(call.name)
                    /* 后续原逻辑不变 */
```

Completed 分支(Task 1.1 改造后的 collect 内)记录 `completedTruncated = event.truncatedByMaxTokens`(在 attempt 环外声明 `var completedTruncated = false`,每 attempt 重置)。在 `if (pendingCalls.isEmpty())` 完成分支(:274)**之前**插入截断续写:

```kotlin
                if (completedTruncated && pendingCalls.isEmpty() && iteration < request.policy.maxIterations) {
                    // Text-only response cut off by max_tokens: keep the partial text in
                    // history and ask the model to continue instead of ending the loop.
                    fullConversation += AgentConversationItem(
                        role = AgentMessageRole.ASSISTANT,
                        text = outputBuilder.toString().trim().takeIf { it.isNotEmpty() },
                    )
                    val continueMessage = AgentConversationItem(
                        role = AgentMessageRole.USER,
                        text = "[System] Your previous response was cut off by the output token limit. " +
                            "Continue exactly where you stopped. Do not repeat content you already produced.",
                    )
                    fullConversation += continueMessage
                    conversationDelta = listOf(continueMessage)
                    continue
                }
```

- [ ] **Step 4: 验证**

```bash
./gradlew :app:testDebugUnitTest && ./gradlew assembleDebug
```
预期:PASS。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/feature/agent/
git commit -m "fix(agent): surface unparseable tool arguments and continue after max_tokens truncation (opt task 1.7)"
```

**验收标准:** 参数 JSON 残缺时工具不执行、模型收到明确的"重发完整参数"错误;Anthropic 文本回复被 max_tokens 截断时自动注入续写请求而不是以半截文本结束。

---

## Task 1.8: API 单例凭证竞态修复(token/apiUrl 参数化)

**现状与证据:** `AnthropicAPIImpl`/`OpenAIAPIImpl` 用可变字段存 token/apiUrl(`AnthropicAPIImpl.kt:34-35`、`OpenAIAPIImpl.kt:38-39`),Hilt 单例提供;调用点共 6 处、全部先 set 后用:5 个 gateway(`AnthropicMessagesAgentGateway.kt:74-75`、`OpenAiResponsesAgentGateway.kt:52-53`、`QwenChatCompletionsAgentGateway.kt:43-44`、`KimiChatCompletionsAgentGateway.kt:56-57`、`DeepSeekChatCompletionsAgentGateway.kt:53-54`)+ `ModelSummaryStrategy.kt:102-103`。`AgentSessionManager` 支持多 chatId 并发会话——两个 provider 并发时 A 的 key 会打到 B 的 endpoint。

**改动文件:**
- Modify: `app/src/main/kotlin/com/vibe/app/data/network/AnthropicAPI.kt` / `AnthropicAPIImpl.kt`
- Modify: `app/src/main/kotlin/com/vibe/app/data/network/OpenAIAPI.kt` / `OpenAIAPIImpl.kt`
- Modify: 上述 6 个调用点
- Modify(如有): `app/src/test/kotlin/com/vibe/app/feature/agent/loop/QwenChatCompletionsAgentGatewayTest.kt`(若其 fake 实现了 OpenAIAPI 接口,同步新签名)

**步骤:**

- [ ] **Step 1: 改接口签名**

`AnthropicAPI.kt`:

```kotlin
interface AnthropicAPI {
    fun streamChatMessage(
        messageRequest: MessageRequest,
        token: String?,
        apiUrl: String,
        diagnosticContext: ModelRequestDiagnosticContext? = null,
        trace: ModelExecutionTrace? = null,
    ): Flow<MessageResponseChunk>
}
```

`OpenAIAPI.kt` 的 4 个方法同样在 request 后追加 `token: String?, apiUrl: String` 参数,删除 `setToken`/`setAPIUrl` 声明。

- [ ] **Step 2: 改实现**

两个 impl:删除 `private var token` / `private var apiUrl` 字段与 setter;每个方法体开头加本地默认值处理:

```kotlin
    override fun streamChatMessage(
        messageRequest: MessageRequest,
        token: String?,
        apiUrl: String,
        diagnosticContext: ModelRequestDiagnosticContext?,
        trace: ModelExecutionTrace?,
    ): Flow<MessageResponseChunk> = flow {
        val baseUrl = apiUrl.ifBlank { ModelConstants.ANTHROPIC_API_URL }
        val endpoint = if (baseUrl.endsWith("/")) "${baseUrl}v1/messages" else "$baseUrl/v1/messages"
        /* 方法体内所有 this.token / this.apiUrl 引用改为参数 token / baseUrl */
```

OpenAIAPIImpl 同法(默认 `ModelConstants.OPENAI_API_URL`)。`logOpenAiRequest`(:468-484)读字段 `token`——把 token 作为参数传入该私有方法。

- [ ] **Step 3: 改 6 个调用点**

gateway 统一模式,例(Anthropic):

```kotlin
        // 删除: anthropicAPI.setToken(...) / anthropicAPI.setAPIUrl(...)
        anthropicAPI.streamChatMessage(
            messageRequest,
            token = request.platform.token,
            apiUrl = request.platform.apiUrl,
            diagnosticContext = requestContext,
            trace = trace,
        ).collect { chunk -> ... }
```

Kimi/Qwen/DeepSeek 传各自归一化后的 URL(如 `request.platform.apiUrl.toKimiBaseUrl()`);`ModelSummaryStrategy.callSummarizationAPI`(:100-107)删除两行 set,改为 `openAIAPI.completeQwenChatCompletion(request, token = token, apiUrl = apiUrl)`。

- [ ] **Step 4: 全局确认无残留**

```bash
grep -rn "setToken\|setAPIUrl" app/src/main/kotlin/ app/src/test/kotlin/
```
预期:无输出(接口、实现、调用点全部清除)。

- [ ] **Step 5: 验证 + Commit**

```bash
./gradlew :app:testDebugUnitTest && ./gradlew assembleDebug
git add app/src/main/kotlin/ app/src/test/kotlin/
git commit -m "fix(network): pass token/apiUrl per call, kill mutable singleton credentials (opt task 1.8)"
```

**验收标准:** API 单例不再持有任何可变凭证状态;两个不同 provider 的会话并发时请求头/endpoint 互不污染(代码层面由"无共享可变状态"保证)。

---

## Task 1.9: 网络日志 DEBUG 门控

**现状与证据:** `NetworkLogcatLogger` 无任何构建变体判断(`NetworkLogcatLogger.kt:152-171` 直接 `Log.d/w/e`);`logRequest` 打 4000 字符请求体(内含用户对话与生成代码),`logSseEvent` 对每个 SSE chunk 都拼串分块打印(`OpenAIAPIImpl.kt:496`、`AnthropicAPIImpl.kt:170` 每事件调用)。release 上性能与隐私双输。

**改动文件:**
- Modify: `app/src/main/kotlin/com/vibe/app/data/network/NetworkLogcatLogger.kt`

**步骤:**

- [ ] **Step 1: 加门控**

```kotlin
import com.vibe.app.BuildConfig

object NetworkLogcatLogger {
    /** Verbose bodies/SSE only in debug builds; release logs metadata and errors only. */
    private val verbose: Boolean = BuildConfig.DEBUG
```

- `logSseEvent`(:84-96)开头加 `if (!verbose) return`(SSE 逐条日志 debug-only);
- `logRequest`(:19-42):body 打印段包 `if (verbose)`,release 只留 URL/METHOD/headers(headers 已脱敏);
- `logResponse`(:44-82):`body != null` 分支包 `if (verbose)`,release 保留状态码/耗时;**例外**:`statusCode >= 400` 时 release 也打 body(截 500 字符)——错误排查需要;
- `logDecodeFailure` / `logNetworkError` 保持无门控(错误必须可见),但 `logDecodeFailure` 的 rawData 在 release 截 500 字符:`rawData.clip(if (verbose) MAX_BODY_LENGTH else 500)`。

- [ ] **Step 2: 验证**

```bash
./gradlew assembleDebug && ./gradlew assembleRelease
```
预期:两个变体编译成功(release 若因签名配置失败,`assembleRelease` 至少过编译期;以 `compileReleaseKotlin` 任务成功为准)。

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/data/network/NetworkLogcatLogger.kt
git commit -m "fix(network): gate verbose request/SSE logging behind BuildConfig.DEBUG (opt task 1.9)"
```

**验收标准:** release 变体不再向 logcat 输出请求体/SSE 正文;HTTP 错误与解码失败仍可见(截断后)。

---

## Phase 完成检查

- [ ] `./gradlew :app:testDebugUnitTest` 全绿
- [ ] `./gradlew test` 全绿
- [ ] `./gradlew assembleDebug` 成功
- [ ] 人工验证清单(Android 10+ 真机/模拟器,debug 包):
  - [ ] **断网重试**:开始一轮生成,模型流式输出中途关 Wi-Fi 3 秒再打开——思考区出现 `[Transient model error, retrying ...]`,轮次继续而不是整轮失败;
  - [ ] **彻底断网**:持续断网——2 次重试后优雅失败,错误信息可读;
  - [ ] **取消保快照**:发起一轮会写文件的任务,待首个 write 工具完成后点停止——项目快照列表仍出现本轮 TURN 快照,undo 可回滚;
  - [ ] **错误 endpoint 快失败**:平台设置里配一个不可达 apiUrl——约 15 秒内报连接错误(而不是 5 分钟);
  - [ ] **release 日志安静**:`adb logcat -s VibeNetwork` 下跑 release 包一轮对话——无请求体/SSE 正文输出。
- [ ] 更新 `00-progress.md`:Phase 1 状态 → ✅,填完成日期,当前位置清空
- [ ] `git commit -m "docs: mark optimization phase 1 complete"`

## 实施记录(执行时追加)

| 日期 | 执行者 | 内容 | 偏离/备注 |
|------|--------|------|-----------|
| 2026-07-04 | Claude (SDD:implementer+reviewer 子代理,Opus 控制) | Task 1.1–1.9 全部实现,每个走 TDD/实现→逐 Task 双重审查(规格+质量)→提交;9 独立 commit `e5fa43b..e74be7a`;整支审查(Opus)判 Ready to merge。验证:`:app:testDebugUnitTest`+`:build-engine:test`+`assembleDebug`+`compileReleaseKotlin` 全绿。 | 分支采用原地(非 worktree),因 332MB 未跟踪 `assets/bootstrap/`。**偏离**:1.2 额外把服务器错误/失败事件也计为 SSE 终止(防伪 stream_interrupted 覆盖真实 Failed,审查确认正确);1.7 顺手改 Kimi/Qwen 两个死代码 `toAgentToolCall`(零引用,行为中性);1.9 必须的 `app/build.gradle.kts` 启用 `buildConfig=true`(AGP 9.1 默认关,否则 BuildConfig 不生成)。**跟进(非阻塞,见 00-progress §3 后 Phase 1 遗留)**:Task 1.8b(ModelSummaryStrategy 残留竞态)、3 个集成测试、真机 5 项验证未做。 |
