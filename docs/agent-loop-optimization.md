# Agent Loop 优化设计文档

## 1. 问题背景

### 1.1 触发场景日志分析

用户请求："请帮我做一个番茄时钟 App，25 分钟工作 5 分钟休息，要有好看的倒计时动画"

Agent Loop 执行轨迹（共 8 次迭代，全部耗尽）：

| 迭代 | 工具调用 | 说明 |
|------|---------|------|
| 1 | `rename_project` | 重命名项目 |
| 2 | `read_project_file` | 读 MainActivity.java |
| 3 | `read_project_file` | 读 activity_main.xml |
| 4 | `read_project_file` | 读 strings.xml |
| 5 | `write_project_file` | 写 MainActivity.java（第一版，缺少引用） |
| 6 | `write_project_file` | 写 activity_main.xml |
| 7 | `write_project_file` | 写 drawable/circle_bg.xml |
| 8 | `write_project_file` | 写 MainActivity.java（第二版，修正引用） ← **达到上限，终止** |

**关键问题：** 模型共写了 4 次文件仍未调用 `run_build_pipeline`，就被强制终止。

### 1.2 根本原因

1. **`maxIterations = 8` 严重不足**
   一个中等复杂度 App（如番茄时钟）的最小工具调用路径：
   - 1 次 `rename_project`
   - 3 次 `read_project_file`（读现有模板）
   - 3~5 次 `write_project_file`（写多个文件）
   - 1 次 `run_build_pipeline`
   - （如编译失败）2~4 次修复写文件 + 再次构建

   最低需要 **10~15 次**，复杂功能需要更多。

2. **每次迭代只有一次 API 调用**
   当前实现中 `allowParallelToolCalls = false`，Qwen 等模型每轮只返回一个工具调用，每次工具调用消耗一次迭代。

3. **系统提示缺乏约束**
   当前 `defaultAgentInstructions()` 要求"Read before you write"，导致模型对每个文件都先读后写，消耗双倍迭代。模型也不知道自己有迭代次数预算限制。

4. **无软上限预警**
   模型直到最后一刻都不知道即将超限，无法提前收敛（例如跳过读操作、合并写操作、提前触发构建）。

5. **超限后直接报错**
   当前超限时 emit `LoopFailed`，但实际上文件已经写好了，只差一次构建就能成功。

---

## 2. 当前状态机

```
Idle
  │ run(request)
  ▼
LoopStarted
  │
  ▼
┌─────────────────────────────────────────────────┐
│ for iteration in 1..maxIterations               │
│                                                 │
│  ModelTurnStarted                               │
│       │                                         │
│       ▼                                         │
│  streamTurn() ──── AgentModelEvent              │
│       │            ├─ ThinkingDelta             │
│       │            ├─ OutputDelta               │
│       │            ├─ ToolCallReady             │
│       │            ├─ Completed                 │
│       │            └─ Failed ──→ LoopFailed     │
│       │                                         │
│  pendingCalls 为空? ──YES──→ LoopCompleted      │
│       │ NO                                      │
│       ▼                                         │
│  执行所有工具 → ToolExecutionStarted/Finished   │
│       │                                         │
│  更新 fullConversation & conversationDelta      │
└─────────────────────────────────────────────────┘
  │ 超过 maxIterations
  ▼
LoopFailed("Agent loop exceeded max iterations")  ← 当前问题
```

---

## 3. 优化方案

### 3.1 方案一：调整 `maxIterations`（立竿见影）

根据不同任务复杂度，将默认值从 8 调整为 **20**，同时为修复阶段保留独立预算。

```
代码生成阶段：maxIterations = 20
修复循环阶段：maxIterations = 10（每轮 fix 独立计数）
总硬上限：    30（防止无限循环）
```

**预算估算表：**

| 任务类型 | rename | read | write | build | fix轮次 | 合计建议值 |
|---------|--------|------|-------|-------|--------|---------|
| 简单 App（1文件） | 1 | 1 | 1 | 1 | 0~2 | 8~10 |
| 中等 App（3~5文件） | 1 | 2~3 | 3~5 | 1 | 1~3 | 12~18 |
| 复杂 App（含动画/自定义View） | 1 | 3~5 | 5~8 | 1~2 | 2~4 | 18~24 |

### 3.2 方案二：新增 `write_multiple_files` 工具（减少迭代消耗）

当前每次 `write_project_file` 消耗一次迭代。新增批量写入工具，允许模型在单次迭代中写多个文件：

