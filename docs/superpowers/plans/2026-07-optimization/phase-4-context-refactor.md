# Phase 4: Context 核心重构 实施计划

> **执行者须知(任何模型/会话通用):**
> 1. 开工前先读 [`00-progress.md`](./00-progress.md),确认 **Phase 2 已完成**(本 phase 假设 2.1/2.3/2.5 已落地:OpenAI 旁路已修、ModelSummary 已有预算检查、ToolResultTrimStrategy 已含 web 工具分支)且本 phase 当前进度与状态表一致;
> 2. 本文所有 `file:line` 基于 `dev@be1f944`,代码可能已漂移——**动手前用 grep 重新定位**,以实际代码为准;
> 3. 每完成一个 Task:勾选本文对应 checkbox → 按"验证"节跑测试(不跑不许勾)→ 独立 commit → 更新 `00-progress.md` 状态表"当前位置";
> 4. 任何偏离(改方案/跳步骤/发现计划错误)必须写入文末"实施记录"表,禁止静默偏离;
> 5. 建议用 `superpowers:executing-plans` 或 `superpowers:subagent-driven-development` 执行;实现遵循 TDD:先写测试、看它失败、再实现。

**目标:** 把压缩单位从"USER 回合"改为"消息级 + token 预算",根治当前回合内工具结果无界膨胀(评审 D2/D3);用结构化持久化替代平面文本截断(D6/D8);用 API usage 回传校准 token 估算(D4);预算随模型可配置(D9)。

**评审依据:** `docs/optimization-review-2026-07.md` §3.3 第二/三/四步。

**前置依赖:** Phase 2(Task 2.1/2.3/2.4/2.5)。

**涉及模块:** `feature/agent/loop`(coordinator + compaction)、`feature/agent/service`(AgentSessionManager)、`data/database`(新表 + PlatformV2 扩列)、`presentation/ui/setting`(两个数字输入)。

**Task 依赖顺序:** 4.1 → 4.2 → 4.3 → 4.4 → 4.5(4.2 依赖 4.1 提取的 `ToolPayloadTrimmer`;4.4 依赖 4.2 的估算改造点;4.3/4.5 各含一次 Room 迁移,版本号按执行时实际 DB 版本递增)。

---

## 背景:现状代码结构速览(已核实)

- 循环主体 `DefaultAgentLoopCoordinator.run()`:每次迭代对 `fullConversation.toList()` 全量调 `conversationCompactor.compact(items, clientType, platform)`(`DefaultAgentLoopCoordinator.kt:193-197`),压缩结果只进 `AgentModelRequest.fullConversation`,**不回写**循环状态;
- `ConversationCompactor.compact()`(`ConversationCompactor.kt:38-109`):预算内直接返回 → Strategy 1 `ToolResultTrimStrategy` → Strategy 2 `StructuralSummaryStrategy` → Strategy 3 `ModelSummaryStrategy`(仅 QWEN/KIMI)→ 兜底 `truncateToFitBudget`(:119-174,每步 `estimateTokens(result)` 全量重算,O(n²));
- 三个策略都按 `splitIntoTurns`(USER 消息切分,`ToolResultTrimStrategy.kt:168-178`)只处理 `olderTurns`——当前 run 的所有工具结果挂在最后一个 USER 下,**永远不被处理**(D2);
- Room 只存平面文本:`MessageV2`(`entity/MessageV2.kt`)的 `content`/`thoughts`;`AgentSessionManager.saveToRoom()`(`AgentSessionManager.kt:430-451`)经 `chatRepository.saveChat` 落库,工具参数/结果全丢(D6);
- 跨回合重建 `buildInitialConversation` + `compactCrossTurnHistory`(`DefaultAgentLoopCoordinator.kt:616-681`):对 Room 加载的 assistant 文本 `take(maxChars)` 保头弃尾(D8),常量在 :741-746;
- token 估算 `ConversationContextManager.estimateTokens`(`ConversationContextManager.kt:122-148`):CJK 2 字符/token、其余 4,无校准、不计 attachments、不计 system prompt/工具 schema(D4);
- 预算 `ProviderContextBudget.forProvider(clientType)`(`ProviderContextBudget.kt:10-17`)硬编码;Anthropic 输出上限 `DEFAULT_MAX_TOKENS = 16000` 写死(`AnthropicMessagesAgentGateway.kt:86,300`);Anthropic 真实 usage 在 `AnthropicMessagesAgentGateway.kt:120-126` 只进诊断 trace;
- `PlatformV2` 是 Room 实体(表 `platform_v2`,`entity/PlatformV2.kt`),DB 当前 `version = 3`(`ChatDatabaseV2.kt:21`),迁移写在 `di/DatabaseModule.kt:24-119`;
- 既有测试风格:JUnit4 + `runBlocking` + `org.junit.Assert`,见 `app/src/test/kotlin/com/vibe/app/feature/agent/loop/compaction/ToolResultTrimStrategyTest.kt`。

---

## Task 4.1: 回合内工具结果淘汰(`CurrentRunToolResultEvictor`)

**现状与证据:** 一次 run 内连读大文件 + 多次 build,`fullConversation` 中当前回合的 TOOL payload 无界增长;所有策略只处理 `olderTurns`(`ToolResultTrimStrategy.kt:39`、`StructuralSummaryStrategy` 同构),兜底截断只砍无 toolCalls 的 assistant 文本(`ConversationCompactor.kt:129-131`)——当前回合无任何压缩路径(评审 D2);`trimToolPayload` 的 8 种裁剪分支在真实数据流中是死代码(D3)。

**改动文件:**
- Create: `app/src/main/kotlin/com/vibe/app/feature/agent/loop/compaction/ToolPayloadTrimmer.kt`
- Create: `app/src/main/kotlin/com/vibe/app/feature/agent/loop/compaction/CurrentRunToolResultEvictor.kt`
- Modify: `app/src/main/kotlin/com/vibe/app/feature/agent/loop/compaction/ToolResultTrimStrategy.kt`(裁剪逻辑委托给 Trimmer)
- Modify: `app/src/main/kotlin/com/vibe/app/feature/agent/loop/compaction/ConversationCompactor.kt`(接入第 0 级)
- Modify: `app/src/main/kotlin/com/vibe/app/feature/agent/loop/compaction/CompactionStrategy.kt`(枚举加 `CURRENT_RUN_EVICTION`)
- Test: `app/src/test/kotlin/com/vibe/app/feature/agent/loop/compaction/CurrentRunToolResultEvictorTest.kt`

**接口契约(后续 Task 依赖):**

```kotlin
// ToolPayloadTrimmer.kt — 从 ToolResultTrimStrategy 原样搬出的纯函数集合
object ToolPayloadTrimmer {
    /** 返回裁剪后的 payload;无需裁剪时返回原对象(=== 判等以检测是否变化)。 */
    fun trim(toolName: String?, payload: JsonElement): JsonElement
}

// CurrentRunToolResultEvictor.kt
class CurrentRunToolResultEvictor(
    private val keepRecentIterations: Int = DEFAULT_KEEP_ITERATIONS, // 3
) {
    /** 只处理最后一个 USER 之后的内容;无可淘汰时返回原列表(同一引用)。 */
    fun evict(items: List<AgentConversationItem>): List<AgentConversationItem>
    companion object { const val DEFAULT_KEEP_ITERATIONS = 3 }
}
```

- [ ] **Step 1:提取 `ToolPayloadTrimmer`**

把 `ToolResultTrimStrategy.kt:74-165` 的 `trimToolPayload` + `trimReadFilePayload` + `trimBuildPayload` + `trimCrashGuidePayload` + `trimGrepPayload` + `trimInteractUiPayload`(以及 Phase 2 在 Task 2.5 加入的 `web_search`/`fetch_web_page` 分支)**原样搬到** `object ToolPayloadTrimmer`,方法改为 `fun trim(toolName: String?, payload: JsonElement): JsonElement`(public)。`ToolResultTrimStrategy` 中原调用点(:46)改为 `ToolPayloadTrimmer.trim(item.toolName, item.payload)`,删除类内私有副本。纯移动,不改逻辑。

- [ ] **Step 2:跑既有测试确认搬移无损**

```bash
./gradlew :app:testDebugUnitTest --tests "*ToolResultTrimStrategyTest*"
```
预期:PASS(该测试是唯一既有压缩测试,搬移后必须全绿)。

- [ ] **Step 3:写 `CurrentRunToolResultEvictorTest`(先失败)**

```kotlin
package com.vibe.app.feature.agent.loop.compaction

import com.vibe.app.feature.agent.AgentConversationItem
import com.vibe.app.feature.agent.AgentMessageRole
import com.vibe.app.feature.agent.AgentToolCall
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentRunToolResultEvictorTest {

    private val evictor = CurrentRunToolResultEvictor(keepRecentIterations = 3)

    private fun user(text: String = "do something") =
        AgentConversationItem(role = AgentMessageRole.USER, text = text)

    private fun assistantWithCall(id: String) = AgentConversationItem(
        role = AgentMessageRole.ASSISTANT,
        text = "calling",
        toolCalls = listOf(AgentToolCall(id = id, name = "read_project_file",
            arguments = buildJsonObject { put("path", JsonPrimitive("Main.java")) })),
        reasoningContent = "thinking...",
    )

    private fun toolResult(id: String, content: String) = AgentConversationItem(
        role = AgentMessageRole.TOOL,
        toolCallId = id,
        toolName = "read_project_file",
        payload = buildJsonObject {
            put("path", JsonPrimitive("Main.java"))
            put("content", JsonPrimitive(content))
        },
    )

    /** 5 个迭代,K=3:迭代 1-2 的 TOOL payload 被裁,3-5 原样保留。 */
    @Test
    fun `evicts tool payloads older than K iterations, keeps recent K intact`() {
        val big = "line\n".repeat(500)
        val items = buildList {
            add(user())
            for (i in 1..5) {
                add(assistantWithCall("call_$i"))
                add(toolResult("call_$i", big))
            }
        }

        val result = evictor.evict(items)

        assertEquals("item count must not change", items.size, result.size)
        // 迭代 1、2 被裁:payload 含占位符,不含原始大内容
        for (i in listOf(1, 2)) {
            val tool = result.first { it.toolCallId == "call_$i" }
            val content = tool.payload!!.jsonObject["content"]!!.jsonPrimitive.content
            assertTrue("iteration $i should be trimmed", content.contains("trimmed"))
        }
        // 迭代 3、4、5 原样(同一对象引用)
        for (i in listOf(3, 4, 5)) {
            val original = items.first { it.toolCallId == "call_$i" }
            val kept = result.first { it.toolCallId == "call_$i" }
            assertSame("iteration $i must be untouched", original, kept)
        }
    }

    /** id 配对与 ASSISTANT.toolCalls 绝不破坏:淘汰只换 payload 内容,不删项、不动 toolCalls。 */
    @Test
    fun `never removes items and never touches assistant toolCalls`() {
        val items = buildList {
            add(user())
            for (i in 1..5) {
                add(assistantWithCall("call_$i"))
                add(toolResult("call_$i", "x".repeat(4000)))
            }
        }

        val result = evictor.evict(items)

        // 每个 toolCallId 依旧成对出现
        for (i in 1..5) {
            assertEquals(1, result.count { it.role == AgentMessageRole.TOOL && it.toolCallId == "call_$i" })
            val assistant = result.first {
                it.role == AgentMessageRole.ASSISTANT && it.toolCalls?.any { c -> c.id == "call_$i" } == true
            }
            assertEquals("call_$i", assistant.toolCalls!!.single().id)
        }
    }

    /** 只处理最后一个 USER 之后的内容;之前的历史一律原样。 */
    @Test
    fun `items before the last user message are untouched`() {
        val oldTool = toolResult("old_call", "y".repeat(4000))
        val items = buildList {
            add(user("previous turn"))
            add(assistantWithCall("old_call"))
            add(oldTool)
            add(user("current turn"))
            for (i in 1..5) {
                add(assistantWithCall("call_$i"))
                add(toolResult("call_$i", "z".repeat(4000)))
            }
        }

        val result = evictor.evict(items)
        assertSame(oldTool, result[2])
    }

    /** 迭代数 ≤ K 时无事可做,返回原列表引用(幂等快路径)。 */
    @Test
    fun `returns same list when nothing to evict`() {
        val items = buildList {
            add(user())
            for (i in 1..3) {
                add(assistantWithCall("call_$i"))
                add(toolResult("call_$i", "small"))
            }
        }
        assertSame(items, evictor.evict(items))
    }

    /** 幂等:第二次 evict 结果与第一次内容一致。 */
    @Test
    fun `evict is idempotent`() {
        val items = buildList {
            add(user())
            for (i in 1..5) {
                add(assistantWithCall("call_$i"))
                add(toolResult("call_$i", "w".repeat(4000)))
            }
        }
        val once = evictor.evict(items)
        val twice = evictor.evict(once)
        assertEquals(once, twice)
    }
}
```

- [ ] **Step 4:跑测试确认失败**

```bash
./gradlew :app:testDebugUnitTest --tests "*CurrentRunToolResultEvictorTest*"
```
预期:FAIL(`CurrentRunToolResultEvictor` 未定义)。

- [ ] **Step 5:实现 `CurrentRunToolResultEvictor`**

```kotlin
package com.vibe.app.feature.agent.loop.compaction

import com.vibe.app.feature.agent.AgentConversationItem
import com.vibe.app.feature.agent.AgentMessageRole

/**
 * Level-0 compaction: evict tool result payloads from OLDER iterations of the
 * CURRENT run (everything after the last USER item). Keeps the most recent
 * [keepRecentIterations] iterations fully intact.
 *
 * An "iteration" is an ASSISTANT item carrying toolCalls plus the TOOL items
 * that follow it. Eviction REPLACES payload content in place via
 * [ToolPayloadTrimmer] — it never removes items, so assistant.toolCalls ↔ TOOL
 * toolCallId pairing is always preserved.
 */
class CurrentRunToolResultEvictor(
    private val keepRecentIterations: Int = DEFAULT_KEEP_ITERATIONS,
) {
    fun evict(items: List<AgentConversationItem>): List<AgentConversationItem> {
        val lastUserIdx = items.indexOfLast { it.role == AgentMessageRole.USER }
        if (lastUserIdx < 0) return items

        // Iteration boundaries: indices of ASSISTANT items with toolCalls after the last USER.
        val iterationStarts = (lastUserIdx + 1 until items.size).filter { idx ->
            items[idx].role == AgentMessageRole.ASSISTANT && !items[idx].toolCalls.isNullOrEmpty()
        }
        if (iterationStarts.size <= keepRecentIterations) return items

        // Evict everything before the first of the last-K iteration starts.
        val evictBefore = iterationStarts[iterationStarts.size - keepRecentIterations]
        var changed = false
        val result = items.mapIndexed { idx, item ->
            if (idx <= lastUserIdx || idx >= evictBefore) return@mapIndexed item
            when {
                item.role == AgentMessageRole.TOOL && item.payload != null -> {
                    val trimmed = ToolPayloadTrimmer.trim(item.toolName, item.payload)
                    if (trimmed !== item.payload) {
                        changed = true
                        item.copy(payload = trimmed)
                    } else item
                }
                item.role == AgentMessageRole.ASSISTANT && item.reasoningContent != null -> {
                    changed = true
                    item.copy(reasoningContent = null)
                }
                else -> item
            }
        }
        return if (changed) result else items
    }

    companion object {
        const val DEFAULT_KEEP_ITERATIONS = 3
    }
}
```

- [ ] **Step 6:接入 `ConversationCompactor.compact`(第 0 级,不受 recentTurns 门控)**

在 `ConversationCompactor.kt` 中:字段区加 `private val currentRunEvictor = CurrentRunToolResultEvictor()`;`CompactionStrategyType` 枚举(`CompactionStrategy.kt:12-18`)加 `CURRENT_RUN_EVICTION`;`compact()` 的"预算内直接返回"判断(:47)之后、Strategy 1 之前插入:

```kotlin
// Level 0: evict old-iteration tool payloads inside the CURRENT run.
// Triggered early (70% of budget) because within-run growth is the main
// inflation source and eviction is free (D2).
var working = items
var workingTokens = currentTokens
if (currentTokens > budget.maxTokens * EVICTION_TRIGGER_RATIO) {
    val evicted = currentRunEvictor.evict(items)
    if (evicted !== items) {
        working = evicted
        workingTokens = ConversationContextManager.estimateTokens(evicted)
        if (workingTokens <= budget.maxTokens) {
            return CompactionResult(
                items = evicted,
                estimatedTokens = workingTokens,
                strategyUsed = CompactionStrategyType.CURRENT_RUN_EVICTION,
                turnsCompacted = 0,
            )
        }
    }
}
```

其后整条链(Strategy 1/2/3、兜底)的输入从 `items` 改为 `working`(`:57` 的 `toolResultTrimStrategy.compact(working, ...)`,`:63` 的 `val afterTrim = trimResult?.items ?: working`,最终 `NONE` 分支返回 `working`/`workingTokens`)。companion 加 `private const val EVICTION_TRIGGER_RATIO = 0.7`。