```kotlin
// 工具定义
AgentToolDefinition(
    name = "write_multiple_files",
    description = """
        Write multiple UTF-8 files into the project workspace in a single call.
        Use this when you need to create or update 2 or more files at once.
        Always send the COMPLETE file content for each file.
    """,
    inputSchema = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject {
            put("files", buildJsonObject {
                put("type", JsonPrimitive("array"))
                put("description", JsonPrimitive("List of files to write"))
                put("items", buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("properties", buildJsonObject {
                        put("path", buildJsonObject { put("type", JsonPrimitive("string")) })
                        put("content", buildJsonObject { put("type", JsonPrimitive("string")) })
                    })
                    put("required", JsonArray(listOf(JsonPrimitive("path"), JsonPrimitive("content"))))
                })
            })
        })
        put("required", JsonArray(listOf(JsonPrimitive("files"))))
    }
)
```

**效果：** 将番茄时钟案例的迭代消耗从 8 次（仅写文件就 4 次）压缩为约 4 次（1 rename + 1 批量读 + 1 批量写 + 1 build）。

### 3.3 方案三：优化系统提示（降低不必要迭代）

**当前提示问题：**
- "Read before you write" → 强制读每个文件，即使内容已知
- 没有迭代预算提示
- 没有引导模型分阶段工作

**优化后提示：**

```
You are VibeApp's on-device Android build agent.
Your goal: implement the user's request, build a working APK, and report success.

## Project Files (template defaults you already know)
The template project contains:
- src/main/java/com/vibe/generated/emptyactivity/MainActivity.java  → basic Activity with setContentView
- src/main/res/layout/activity_main.xml                             → ConstraintLayout with a TextView
- src/main/res/values/strings.xml                                   → app_name string only
- src/main/AndroidManifest.xml                                      → single activity, no permissions

Only read a file if you genuinely need its current content (e.g., to understand custom logic).
Skip reading files whose template content is already known.

## Iteration Budget
You have a limited number of tool-call rounds. Use them efficiently:

Phase 1 – Plan & Rename (1 iteration)
  - Call rename_project with the app name.
  - If you need to read files, do it here. Skip reads for files you will fully replace.

Phase 2 – Write All Files (1–2 iterations)
  - Use write_multiple_files to write ALL changed files in one call when possible.
  - Write complete file content, never partial updates.

Phase 3 – Build (1 iteration)
  - Call run_build_pipeline. This is mandatory before finishing.

Phase 4 – Fix Loop (repeat as needed)
  - If build fails, read only the error message from the result.
  - Fix the specific error(s) and write only affected files.
  - Call run_build_pipeline again.
  - Stop when build succeeds.

## Rules
1. When you have N iterations remaining and haven't built yet, call run_build_pipeline immediately.
2. Always send complete file content in write calls — never partial diffs.
3. Java 8 only. No lambdas. No third-party libraries. Android SDK APIs only.
4. Stop only when build succeeds or you have a blocking error to report.
```

### 3.4 方案四：迭代预算感知（软上限预警）

在每次迭代开始前，将剩余预算注入对话，让模型自适应地收敛：

```kotlin
// DefaultAgentLoopCoordinator.kt 中的修改思路
for (iteration in 1..request.policy.maxIterations) {
    val remaining = request.policy.maxIterations - iteration
    val budgetHint = if (remaining <= 3) {
        // 注入软上限提示
        AgentConversationItem(
            role = AgentMessageRole.USER,
            text = "[System] You have $remaining tool-call rounds remaining. " +
                   "If you have not called run_build_pipeline yet, do so now."
        )
    } else null

    if (budgetHint != null) {
        fullConversation += budgetHint
        conversationDelta = listOf(budgetHint)
    }
    // ... 继续正常流程
}
```

### 3.5 方案五：超限自动兜底（最关键的容错机制）

当前超限直接 emit `LoopFailed`。新增兜底逻辑：若文件已被写入但构建未执行，自动触发一次构建并将结果反馈给用户。