注意:开头的快路径判断保持 `currentTokens <= budget.maxTokens` 不变——70% 触发只针对"已超预算前的预清理"场景?**不对**,按设计淘汰应在 70% 即介入:把 :47 的快路径改为 `if (currentTokens <= budget.maxTokens * EVICTION_TRIGGER_RATIO) return ...NONE...`,70%~100% 区间只跑 Level 0(淘汰后若 ≤ maxTokens 返回,否则若本来就 ≤ maxTokens 也返回 NONE 结果),>100% 才继续策略链。实现为:

```kotlin
if (currentTokens <= (budget.maxTokens * EVICTION_TRIGGER_RATIO).toInt()) {
    return CompactionResult(items, currentTokens, CompactionStrategyType.NONE, 0)
}
// ... Level 0 eviction as above ...
if (workingTokens <= budget.maxTokens) {
    val type = if (working === items) CompactionStrategyType.NONE
               else CompactionStrategyType.CURRENT_RUN_EVICTION
    return CompactionResult(working, workingTokens, type, 0)
}
// fall through to Strategy 1-3 with `working`
```

- [ ] **Step 7:跑测试确认通过**

```bash
./gradlew :app:testDebugUnitTest --tests "*CurrentRunToolResultEvictorTest*" --tests "*ToolResultTrimStrategyTest*"
```
预期:全部 PASS。

- [ ] **Step 8:Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/feature/agent/loop/compaction/ app/src/test/kotlin/com/vibe/app/feature/agent/loop/compaction/
git commit -m "feat(agent): evict old-iteration tool payloads within current run (opt task 4.1)"
```

**验收标准:** 新旧测试全绿;`ConversationCompactor` 在 70% 预算即触发回合内淘汰;淘汰绝不增删消息、绝不改 `toolCalls`。

---

## Task 4.2: 压缩会话缓存(`CompactionSession`)与兜底截断去 O(n²)

**现状与证据:** 每次迭代对原始 `fullConversation` 重新跑全链(`DefaultAgentLoopCoordinator.kt:193-197`),压缩结果不回写:QWEN/KIMI 一旦超限,**每次迭代都重新调一次摘要 API**(评审 D5 后半);`truncateToFitBudget` 每步 `estimateTokens(result)` 全量重算(`ConversationCompactor.kt:127,142,159`,D11)。

**改动文件:**
- Create: `app/src/main/kotlin/com/vibe/app/feature/agent/loop/compaction/CompactionSession.kt`
- Modify: `ConversationCompactor.kt`(实现 `ConversationCompacting` 接口;`CompactionResult` 增加摘要溯源字段;`truncateToFitBudget` 增量记账)
- Modify: `CompactionStrategy.kt`(`CompactionResult` 加字段)
- Modify: `ModelSummaryStrategy.kt`(回填摘要覆盖数)
- Modify: `DefaultAgentLoopCoordinator.kt`(每轮 run 持有一个 session,:193/:451 两个调用点换成 session)
- Test: `app/src/test/kotlin/com/vibe/app/feature/agent/loop/compaction/CompactionSessionTest.kt`

**接口契约:**

```kotlin
// ConversationCompactor 抽出接口,便于 session 测试用假实现
interface ConversationCompacting {
    suspend fun compact(
        items: List<AgentConversationItem>,
        clientType: ClientType,
        platform: PlatformV2? = null,
    ): CompactionResult
}

// CompactionResult 新增字段(默认值保证旧调用点无感):
data class CompactionResult(
    val items: List<AgentConversationItem>,
    val estimatedTokens: Int,
    val strategyUsed: CompactionStrategyType,
    val turnsCompacted: Int,
    /** MODEL_SUMMARY 时:摘要 item 本身。 */
    val modelSummaryItem: AgentConversationItem? = null,
    /** MODEL_SUMMARY 时:该摘要覆盖了输入列表的前多少个 item。 */
    val modelSummaryCoveredCount: Int = 0,
)

// CompactionSession — 一轮 agent run 的压缩上下文,复用模型摘要
class CompactionSession(private val compactor: ConversationCompacting) {
    suspend fun compact(
        items: List<AgentConversationItem>,
        clientType: ClientType,
        platform: PlatformV2? = null,
    ): CompactionResult
}
```

- [ ] **Step 1:写 `CompactionSessionTest`(先失败)**

```kotlin
package com.vibe.app.feature.agent.loop.compaction

import com.vibe.app.data.database.entity.PlatformV2
import com.vibe.app.data.model.ClientType
import com.vibe.app.feature.agent.AgentConversationItem
import com.vibe.app.feature.agent.AgentMessageRole
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactionSessionTest {

    private fun user(text: String) = AgentConversationItem(AgentMessageRole.USER, text = text)
    private fun assistant(text: String) = AgentConversationItem(AgentMessageRole.ASSISTANT, text = text)
    private val summaryItem = assistant("[Conversation Summary]\nold turns summarized")

    /** 记录调用次数;首次返回 MODEL_SUMMARY(覆盖前 4 项),此后返回 NONE。 */
    private class FakeCompactor : ConversationCompacting {
        var calls = 0
        var modelSummaryRuns = 0
        var lastInput: List<AgentConversationItem> = emptyList()
        var nextResult: ((List<AgentConversationItem>) -> CompactionResult)? = null

        override suspend fun compact(
            items: List<AgentConversationItem>,
            clientType: ClientType,
            platform: PlatformV2?,
        ): CompactionResult {
            calls++
            lastInput = items
            return nextResult!!.invoke(items)
        }
    }

    @Test
    fun `second compact reuses cached model summary instead of re-summarizing`() = runBlocking {
        val fake = FakeCompactor()
        val session = CompactionSession(fake)
        val base = listOf(user("t1"), assistant("a1"), user("t2"), assistant("a2"), user("t3"))

        // 第一次:底层返回 MODEL_SUMMARY,覆盖前 4 项
        fake.nextResult = { items ->
            fake.modelSummaryRuns++
            CompactionResult(
                items = listOf(summaryItem) + items.drop(4),
                estimatedTokens = 100,
                strategyUsed = CompactionStrategyType.MODEL_SUMMARY,
                turnsCompacted = 2,
                modelSummaryItem = summaryItem,
                modelSummaryCoveredCount = 4,
            )
        }
        session.compact(base, ClientType.KIMI)
        assertEquals(1, fake.modelSummaryRuns)

        // 第二次:同一前缀 + 新增 2 项。session 应先用缓存摘要替换前 4 项再委托,
        // 底层看到的输入以摘要 item 开头 → 不再需要 MODEL_SUMMARY。
        fake.nextResult = { items ->
            CompactionResult(items, 120, CompactionStrategyType.NONE, 0)
        }
        val grown = base + listOf(assistant("a3"), user("t4"))
        val second = session.compact(grown, ClientType.KIMI)

        assertEquals(2, fake.calls)
        assertEquals("substituted input starts with cached summary", summaryItem, fake.lastInput.first())
        assertEquals("4 covered items replaced by 1 summary", grown.size - 4 + 1, fake.lastInput.size)
        assertEquals(1, fake.modelSummaryRuns)
        assertTrue(second.items.contains(summaryItem))
    }

    /** 前缀不匹配(历史被外部改动)时不使用缓存。 */
    @Test
    fun `cache is skipped when prefix identity does not match`() = runBlocking {
        val fake = FakeCompactor()
        val session = CompactionSession(fake)
        val base = listOf(user("t1"), assistant("a1"), user("t2"), assistant("a2"), user("t3"))
        fake.nextResult = { items ->
            CompactionResult(
                items = listOf(summaryItem) + items.drop(4),
                estimatedTokens = 100,
                strategyUsed = CompactionStrategyType.MODEL_SUMMARY,
                turnsCompacted = 2,
                modelSummaryItem = summaryItem,
                modelSummaryCoveredCount = 4,
            )
        }
        session.compact(base, ClientType.KIMI)

        fake.nextResult = { items -> CompactionResult(items, 50, CompactionStrategyType.NONE, 0) }
        val unrelated = listOf(user("different"), assistant("history"))
        session.compact(unrelated, ClientType.KIMI)

        assertEquals("no substitution for unrelated history", unrelated, fake.lastInput)
    }
}
```

- [ ] **Step 2:跑测试确认失败**

```bash
./gradlew :app:testDebugUnitTest --tests "*CompactionSessionTest*"
```
预期:FAIL(`ConversationCompacting`/`CompactionSession` 未定义、`CompactionResult` 缺字段)。

- [ ] **Step 3:实现**

(a) `CompactionStrategy.kt`:`CompactionResult` 按上方契约加两个带默认值的字段。

(b) `ModelSummaryStrategy.compact`(`ModelSummaryStrategy.kt:62-75`)返回处补充:

```kotlin
return CompactionResult(
    items = result,
    estimatedTokens = tokens,
    strategyUsed = type,
    turnsCompacted = olderTurns.size,
    modelSummaryItem = summaryItem,
    modelSummaryCoveredCount = olderTurns.sumOf { it.size },
)
```

(c) `ConversationCompactor` 声明 `: ConversationCompacting`(接口新建于 `CompactionSession.kt` 或独立文件均可,包内可见即可)。

(d) `CompactionSession.kt`:

```kotlin
package com.vibe.app.feature.agent.loop.compaction

import com.vibe.app.data.database.entity.PlatformV2
import com.vibe.app.data.model.ClientType
import com.vibe.app.feature.agent.AgentConversationItem

interface ConversationCompacting {
    suspend fun compact(
        items: List<AgentConversationItem>,
        clientType: ClientType,
        platform: PlatformV2? = null,
    ): CompactionResult
}

/**
 * Per-run compaction context. Caches the (expensive) model summary so that
 * subsequent iterations of the same agent run substitute the cached summary
 * for the already-covered prefix instead of calling the summary API again.
 *
 * Cache validity: the covered prefix is matched by OBJECT IDENTITY of its
 * last item — fullConversation is append-only within a run, so identity of
 * items[coveredCount-1] proves the prefix is unchanged.
 */
class CompactionSession(private val compactor: ConversationCompacting) {

    private var cachedSummary: AgentConversationItem? = null
    private var cachedCoveredCount: Int = 0
    private var cachedLastCoveredItem: AgentConversationItem? = null

    suspend fun compact(
        items: List<AgentConversationItem>,
        clientType: ClientType,
        platform: PlatformV2? = null,
    ): CompactionResult {
        val summary = cachedSummary
        val substituted = summary != null &&
            cachedCoveredCount in 1..items.size &&
            items[cachedCoveredCount - 1] === cachedLastCoveredItem
        val effectiveItems = if (substituted) {
            listOf(summary!!) + items.drop(cachedCoveredCount)
        } else {
            items
        }

        val result = compactor.compact(effectiveItems, clientType, platform)

        if (result.strategyUsed == CompactionStrategyType.MODEL_SUMMARY &&
            result.modelSummaryItem != null && result.modelSummaryCoveredCount > 0
        ) {
            // Map covered count back to ORIGINAL item indices when we substituted.
            val coveredInOriginal = if (substituted) {
                cachedCoveredCount + (result.modelSummaryCoveredCount - 1)
            } else {
                result.modelSummaryCoveredCount
            }
            if (coveredInOriginal in 1..items.size) {
                cachedSummary = result.modelSummaryItem
                cachedCoveredCount = coveredInOriginal
                cachedLastCoveredItem = items[coveredInOriginal - 1]
            }
        }
        return result
    }
}
```

(e) `DefaultAgentLoopCoordinator.run()`:在 `try` 块开头(`:157` 附近,`initialConversation` 之前)加 `val compactionSession = CompactionSession(conversationCompactor)`;把 `:193-197` 与 `:451-455` 两处 `conversationCompactor.compact(...)` 改为 `compactionSession.compact(...)`。

(f) `truncateToFitBudget` 去 O(n²)(`ConversationCompactor.kt:119-174`):方法开头一次性建 per-item token 数组与总和,变更时增量更新:

```kotlin
private fun truncateToFitBudget(
    items: List<AgentConversationItem>,
    tokenBudget: Int,
): List<AgentConversationItem> {
    val result = items.toMutableList()
    val itemTokens = IntArray(result.size) { i ->
        ConversationContextManager.estimateTokens(listOf(result[i]))
    }
    var total = itemTokens.sum()

    fun replace(i: Int, newItem: AgentConversationItem) {
        result[i] = newItem
        val newTokens = ConversationContextManager.estimateTokens(listOf(newItem))
        total += newTokens - itemTokens[i]
        itemTokens[i] = newTokens
    }
    // Phase 1 / Phase 2:循环条件由 estimateTokens(result) 改为 total,
    // 截断处调用 replace(i, item.copy(...));Phase 3 的整回合删除处,
    // 每 removeAt(0) 前 total -= itemTokens 对应值(用 ArrayDeque/index 偏移或
    // 重建 IntArray 均可,保持 O(n) 总量)。
    ...
}
```

Phase 3 的实现建议:把 `result`/`itemTokens` 换成 `ArrayDeque<Pair<AgentConversationItem, Int>>`,`removeFirst()` 时 `total -= it.second`;三个 phase 结束后 `return deque.map { it.first }`。保持既有语义(整回合删除、最后回合不删)不变,只改记账方式。

- [ ] **Step 4:跑测试确认通过**

```bash
./gradlew :app:testDebugUnitTest --tests "*CompactionSessionTest*" --tests "*ToolResultTrimStrategyTest*" --tests "*CurrentRunToolResultEvictorTest*"
```
预期:全部 PASS。

- [ ] **Step 5:Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/feature/agent/loop/ app/src/test/kotlin/com/vibe/app/feature/agent/loop/
git commit -m "feat(agent): cache model summaries per run and de-quadratic budget truncation (opt task 4.2)"
```

**验收标准:** 同一 run 内第二次超限压缩不再触发摘要 API(测试断言);兜底截断的 token 记账为增量式;coordinator 两个压缩调用点均走 session。

---

## Task 4.3: 结构化持久化(`TurnArtifactEntity`)与跨回合重建改造

**现状与证据:** Room 只存 `content` + `thoughts`(标记如 `[Tool] write_project_file` 不含路径,`AgentSessionManager.kt:251,271`);`currentPlan` 是 run() 局部变量(`DefaultAgentLoopCoordinator.kt:163`)回合结束即丢(D6);跨回合重建 `compactCrossTurnHistory` 用 `take(maxChars)` 保头弃尾,assistant 的结论在尾部反而被丢(D8,:672-677);超大 USER 消息无处理路径。

**改动文件:**
- Create: `app/src/main/kotlin/com/vibe/app/data/database/entity/TurnArtifactEntity.kt`
- Create: `app/src/main/kotlin/com/vibe/app/data/database/dao/TurnArtifactDao.kt`
- Create: `app/src/main/kotlin/com/vibe/app/feature/agent/service/TurnArtifactCollector.kt`
- Create: `app/src/main/kotlin/com/vibe/app/feature/agent/loop/TurnArtifactSummaryFormatter.kt`
- Modify: `ChatDatabaseV2.kt`(注册实体,version 3→4;**以执行时实际版本为准递增**)
- Modify: `di/DatabaseModule.kt`(新增 Migration)
- Modify: `data/repository/ChatRepository` 接口与 `ChatRepositoryImpl`(save/load artifact)
- Modify: `AgentSessionManager.kt`(采集 + 落库 + 启动时装载进请求)
- Modify: `feature/agent/AgentModels.kt`(`AgentLoopRequest` 加 `turnArtifacts`;`AgentPlan`/`AgentPlanStep` 加 `@Serializable`)
- Modify: `DefaultAgentLoopCoordinator.kt`(重建逻辑 + plan 恢复)
- Test: `app/src/test/kotlin/com/vibe/app/feature/agent/service/TurnArtifactCollectorTest.kt`
- Test: `app/src/test/kotlin/com/vibe/app/feature/agent/loop/TurnArtifactSummaryFormatterTest.kt`

**接口契约:**

```kotlin
@Entity(
    tableName = "turn_artifacts",
    foreignKeys = [ForeignKey(
        entity = ChatRoomV2::class,
        parentColumns = ["chat_id"],
        childColumns = ["chat_id"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["chat_id"])],
)
data class TurnArtifactEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo("artifact_id") val id: Int = 0,
    @ColumnInfo(name = "chat_id") val chatId: Int,
    /** 与 AgentLoopRequest.userMessages 的下标对齐(第 N 条用户消息 = turnIndex N)。 */
    @ColumnInfo(name = "turn_index") val turnIndex: Int,
    @ColumnInfo(name = "user_request") val userRequest: String,       // ≤200 chars
    @ColumnInfo(name = "files_modified") val filesModified: List<String>, // StringListConverter
    @ColumnInfo(name = "build_status") val buildStatus: String?,      // "SUCCESS" | "FAILED" | null
    @ColumnInfo(name = "error_summary") val errorSummary: String?,    // ≤300 chars
    @ColumnInfo(name = "tool_sequence") val toolSequence: List<String>, // "name:ok"/"name:err",≤50 条
    @ColumnInfo(name = "plan_json") val planJson: String?,            // AgentPlan 的 kotlinx JSON
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Dao interface TurnArtifactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(artifact: TurnArtifactEntity)
    @Query("SELECT * FROM turn_artifacts WHERE chat_id = :chatId ORDER BY turn_index ASC")
    suspend fun loadByChatId(chatId: Int): List<TurnArtifactEntity>
}

class TurnArtifactCollector {                       // 纯内存,无 Android 依赖
    fun onToolStarted(toolName: String, arguments: JsonElement)
    fun onToolFinished(toolName: String, isError: Boolean, output: JsonElement)
    fun onPlan(plan: AgentPlan)
    fun onFailure(message: String)
    fun build(chatId: Int, turnIndex: Int, userRequest: String, createdAt: Long): TurnArtifactEntity
}

object TurnArtifactSummaryFormatter {
    /** 确定性摘要,≤600 字符。 */
    fun format(artifact: TurnArtifactEntity): String
}
```