```kotlin
// 当前代码（超限后直接失败）：
emit(AgentLoopEvent.LoopFailed(
    message = "Agent loop exceeded max iterations: ${request.policy.maxIterations}",
    iteration = request.policy.maxIterations,
))

// 优化后（超限兜底）：
val buildWasExecuted = collectedToolResults.any { it.toolName == RUN_BUILD_PIPELINE }
val filesWereWritten = collectedToolResults.any {
    it.toolName == WRITE_PROJECT_FILE || it.toolName == WRITE_MULTIPLE_FILES
}

if (!buildWasExecuted && filesWereWritten && request.projectId != null) {
    // 文件已写好但还没构建，自动触发一次构建
    emit(AgentLoopEvent.ThinkingDelta(
        iteration = request.policy.maxIterations,
        delta = "\n[Auto-Recovery] Iteration limit reached. Running build automatically...\n"
    ))
    val autoResult = runCatching {
        agentToolRegistry.findTool(RUN_BUILD_PIPELINE)?.execute(
            call = AgentToolCall(id = "auto_build", name = RUN_BUILD_PIPELINE,
                                 arguments = buildJsonObject {}),
            context = AgentToolContext(
                chatId = request.chatId,
                platformUid = request.platform.uid,
                iteration = request.policy.maxIterations,
                projectId = request.projectId,
            )
        )
    }.getOrNull()

    if (autoResult != null && !autoResult.isError) {
        emit(AgentLoopEvent.LoopCompleted(
            finalText = "Build completed successfully after auto-recovery.",
            toolResults = collectedToolResults + autoResult,
        ))
    } else {
        emit(AgentLoopEvent.LoopFailed(
            message = "Iteration limit reached. Auto-build attempted but failed: ${autoResult?.output}",
            iteration = request.policy.maxIterations,
        ))
    }
} else {
    emit(AgentLoopEvent.LoopFailed(
        message = "Agent loop exceeded max iterations: ${request.policy.maxIterations}",
        iteration = request.policy.maxIterations,
    ))
}
```

### 3.6 方案六：Build 失败后的自动修复机制

当 `run_build_pipeline` 返回失败时，当前需要用户手动重发消息触发修复。优化为：在 `ChatViewModel.runBuild()` 触发的构建失败路径中，自动启动一轮 agent 修复循环，而不是仅将错误丢入聊天框。

**流程：**
```
用户点击 Run 按钮
  → runBuild() 调用 projectInitializer.buildProject()
  → 构建失败
  → [现有方案] 将错误注入聊天框，等用户看到后 AI 自动回复修复
  → [优化方案] 直接触发 AgentLoopCoordinator.run()，并携带构建错误作为 USER 消息
```

这样用户无需手动介入，修复流程完全自动化。

---

## 4. 优化后的状态机

```
Idle
  │ run(request)
  ▼
LoopStarted
  │
  ▼
Phase 1: Rename + Read (预算: 1~3 次)
  │
  ▼
Phase 2: Write All Files (预算: 1~2 次，推荐使用 write_multiple_files)
  │
  ▼
Phase 3: Build
  │ run_build_pipeline
  ├─ SUCCESS → LoopCompleted ✓
  └─ FAILED
       │
       ▼
Phase 4: Fix Loop (每轮 fix 独立计数，上限 fixMaxIterations=10)
  │ for fixIteration in 1..fixMaxIterations
  │   ├─ write affected files
  │   ├─ run_build_pipeline
  │   ├─ SUCCESS → LoopCompleted ✓
  │   └─ FAILED → continue fix loop
  │ 超出 fixMaxIterations → LoopFailed（报告无法修复）
  │
  ▼
软上限预警（remaining ≤ 3）
  │ 注入 budget hint 消息，要求模型立即 build
  │
  ▼
硬上限兜底（超出 maxIterations）
  ├─ 文件已写但未构建 → 自动触发构建 → LoopCompleted 或 LoopFailed
  └─ 其他情况 → LoopFailed
```

---

## 5. 实施优先级

| 优先级 | 方案 | 改动范围 | 预期收益 |
|--------|------|---------|---------|
| P0（立即） | 3.1 调整 maxIterations 至 20 | `AgentModels.kt` 1行 | 消除大部分超限问题 |
| P0（立即） | 3.3 优化系统提示 | `DefaultAgentLoopCoordinator.kt` | 减少不必要迭代 |
| P1（短期） | 3.5 超限自动兜底 | `DefaultAgentLoopCoordinator.kt` | 消除已写文件但未构建导致的失败 |
| P1（短期） | 3.4 迭代预算感知 | `DefaultAgentLoopCoordinator.kt` | 让模型自适应收敛 |
| P2（中期） | 3.2 `write_multiple_files` 工具 | `ProjectTools.kt` + Registry | 大幅减少写文件迭代消耗 |
| P2（中期） | 3.6 Build 失败自动修复 | `ChatViewModel.kt` | 全自动修复，无需用户介入 |

---

## 6. 上下文窗口管理（已实现）

### 6.1 问题

长对话场景下，AI 模型逐渐不再调用 tools，退化为只输出文字描述。根因：