- [ ] **Step 1:写 `TurnArtifactCollectorTest` 与 `TurnArtifactSummaryFormatterTest`(先失败)**

```kotlin
package com.vibe.app.feature.agent.service

import com.vibe.app.feature.agent.AgentPlan
import com.vibe.app.feature.agent.AgentPlanStep
import com.vibe.app.feature.agent.PlanStepStatus
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnArtifactCollectorTest {

    private fun args(path: String) = buildJsonObject { put("path", JsonPrimitive(path)) }
    private val okOutput = buildJsonObject { put("status", JsonPrimitive("ok")) }

    @Test
    fun `collects modified files from write edit delete tools`() {
        val c = TurnArtifactCollector()
        c.onToolStarted("write_project_file", args("src/Main.java"))
        c.onToolFinished("write_project_file", isError = false, output = okOutput)
        c.onToolStarted("edit_project_file", args("res/layout/activity_main.xml"))
        c.onToolFinished("edit_project_file", isError = false, output = okOutput)
        c.onToolStarted("read_project_file", args("src/Main.java")) // read 不算修改
        c.onToolFinished("read_project_file", isError = false, output = okOutput)

        val a = c.build(chatId = 1, turnIndex = 0, userRequest = "add dark mode", createdAt = 123L)
        assertEquals(listOf("src/Main.java", "res/layout/activity_main.xml"), a.filesModified)
        assertEquals(listOf("write_project_file:ok", "edit_project_file:ok", "read_project_file:ok"), a.toolSequence)
    }

    @Test
    fun `records build status from last run_build_pipeline result`() {
        val c = TurnArtifactCollector()
        c.onToolStarted("run_build_pipeline", buildJsonObject {})
        c.onToolFinished("run_build_pipeline", isError = true,
            output = buildJsonObject { put("errorMessage", JsonPrimitive("cannot find symbol Foo")) })
        c.onToolStarted("run_build_pipeline", buildJsonObject {})
        c.onToolFinished("run_build_pipeline", isError = false, output = okOutput)

        val a = c.build(1, 0, "u", 0L)
        assertEquals("SUCCESS", a.buildStatus)
        assertTrue(a.errorSummary!!.contains("cannot find symbol"))
    }

    @Test
    fun `keeps latest plan as json and truncates user request`() {
        val c = TurnArtifactCollector()
        c.onPlan(AgentPlan(
            summary = "build a timer app",
            steps = listOf(AgentPlanStep(1, "layout", PlanStepStatus.COMPLETED)),
            createdAtIteration = 1,
        ))
        val a = c.build(1, 2, "x".repeat(500), 0L)
        assertEquals(200, a.userRequest.length)
        assertTrue(a.planJson!!.contains("timer app"))
    }

    @Test
    fun `no build no plan yields nulls`() {
        val a = TurnArtifactCollector().build(1, 0, "u", 0L)
        assertNull(a.buildStatus)
        assertNull(a.planJson)
        assertNull(a.errorSummary)
    }
}
```

```kotlin
package com.vibe.app.feature.agent.loop

import com.vibe.app.data.database.entity.TurnArtifactEntity
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnArtifactSummaryFormatterTest {

    private fun artifact() = TurnArtifactEntity(
        chatId = 1, turnIndex = 0,
        userRequest = "add dark mode toggle",
        filesModified = listOf("src/Main.java", "res/values/colors.xml"),
        buildStatus = "SUCCESS",
        errorSummary = "cannot find symbol Foo (fixed)",
        toolSequence = listOf("write_project_file:ok", "run_build_pipeline:ok"),
        planJson = null,
        createdAt = 0L,
    )

    @Test
    fun `summary contains request, files, build status and errors`() {
        val s = TurnArtifactSummaryFormatter.format(artifact())
        assertTrue(s.contains("add dark mode toggle"))
        assertTrue(s.contains("src/Main.java"))
        assertTrue(s.contains("res/values/colors.xml"))
        assertTrue(s.contains("SUCCESS"))
        assertTrue(s.contains("cannot find symbol"))
    }

    @Test
    fun `summary is capped at 600 chars`() {
        val many = artifact().copy(filesModified = List(100) { "very/long/path/to/some/file_$it.java" })
        assertTrue(TurnArtifactSummaryFormatter.format(many).length <= 600)
    }
}
```

- [ ] **Step 2:跑测试确认失败**

```bash
./gradlew :app:testDebugUnitTest --tests "*TurnArtifactCollectorTest*" --tests "*TurnArtifactSummaryFormatterTest*"
```
预期:FAIL(类未定义)。

- [ ] **Step 3:实现数据层**

(a) 按契约创建 `TurnArtifactEntity` + `TurnArtifactDao`(`List<String>` 列复用既有 `StringListConverter`,与 `MessageV2.files` 相同机制);
(b) `ChatDatabaseV2.kt`:entities 数组加 `TurnArtifactEntity::class`,`version = 4`,新增 `abstract fun turnArtifactDao(): TurnArtifactDao`;
(c) `di/DatabaseModule.kt` 仿照 `MIGRATION_CHAT_DB_V2_2_3`(:24-43)新增:

```kotlin
private val MIGRATION_CHAT_DB_V2_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `turn_artifacts` (
                `artifact_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `chat_id` INTEGER NOT NULL,
                `turn_index` INTEGER NOT NULL,
                `user_request` TEXT NOT NULL,
                `files_modified` TEXT NOT NULL,
                `build_status` TEXT,
                `error_summary` TEXT,
                `tool_sequence` TEXT NOT NULL,
                `plan_json` TEXT,
                `created_at` INTEGER NOT NULL,
                FOREIGN KEY(`chat_id`) REFERENCES `chats_v2`(`chat_id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_turn_artifacts_chat_id` ON `turn_artifacts` (`chat_id`)")
    }
}
```
并加入 `.addMigrations(...)` 列表(:117-119)。**注意:若执行时 DB 版本已非 3(其他 phase 先动了库),迁移号顺延并在实施记录中说明。**
(d) `ChatRepository` 接口加 `suspend fun saveTurnArtifact(artifact: TurnArtifactEntity)` 与 `suspend fun loadTurnArtifacts(chatId: Int): List<TurnArtifactEntity>`,`ChatRepositoryImpl` 直通 DAO。

- [ ] **Step 4:实现 `TurnArtifactCollector` 与 `TurnArtifactSummaryFormatter`**

Collector(要点:写类工具集合与 coordinator 的 `WRITE_TOOL_NAMES` 保持一致,见 `DefaultAgentLoopCoordinator.kt:733-739`):

```kotlin
class TurnArtifactCollector {
    private val filesModified = LinkedHashSet<String>()
    private val toolSequence = mutableListOf<String>()
    private val pendingArgs = mutableMapOf<String, JsonElement>() // toolName -> last args
    private var buildStatus: String? = null
    private var errorSummary: String? = null
    private var plan: AgentPlan? = null

    fun onToolStarted(toolName: String, arguments: JsonElement) {
        pendingArgs[toolName] = arguments
    }

    fun onToolFinished(toolName: String, isError: Boolean, output: JsonElement) {
        if (toolSequence.size < MAX_TOOL_SEQUENCE) {
            toolSequence += "$toolName:${if (isError) "err" else "ok"}"
        }
        if (!isError && toolName in FILE_MUTATION_TOOLS) {
            (pendingArgs[toolName] as? JsonObject)
                ?.get("path")?.jsonPrimitive?.contentOrNull
                ?.let { filesModified += it }
        }
        if (toolName == "run_build_pipeline") {
            buildStatus = if (isError) "FAILED" else "SUCCESS"
            if (isError) {
                errorSummary = ((output as? JsonObject)?.get("errorMessage")
                    ?.jsonPrimitive?.contentOrNull ?: "build failed").take(300)
            }
        }
    }

    fun onPlan(plan: AgentPlan) { this.plan = plan }
    fun onFailure(message: String) { errorSummary = message.take(300) }

    fun build(chatId: Int, turnIndex: Int, userRequest: String, createdAt: Long) =
        TurnArtifactEntity(
            chatId = chatId, turnIndex = turnIndex,
            userRequest = userRequest.take(200),
            filesModified = filesModified.toList(),
            buildStatus = buildStatus,
            errorSummary = errorSummary,
            toolSequence = toolSequence.toList(),
            planJson = plan?.let { Json.encodeToString(AgentPlan.serializer(), it) },
            createdAt = createdAt,
        )

    companion object {
        private const val MAX_TOOL_SEQUENCE = 50
        private val FILE_MUTATION_TOOLS =
            setOf("write_project_file", "edit_project_file", "delete_project_file")
    }
}
```

`AgentPlan`/`AgentPlanStep`/`PlanStepStatus`(`feature/agent/AgentModels.kt:114-124`)加 `@Serializable`(kotlinx.serialization 已在依赖中,payload 全用它)。

Formatter:

```kotlin
object TurnArtifactSummaryFormatter {
    private const val MAX_CHARS = 600

    fun format(artifact: TurnArtifactEntity): String = buildString {
        append("[Turn ${artifact.turnIndex + 1} summary]\n")
        append("User: ${artifact.userRequest}\n")
        if (artifact.filesModified.isNotEmpty()) {
            append("Files changed: ")
            append(artifact.filesModified.joinToString(", "))
            append("\n")
        }
        artifact.buildStatus?.let { append("Build: $it\n") }
        artifact.errorSummary?.let { append("Errors: $it\n") }
    }.let { text ->
        if (text.length <= MAX_CHARS) text.trimEnd()
        else text.take(MAX_CHARS - 20).trimEnd() + "\n[... truncated]"
    }
}
```

- [ ] **Step 5:跑 Step 1 的两个测试确认通过**

```bash
./gradlew :app:testDebugUnitTest --tests "*TurnArtifactCollectorTest*" --tests "*TurnArtifactSummaryFormatterTest*"
```

- [ ] **Step 6:接线 `AgentSessionManager`**

(a) `startSession`:`scope.launch` 内构造 `AgentLoopRequest` 前,`val artifacts = runCatching { chatRepository.loadTurnArtifacts(chatId) }.getOrDefault(emptyList())`,传入 `AgentLoopRequest(turnArtifacts = artifacts, ...)`(`AgentLoopRequest` 定义在 `AgentModels.kt:61-70`,加字段 `val turnArtifacts: List<TurnArtifactEntity> = emptyList()`);同时 `val collector = TurnArtifactCollector()` 保存到新的 `ConcurrentHashMap<Int, TurnArtifactCollector>`(仿 `messageStates`);
(b) `applyEvent`(:225)对应分支调 collector:`ToolExecutionStarted → collector.onToolStarted(event.call.name, event.call.arguments)`;`ToolExecutionFinished → collector.onToolFinished(event.result.toolName, event.result.isError, event.result.output)`;`PlanCreated/PlanUpdated → collector.onPlan(event.plan)`;`LoopFailed → collector.onFailure(event.message)`;
(c) `saveToRoom`(:430):`saveChat` 成功后追加:

```kotlin
turnArtifactCollectors[chatId]?.let { collector ->
    runCatching {
        chatRepository.saveTurnArtifact(collector.build(
            chatId = savedChatRoom.id,
            turnIndex = state.userMessages.lastIndex,
            userRequest = state.userMessages.lastOrNull()?.content.orEmpty(),
            createdAt = System.currentTimeMillis() / 1000,
        ))
    }.onFailure { Log.w(TAG, "Failed to save turn artifact", it) }
}
```
collector 的清理跟随 `clearMessageState`/下次 `startSession` 覆盖(与 messageStates 同生命周期)。

- [ ] **Step 7:改造 coordinator 的跨回合重建与 plan 恢复**

(a) `compactCrossTurnHistory`(:646-681)重写核心:超预算时,遍历 assistant 项(newest first,沿用现有 `assistantIndices`),`rank == 0`(最近一条)改为 **头 1000 + 尾 3000**;`rank >= 1` 时若存在 `request.turnArtifacts.firstOrNull { it.turnIndex == 该 assistant 对应的 userMessages 下标 }` 则整体替换为 `TurnArtifactSummaryFormatter.format(artifact)`,无 artifact 的旧消息退化为现状的 `take(MAX_SUMMARY_CHARS)`;另对所有 USER 项(当前最后一条除外)`text.length > 8000` 时截为头 2000 + 尾 1000。新增纯函数并配测试:

```kotlin
internal fun truncateHeadTail(text: String, head: Int, tail: Int): String {
    if (text.length <= head + tail + OMISSION_MARKER_ALLOWANCE) return text
    val omitted = text.length - head - tail
    return text.take(head) + "\n[... $omitted chars omitted ...]\n" + text.takeLast(tail)
}
```
(assistant 对应 userMessages 下标:`buildInitialConversation` 按 index 配对,重建时同步记录每个 assistant item 的 turnIndex——最直接的实现是让 `buildInitialConversation` 在配对循环里就地完成 artifact 替换判断,而不是事后在 `compactCrossTurnHistory` 反推下标。)

(b) plan 恢复:`run()` 中 `var currentPlan: AgentPlan? = null`(:163)改为:

```kotlin
var currentPlan: AgentPlan? = request.turnArtifacts.lastOrNull()?.planJson
    ?.let { runCatching { Json.decodeFromString(AgentPlan.serializer(), it) }.getOrNull() }
    ?.takeIf { plan -> plan.steps.any { it.status != PlanStepStatus.COMPLETED } }
```
(仅当上轮 plan 还有未完成步骤时延续;`buildInstructions` 的 `[Active Plan]` 注入无需改动。)

新增 `TruncateHeadTailTest`(与 FormatterTest 同文件或独立):

```kotlin
@Test
fun `keeps head and tail, marks omission`() {
    val text = "H".repeat(3000) + "T".repeat(2000)
    val out = truncateHeadTail(text, head = 1000, tail = 500)
    assertTrue(out.startsWith("H".repeat(1000)))
    assertTrue(out.endsWith("T".repeat(500)))
    assertTrue(out.contains("chars omitted"))
}

@Test
fun `short text returned unchanged`() {
    assertEquals("short", truncateHeadTail("short", 1000, 500))
}
```

- [ ] **Step 8:全量验证 + 手动迁移检查**

```bash
./gradlew :app:testDebugUnitTest && ./gradlew assembleDebug
```
预期:全绿。装到带旧数据的设备上打开历史聊天(验证 3→4 迁移不炸、旧聊天无 artifact 时行为与之前一致)。

- [ ] **Step 9:Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/ app/src/test/kotlin/com/vibe/app/ app/schemas 2>/dev/null || git add -A app/
git commit -m "feat(agent): persist structured turn artifacts and rebuild cross-turn context from them (opt task 4.3)"
```

**验收标准:** 新回合结束后 `turn_artifacts` 有记录(含文件清单/build 状态);下一回合的初始上下文中旧回合呈现为确定性摘要而非保头截断;最近 assistant 保尾;未完成的 plan 跨回合延续;旧库升级无损。

---

## Task 4.4: token 估算校准(`TokenRatioCalibrator`)+ 估算补全

**现状与证据:** 估算纯字符启发式且从不校准(`ConversationContextManager.kt:122-148`);Anthropic 真实 usage 只进诊断(`AnthropicMessagesAgentGateway.kt:120-126` → `trace.markInputTokens`);图片附件不计(gateway 却全量重发 base64,:282-286);compact 只比较 conversation,system prompt(约 12.6KB)与工具 schema 不计(`ConversationCompactor.kt:44-47`)。

**改动文件:**
- Create: `app/src/main/kotlin/com/vibe/app/feature/agent/loop/compaction/TokenRatioCalibrator.kt`
- Modify: `feature/agent/AgentContracts.kt`(`AgentModelEvent.Completed` 加 `promptTokens: Int? = null`)
- Modify: `AnthropicMessagesAgentGateway.kt`(usage → Completed 事件)
- Modify: `ConversationCompactor.kt`(compact 签名加 `overheadTokens`,估算乘校准系数)
- Modify: `CompactionSession.kt`(透传 overheadTokens)
- Modify: `DefaultAgentLoopCoordinator.kt`(计算 overhead、Completed 时回填校准、附件估算)
- Test: `app/src/test/kotlin/com/vibe/app/feature/agent/loop/compaction/TokenRatioCalibratorTest.kt`

**接口契约:**

```kotlin
@Singleton
class TokenRatioCalibrator @Inject constructor(
    private val store: RatioStore,
) {
    /** 估算值 → 校准值 的乘数,默认 1.0,收敛后 clamp 在 [0.5, 3.0]。 */
    fun ratioFor(clientType: ClientType): Float
    /** 用一次真实 usage 观测更新滑动平均(EMA, alpha=0.3)。 */
    fun record(clientType: ClientType, estimatedTokens: Int, actualTokens: Int)

    interface RatioStore {                    // 生产实现:SharedPreferences("token_ratio_calibration")
        fun read(key: String): Float?
        fun write(key: String, value: Float)
    }
}
```