1. **历史消息无截断**：`buildInitialConversation()` 将所有历史消息原封不动塞进 API 请求。10+ 轮对话后，每轮包含完整 Java 源码（`write_project_file` 的内容），上下文膨胀到接近窗口限制。
2. **工具调用记录丢失**：`MessageV2.toAgentConversationItem()` 只取 `content` 文本，丢弃了 `thoughts` 中的 tool call 摘要。模型在 turn 3+ 看不到历史 tool 调用示例，失去 tool calling 的"惯性"。
3. **无 token 预算控制**：没有任何 token 估算或动态裁剪机制。

### 6.2 方案：滑动窗口 + 摘要 + 工具记录保留

#### ConversationContextManager

新增 `ConversationContextManager`（位于 `feature/agent/loop/`），负责将完整对话历史裁剪到 token 预算内：

```text
输入：[Turn1, Turn2, Turn3, ..., Turn10]  （完整历史）
                    ↓
分割为 turns：每个 turn = user消息 + assistant回复 + tool结果
                    ↓
最近 4 轮：保留完整内容（用户原文 + 完整代码 + tool calls）
更早的轮次：压缩为摘要（用户请求 + 使用的工具名 + 结果概要）
                    ↓
Token 估算：若仍超预算，从最早的摘要开始丢弃
                    ↓
输出：[Summary1, Summary2, ..., Turn7_full, Turn8_full, Turn9_full, Turn10_full]
```

**关键参数：**

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `maxContextTokens` | 24,000 | 上下文 token 预算（不含 system prompt 和 tools） |
| `recentTurnsToKeepFull` | 4 | 保留完整内容的最近轮次数 |

**Token 估算策略：** 基于字符数的启发式估算，拉丁字符 ~4 chars/token，CJK 字符 ~2 chars/token。不需要精确，只需防止溢出。

#### 工具调用记录保留

修改 `MessageV2.toAgentConversationItem()`，从 `thoughts` 字段中提取 `[Tool]` 和 `[Tool Result]` 行，追加到 assistant 消息文本中：

```text
原始 assistant content：
  "我已经成功修复了游戏..."

增强后：
  "我已经成功修复了游戏...

  [Tool Usage]
  [Tool] read_project_file
  [Tool Result] read_project_file: ok
  [Tool] write_project_file
  [Tool Result] write_project_file: ok
  [Tool] run_build_pipeline
  [Tool Result] run_build_pipeline: ok"
```

这让模型在后续 turn 中能看到"上一轮我调用了哪些 tool"，保持 tool calling 的行为惯性。

#### 摘要格式

被压缩的早期 turn 变为一条 USER 角色的概要消息：

```text
[Previous Turn Summary]
User: 帮我生成一个贪吃蛇小游戏
Tools used: rename_project, write_project_file, run_build_pipeline
Result: 成功创建了贪吃蛇游戏应用...
```

使用 USER 角色避免构造不完整的 assistant 消息结构（缺少 tool call ID 等），对所有 provider 都安全。

### 6.3 代码位置

| 文件 | 改动 |
|------|------|
| `feature/agent/loop/ConversationContextManager.kt` | 新增：滑动窗口、摘要、token 估算 |
| `feature/agent/loop/DefaultAgentLoopCoordinator.kt` | 修改：`buildInitialConversation()` 调用 `contextManager.trimConversation()`；`toAgentConversationItem()` 从 thoughts 提取 tool 记录 |

### 6.4 设计约束

- **不改变 gateway 层**：裁剪在 coordinator 层完成，Anthropic / OpenAI / Qwen gateway 无感知。
- **不改变数据模型**：`MessageV2`、`AgentConversationItem` 数据类不变。
- **只影响跨 turn 历史**：单次 agent loop 内的 `fullConversation` 累积（iteration 间的 tool 结果回灌）不受影响。
- **渐进降级**：若对话不超过 4 轮，行为与优化前完全一致。

---

## 7. 预期效果

以番茄时钟 App 为例，优化前后对比：

**优化前：**
- maxIterations = 8
- 迭代分布：1 rename + 3 read + 4 write = 8（超限，未构建）
- 结果：LoopFailed，用户看到错误

**优化后（P0）：**
- maxIterations = 20
- 迭代分布：1 rename + 2 read（跳过已知文件）+ 3 write + 1 build + （0~2 fix）= 7~10
- 结果：LoopCompleted，APK 构建成功

**优化后（P0 + P1 + P2）：**
- maxIterations = 20，有 write_multiple_files 工具
- 迭代分布：1 rename + 1 read（仅读未知文件）+ 1 write_multiple（3个文件）+ 1 build + （0~1 fix）= 4~6
- 结果：迭代消耗降低 40~50%，大幅提升复杂 App 的成功率