- [ ] **Step 1:写 `TokenRatioCalibratorTest`(先失败)**

```kotlin
package com.vibe.app.feature.agent.loop.compaction

import com.vibe.app.data.model.ClientType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenRatioCalibratorTest {

    private class MemoryStore : TokenRatioCalibrator.RatioStore {
        val map = mutableMapOf<String, Float>()
        override fun read(key: String) = map[key]
        override fun write(key: String, value: Float) { map[key] = value }
    }

    @Test
    fun `defaults to ratio 1`() {
        val c = TokenRatioCalibrator(MemoryStore())
        assertEquals(1.0f, c.ratioFor(ClientType.KIMI), 0.001f)
    }

    @Test
    fun `converges toward actual over estimated`() {
        val c = TokenRatioCalibrator(MemoryStore())
        // 实际值总是估算值的 1.5 倍 → ratio 应逼近 1.5
        repeat(20) { c.record(ClientType.KIMI, estimatedTokens = 1000, actualTokens = 1500) }
        assertEquals(1.5f, c.ratioFor(ClientType.KIMI), 0.05f)
    }

    @Test
    fun `ratio is clamped and per-provider isolated`() {
        val c = TokenRatioCalibrator(MemoryStore())
        repeat(20) { c.record(ClientType.QWEN, 100, 100_000) }   // 疯狂观测
        assertTrue(c.ratioFor(ClientType.QWEN) <= 3.0f)
        assertEquals(1.0f, c.ratioFor(ClientType.KIMI), 0.001f)  // 不串台
    }

    @Test
    fun `ignores degenerate observations`() {
        val c = TokenRatioCalibrator(MemoryStore())
        c.record(ClientType.KIMI, estimatedTokens = 0, actualTokens = 500)
        assertEquals(1.0f, c.ratioFor(ClientType.KIMI), 0.001f)
    }
}
```

- [ ] **Step 2:跑测试确认失败**

```bash
./gradlew :app:testDebugUnitTest --tests "*TokenRatioCalibratorTest*"
```

- [ ] **Step 3:实现校准器 + 生产 Store**

```kotlin
@Singleton
class TokenRatioCalibrator @Inject constructor(
    private val store: RatioStore,
) {
    private val cache = ConcurrentHashMap<String, Float>()

    fun ratioFor(clientType: ClientType): Float =
        cache.getOrPut(clientType.name) { store.read(clientType.name) ?: 1.0f }

    fun record(clientType: ClientType, estimatedTokens: Int, actualTokens: Int) {
        if (estimatedTokens <= 0 || actualTokens <= 0) return
        val observed = (actualTokens.toFloat() / estimatedTokens).coerceIn(MIN_RATIO, MAX_RATIO)
        val current = ratioFor(clientType)
        val updated = (current * (1 - ALPHA) + observed * ALPHA).coerceIn(MIN_RATIO, MAX_RATIO)
        cache[clientType.name] = updated
        store.write(clientType.name, updated)
    }

    interface RatioStore {
        fun read(key: String): Float?
        fun write(key: String, value: Float)
    }

    companion object {
        private const val ALPHA = 0.3f
        private const val MIN_RATIO = 0.5f
        private const val MAX_RATIO = 3.0f
    }
}
```

生产 Store 在 `di` 层提供(新建或并入现有 module,仿 `NetworkModule` 的 @Provides 风格):

```kotlin
@Provides @Singleton
fun provideRatioStore(@ApplicationContext context: Context): TokenRatioCalibrator.RatioStore =
    object : TokenRatioCalibrator.RatioStore {
        private val prefs = context.getSharedPreferences("token_ratio_calibration", Context.MODE_PRIVATE)
        override fun read(key: String) =
            prefs.getFloat(key, Float.NaN).takeUnless { it.isNaN() }
        override fun write(key: String, value: Float) {
            prefs.edit().putFloat(key, value).apply()
        }
    }
```

- [ ] **Step 4:usage 回传链路**

(a) `AgentModelEvent.Completed`(`AgentContracts.kt:57-61`)加 `val promptTokens: Int? = null`;
(b) `AnthropicMessagesAgentGateway`:`MessageStartResponseChunk` 分支(:120-126)已计算总输入 tokens——存入局部 `var observedPromptTokens: Int? = null`,发 `Completed` 的位置带上 `promptTokens = observedPromptTokens`;
(c) 其余 gateway(Kimi/Qwen/DeepSeek/OpenAI):检查各自流式 DTO 是否解析 usage(基线代码 grep `usage` 无命中)——**有则接上,没有则保持 null,不在本 task 扩 DTO**(校准对这些 provider 自然退化为启发式,行为不劣于现状);
(d) `DefaultAgentLoopCoordinator`:发起 `streamTurn` 前计算本次请求的估算值(见 Step 5 的 overhead + conversation 估算),`Completed` 分支(:240-245)中:

```kotlin
is AgentModelEvent.Completed -> {
    previousResponseId = event.responseId ?: previousResponseId
    if (event.reasoningContent != null) turnReasoningContent = event.reasoningContent
    event.promptTokens?.let { actual ->
        tokenRatioCalibrator.record(request.platform.compatibleType, lastEstimatedPromptTokens, actual)
    }
}
```
(`tokenRatioCalibrator` 构造注入 coordinator;`lastEstimatedPromptTokens` 为循环内局部变量,每次迭代发请求前赋值。)

- [ ] **Step 5:估算补全(overhead + 附件 + 校准系数)**

(a) `ConversationCompactor.compact` 签名加 `overheadTokens: Int = 0`(接口 `ConversationCompacting` 与 `CompactionSession` 同步透传);预算比较全部由 `currentTokens <= X` 改为 `currentTokens + overheadTokens <= X`;并注入 `TokenRatioCalibrator`,所有 `ConversationContextManager.estimateTokens(...)` 调用点的结果统一乘 `ratioFor(clientType)` 后取整(封装私有方法 `private fun estimate(items, clientType) = (ConversationContextManager.estimateTokens(items) * calibrator.ratioFor(clientType)).toInt()`);
(b) coordinator 每次迭代压缩前计算:

```kotlin
val instructions = buildInstructions(request, currentPlan, mode, memo)
val overheadTokens = ConversationContextManager.estimateTokens(instructions) +
    ConversationContextManager.estimateTokens(request.tools.joinToString { it.inputSchema.toString() + it.description }) +
    estimateAttachmentTokens(fullConversation)
lastEstimatedPromptTokens = overheadTokens + compactionResult.estimatedTokens
```
(把 `buildInstructions` 的调用从 `AgentModelRequest` 构造处上提复用,避免调两次;)
(c) 附件估算(coordinator 内私有,Android API 可用):

```kotlin
private fun estimateAttachmentTokens(items: List<AgentConversationItem>): Int =
    items.sumOf { item ->
        item.attachments.sumOf { path ->
            runCatching {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, opts)
                if (opts.outWidth > 0 && opts.outHeight > 0) {
                    (opts.outWidth.toLong() * opts.outHeight / 750).toInt().coerceAtMost(1600)
                } else DEFAULT_IMAGE_TOKENS
            }.getOrDefault(DEFAULT_IMAGE_TOKENS)
        }
    }
// companion: private const val DEFAULT_IMAGE_TOKENS = 1000
```

- [ ] **Step 6:验证 + Commit**

```bash
./gradlew :app:testDebugUnitTest && ./gradlew assembleDebug
```
真机冒烟:用 Anthropic platform 跑一轮多迭代会话,`adb shell run-as com.vibe.app cat shared_prefs/token_ratio_calibration.xml`(或 Device Explorer)确认 ratio 被写入且非 1.0。

```bash
git add -A app/
git commit -m "feat(agent): calibrate token estimation with real usage and count overhead/attachments (opt task 4.4)"
```

**验收标准:** 校准器测试全绿;Anthropic 会话跑过后 ratio 持久化;压缩预算比较包含 system prompt + 工具 schema + 图片估算;无 usage 的 provider 行为不变。

---

## Task 4.5: 预算配置化(`ContextBudgetResolver` + PlatformV2 扩列 + 设置 UI)

**现状与证据:** 预算按 provider 写死且与模型无关(`ProviderContextBudget.kt:10-17`,Kimi 24K vs kimi-k2.5 实际窗口大得多);Anthropic `maxTokens = 16000` 硬编码(`AnthropicMessagesAgentGateway.kt:86,300`);Qwen/Kimi/DeepSeek 请求不设 max_tokens(评审 D9);`PlatformV2` 无窗口字段(`entity/PlatformV2.kt`)。

**改动文件:**
- Modify: `entity/PlatformV2.kt`(加两列)
- Modify: `ChatDatabaseV2.kt`(version 4→5)+ `di/DatabaseModule.kt`(ALTER TABLE 迁移)
- Create: `app/src/main/kotlin/com/vibe/app/feature/agent/loop/compaction/ContextBudgetResolver.kt`
- Modify: `ConversationCompactor.kt` 与 `DefaultAgentLoopCoordinator.compactCrossTurnHistory`(预算取值点换 resolver)
- Modify: `AnthropicMessagesAgentGateway.kt`(:86 用 platform 值)
- Modify: Qwen/Kimi/DeepSeek 三个 gateway 与对应 request DTO(补 `max_tokens`)
- Modify: `presentation/ui/setting/PlatformSettingDialogs.kt` + `PlatformSettingViewModel.kt` + 字符串资源
- Test: `app/src/test/kotlin/com/vibe/app/feature/agent/loop/compaction/ContextBudgetResolverTest.kt`

**接口契约:**

```kotlin
object ContextBudgetResolver {
    /** 用户配置了 contextWindowTokens 时:预算 = window − maxOutput(下限 8000);否则回落 provider 默认表。 */
    fun resolve(platform: PlatformV2): ProviderContextBudget
    const val DEFAULT_MAX_OUTPUT_TOKENS = 16_000
    private const val MIN_BUDGET = 8_000
}
```

- [ ] **Step 1:写 `ContextBudgetResolverTest`(先失败)**

```kotlin
package com.vibe.app.feature.agent.loop.compaction

import com.vibe.app.data.database.entity.PlatformV2
import com.vibe.app.data.model.ClientType
import org.junit.Assert.assertEquals
import org.junit.Test

class ContextBudgetResolverTest {

    private fun platform(window: Int?, maxOut: Int?) = PlatformV2(
        name = "test", compatibleType = ClientType.KIMI,
        apiUrl = "https://api.example.com", model = "kimi-k2.5",
        contextWindowTokens = window, maxOutputTokens = maxOut,
    )

    @Test
    fun `falls back to provider defaults when window unset`() {
        val b = ContextBudgetResolver.resolve(platform(window = null, maxOut = null))
        assertEquals(ProviderContextBudget.forProvider(ClientType.KIMI), b)
    }

    @Test
    fun `derives budget from configured window minus max output`() {
        val b = ContextBudgetResolver.resolve(platform(window = 128_000, maxOut = 8_000))
        assertEquals(120_000, b.maxTokens)
        assertEquals(ProviderContextBudget.forProvider(ClientType.KIMI).recentTurns, b.recentTurns)
    }

    @Test
    fun `applies floor for tiny windows`() {
        val b = ContextBudgetResolver.resolve(platform(window = 10_000, maxOut = 9_000))
        assertEquals(8_000, b.maxTokens)
    }
}
```

- [ ] **Step 2:跑测试确认失败,然后实现**

```bash
./gradlew :app:testDebugUnitTest --tests "*ContextBudgetResolverTest*"
```

```kotlin
object ContextBudgetResolver {
    const val DEFAULT_MAX_OUTPUT_TOKENS = 16_000
    private const val MIN_BUDGET = 8_000

    fun resolve(platform: PlatformV2): ProviderContextBudget {
        val defaults = ProviderContextBudget.forProvider(platform.compatibleType)
        val window = platform.contextWindowTokens ?: return defaults
        val maxOut = platform.maxOutputTokens ?: DEFAULT_MAX_OUTPUT_TOKENS
        return defaults.copy(maxTokens = (window - maxOut).coerceAtLeast(MIN_BUDGET))
    }
}
```

- [ ] **Step 3:PlatformV2 扩列 + 迁移**

`PlatformV2` 末尾加:

```kotlin
@ColumnInfo(name = "context_window_tokens")
val contextWindowTokens: Int? = null,

@ColumnInfo(name = "max_output_tokens")
val maxOutputTokens: Int? = null,
```

`ChatDatabaseV2.version` 4→5;`DatabaseModule` 加:

```kotlin
private val MIGRATION_CHAT_DB_V2_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `platform_v2` ADD COLUMN `context_window_tokens` INTEGER")
        db.execSQL("ALTER TABLE `platform_v2` ADD COLUMN `max_output_tokens` INTEGER")
    }
}
```
并注册。(版本号同样以执行时实际值顺延。)

- [ ] **Step 4:替换预算取值点 + gateway max_tokens**

(a) `ConversationCompactor.compact` 内 `ProviderContextBudget.forProvider(clientType)`(:43)改为:`platform` 非空时 `ContextBudgetResolver.resolve(platform)`,为空回落 `forProvider(clientType)`;`compactCrossTurnHistory`(coordinator :650)同理改 `ContextBudgetResolver.resolve(request.platform)`;
(b) `AnthropicMessagesAgentGateway.kt:86`:`maxTokens = request.platform.maxOutputTokens ?: DEFAULT_MAX_TOKENS`;
(c) Qwen/Kimi/DeepSeek gateway 构造请求处传 `maxTokens = request.platform.maxOutputTokens`;对应 request DTO(如 `data/dto/qwen/request/QwenChatCompletionRequest`)若无该字段则补:

```kotlin
@SerialName("max_tokens")
val maxTokens: Int? = null,
```
(kotlinx.serialization 配置了 `explicitNulls = false` 时 null 不下发,先确认 DTO 的 Json 配置与 `AnthropicMessagesAgentGateway.kt:70` 一致;若否,给字段配 `@EncodeDefault(NEVER)` 或维持库内既有可空字段处理模式。)

- [ ] **Step 5:设置 UI**

`PlatformSettingDialogs.kt` 仿 model 字段(:315-319)在高级区(temperature 附近)追加两个数字输入:

```kotlin
OutlinedTextField(
    modifier = Modifier.fillMaxWidth(),
    value = contextWindowText,
    onValueChange = { contextWindowText = it.filter { ch -> ch.isDigit() } },
    label = { Text(stringResource(R.string.context_window_tokens)) },
    placeholder = { Text(stringResource(R.string.context_window_tokens_hint)) },
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    singleLine = true,
)
// max_output_tokens 同构,label = R.string.max_output_tokens
```
状态初始化 `platform.contextWindowTokens?.toString() ?: ""`,保存时 `text.toIntOrNull()` 写回 `platform.copy(contextWindowTokens = ..., maxOutputTokens = ...)`(经 `PlatformSettingViewModel` 现有保存路径)。字符串资源加到 `values/strings.xml` 及项目实际存在的中文资源目录(如 `values-zh-rCN/strings.xml`):

```xml
<string name="context_window_tokens">Context window (tokens)</string>
<string name="context_window_tokens_hint">e.g. 128000 — leave empty for default</string>
<string name="max_output_tokens">Max output tokens</string>
```
(中文:`上下文窗口(tokens)` / `如 128000,留空使用默认` / `最大输出 tokens`。)

- [ ] **Step 6:验证 + Commit**

```bash
./gradlew :app:testDebugUnitTest && ./gradlew assembleDebug
```
真机冒烟:平台设置里给 Kimi 配 window=128000 → 长会话不再在 24K 就疯狂压缩(可从 chat 诊断日志的 compaction 事件确认);清空配置回落默认。

```bash
git add -A app/
git commit -m "feat(agent): per-model context window and max output configuration (opt task 4.5)"
```

**验收标准:** resolver 测试全绿;两列迁移无损;Anthropic 输出上限与 OpenAI 系 max_tokens 均可配;设置 UI 可留空回落默认。

---

## Phase 完成检查

- [ ] 全量测试:`./gradlew test && ./gradlew assembleDebug` 全绿
- [ ] 人工验证清单(Android 10+ 真机/模拟器):
  - [ ] 单回合连读 5 个大文件 + 3 次 build(Kimi 或 Qwen 平台):会话不再报 context 超限,诊断日志出现 `CURRENT_RUN_EVICTION`;
  - [ ] 同一 run 超限后继续多次迭代:模型摘要 API 只被调用一次(诊断日志无重复 MODEL_SUMMARY);
  - [ ] 跨回合:第二回合开场,模型能"知道"上回合改过哪些文件(观察其不再盲目重新 list/read);
  - [ ] 中途切换 platform(如 Kimi → Anthropic)后继续对话,上下文延续、无 400 错误;
  - [ ] 旧版本数据库升级后历史聊天可正常打开与继续。
- [ ] 更新 `00-progress.md`:Phase 4 状态 → ✅ 已完成,填完成日期
- [ ] `git commit -m "docs: mark optimization phase 4 complete"`

## 实施记录(执行时追加)

| 日期 | 执行者 | 完成内容 | 偏离/备注 |
|------|--------|----------|-----------|
| | | | |
