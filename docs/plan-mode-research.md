# Plan Mode 调研文档：Superpowers 原理分析与 VibeApp 集成方案

> 日期：2026-04-04

## 1. Superpowers 项目概述

[obra/superpowers](https://github.com/obra/superpowers) 是一个面向 AI 编程助手的**可组合技能（Skills）库**，以 Claude Code 插件形式运行。核心理念是通过一系列结构化的工作流技能，让 AI 在执行复杂任务前先进行充分的思考、规划和验证。

### 1.1 项目架构

```
superpowers/
├── .claude-plugin/       # Claude Code 插件注册
│   ├── plugin.json       # 插件元数据
│   └── marketplace.json  # 市场定义
├── hooks/
│   ├── hooks.json        # SessionStart hook 配置
│   └── session-start     # 启动时注入 using-superpowers 上下文
├── skills/               # 14 个技能目录，每个包含 SKILL.md
│   ├── brainstorming/
│   ├── writing-plans/
│   ├── executing-plans/
│   ├── subagent-driven-development/
│   ├── test-driven-development/
│   ├── systematic-debugging/
│   ├── verification-before-completion/
│   └── ...
├── agents/
│   └── code-reviewer.md  # 代码审查子代理提示词
└── docs/superpowers/
    ├── plans/            # 生成的计划文档存储
    └── specs/            # 生成的规格文档存储
```

### 1.2 集成机制

Superpowers 并非 MCP Server，而是一个 **Claude Code 插件**，通过三层机制集成：

1. **插件注册**：`.claude-plugin/plugin.json` 声明插件身份
2. **SessionStart Hook**：每次会话启动时，自动注入 `using-superpowers` 技能内容到上下文，建立"遇到任务先查技能"的行为模式
3. **Skill 工具**：每个 `skills/<name>/SKILL.md` 通过 Claude Code 的 Skill 工具按需加载

### 1.3 技能格式

每个技能是一个 Markdown 文件，包含 YAML frontmatter 和指令正文：

```markdown
---
name: writing-plans
description: Use when you have a spec or requirements for a multi-step task, before touching code
---

（详细的工作流指令...）
```

**关键设计**：description 字段只包含触发条件（"Use when..."），不包含工作流摘要，因为 AI 会倾向于只读取摘要而跳过完整指令。

## 2. Plan Mode 工作原理

Superpowers 的 Plan Mode 并非一个单一功能，而是一条**技能链**（Skill Chain），由多个技能串联执行：

### 2.1 完整流程

```
用户请求 → 头脑风暴(Brainstorming) → 规格文档(Spec) → 编写计划(Writing Plans)
         → 计划审查 → 执行计划(Executing Plans) → 验证 → 完成
```

### 2.2 各阶段详解

#### 阶段 1：头脑风暴（Brainstorming）

- **触发条件**：任何涉及"创造性工作"的任务——创建功能、构建组件、修改行为
- **流程**：
  1. 探索项目上下文
  2. 逐个提出澄清问题（一次只问一个）
  3. 提出 2-3 个方案及其权衡
  4. 以可读方式呈现设计供用户审批
  5. 用户批准后，将设计保存为 **Spec 文档**到 `docs/superpowers/specs/`
  6. 派遣 **Spec 审查子代理**验证完整性和一致性
- **核心约束**："用户批准设计之前，不得调用任何实现技能"

#### 阶段 2：编写计划（Writing Plans）

这是 Plan Mode 的核心。计划的编写假设**执行者对代码库零了解且判断力有限**，因此要求：

- **任务粒度**：每个任务耗时 2-5 分钟
- **TDD 流程**：每个任务遵循红-绿-重构周期
- **完整代码块**：不允许占位符（"TODO"、"类似任务 N"）
- **精确文件路径**：每个待创建/修改的文件都有明确路径
- **精确验证命令**：每个步骤都有可运行的验证命令及期望输出
- **类型/方法名一致性**：跨任务引用必须保持名称一致

计划保存到 `docs/superpowers/plans/` 后，派遣**计划审查子代理**检查：

| 类别 | 检查内容 |
|------|---------|
| 完整性 | TODO/占位符/遗漏步骤 |
| Spec 对齐 | 计划覆盖所有规格需求 |
| 任务分解 | 任务边界清晰，步骤可执行 |
| 可构建性 | 工程师能否不卡壳地跟着做 |

#### 阶段 3：执行计划

有两种执行模式：

**模式 A — 子代理驱动开发（Subagent-Driven）**：
- 适合：任务间独立、当前会话内执行
- 每个任务独立派遣实现子代理
- 每个任务完成后进行**双重审查**：规格符合性审查 + 代码质量审查
- 两个审查都通过后才标记任务完成

**模式 B — 顺序执行（Executing Plans）**：
- 适合：任务有依赖关系、跨会话执行
- 顺序执行每个任务，标记进度
- 遇到阻塞（依赖缺失、测试失败、指令不清）立即停止而非猜测
- 完成所有任务后进入分支完成流程

### 2.3 Plan Mode 的触发机制

Superpowers **没有自动检测机制**来决定是否进入 Plan Mode。它依赖的是：

1. `using-superpowers` 技能在会话启动时注入，要求"任何行动前先检查相关技能"
2. `brainstorming` 技能的 description 声明"创造性工作前必须使用"
3. `writing-plans` 技能的 description 声明"多步骤任务前必须使用"
4. AI 根据用户请求的性质匹配到对应技能，链式触发后续技能

本质上是**基于提示词工程的行为控制**，而非代码层面的模式切换。

## 3. VibeApp 当前架构分析

### 3.1 Agent Loop 现状

VibeApp 的 Agent Loop 是一个**迭代式工具调用循环**（最多 30 次迭代）：

```
用户消息 → [迭代 1: 强制工具调用] → [迭代 2..N: 自动决策] → 文本响应/耗尽迭代
```

**核心文件**：
- `DefaultAgentLoopCoordinator.kt` — 循环协调器
- `AgentModels.kt` — 数据模型
- `ProviderAgentGatewayRouter.kt` — 提供商路由
- `agent-system-prompt.md` — 系统提示词

**迭代流程**：
1. 上下文压缩（三级策略：工具结果裁剪 → 结构化摘要 → 模型摘要）
2. 第一次迭代强制工具调用（`toolChoiceMode = REQUIRED`）
3. 流式接收模型事件（Thinking/Output/ToolCall/Completed/Failed）
4. 执行工具调用，收集结果
5. 将结果追加到对话历史，进入下一次迭代
6. 无工具调用时循环结束，输出最终文本

### 3.2 现有"思考"能力

- **reasoningContent**：`AgentConversationItem` 已有 `reasoningContent` 字段
- **Kimi 支持**：Kimi 网关捕获 `choice.delta.reasoningContent` 并在 `Completed` 事件中返回
- **OpenAI 支持**：Responses API 的 `reasoning` 配置已接入
- **Anthropic 支持**：`thinking` 配置已接入（extended thinking）
- **UI 展示**：`AgentStepItem.THINKING` 类型已实现，ThinkingBlock 组件可展开/折叠显示

### 3.3 当前限制

| 限制 | 说明 |
|------|------|
| 无显式规划阶段 | 模型隐式通过迭代决定下一步，无专门的"先规划再执行"逻辑 |
| 无计划存储 | 没有持久化的计划对象，无法跨迭代追踪完成度 |
| 无用户确认检查点 | 模型生成计划后不等待用户确认就开始执行 |
| 无任务分解机制 | 没有将复杂请求分解为子步骤的工具或数据结构 |
| 推理内容未充分利用 | 推理内容在压缩时被丢弃，未用于指导后续行为 |

## 4. 三方 API 对 Plan Mode 的支持能力

### 4.1 各提供商能力矩阵

| 能力 | Anthropic | OpenAI | Qwen | Kimi | MiniMax |
|------|-----------|--------|------|------|---------|
| Tool Calling | Yes | Yes | Yes | Yes | Yes |
| Extended Thinking | Yes (thinking) | Yes (reasoning) | No | Yes (reasoningContent) | No |
| Streaming | Yes | Yes | Yes | Yes | Yes |
| Stateful 对话 | No (全量重建) | Yes (previousResponseId) | No | No | No |
| 长上下文 | 200K | 128K+ | 128K | 128K+ | 1M |
| 结构化输出 | Yes | Yes | Partial | Partial | Partial |

### 4.2 Plan Mode 的 API 需求分析

Plan Mode 本质上是一种**提示词驱动的行为模式**，不需要特殊的 API 能力。核心需求是：

1. **Tool Calling**：所有提供商都支持 ✅
2. **足够的上下文窗口**：计划文本 + 代码 + 对话历史需要较大窗口。所有提供商 ≥ 128K ✅
3. **遵循复杂指令的能力**：这是关键差异化因素

### 4.3 各提供商的 Plan Mode 适配评估

| 提供商 | 适配程度 | 说明 |
|--------|---------|------|
| **Anthropic (Claude)** | 极佳 | 强指令遵循、原生支持 extended thinking、Superpowers 就是为它设计的 |
| **OpenAI (GPT-4/o3)** | 极佳 | 强指令遵循、reasoning 支持、结构化输出能力强 |
| **Kimi (k2.5)** | 良好 | reasoningContent 已对接、指令遵循能力较好 |
| **Qwen** | 一般 | 无 extended thinking、复杂指令遵循能力不如 Claude/GPT-4 |
| **MiniMax** | 一般 | 无 extended thinking、复杂计划可能偏离 |

**结论**：所有提供商都可以支持 Plan Mode，因为它本质是提示词工程。但效果最佳的是 Anthropic 和 OpenAI，因为它们的指令遵循能力最强，且支持 extended thinking 来展示思考过程。

## 5. VibeApp 集成 Plan Mode 的方案设计

### 5.1 方案对比

| 方案 | 描述 | 复杂度 | 效果 |
|------|------|--------|------|
| **A. 纯提示词方案** | 在 system prompt 中添加规划指令 | 低 | 中 |
| **B. 规划工具方案** | 新增 `create_plan` / `update_plan` 工具 | 中 | 高 |
| **C. 显式阶段方案** | 在 Agent Loop 中增加独立的规划阶段 | 高 | 最佳 |

### 5.2 推荐方案：B + A 混合（规划工具 + 提示词增强）

结合提示词引导和工具化的计划管理，在不大幅改动 Agent Loop 核心的前提下实现 Plan Mode。

#### 5.2.1 新增数据模型

```kotlin
// AgentModels.kt 中新增

data class AgentPlanStep(
    val id: Int,
    val description: String,
    val status: PlanStepStatus = PlanStepStatus.PENDING,
)

enum class PlanStepStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
}

data class AgentPlan(
    val summary: String,
    val steps: List<AgentPlanStep>,
    val createdAtIteration: Int,
)
```

#### 5.2.2 新增规划工具

在 `ProjectTools.kt` 的工具注册中新增两个工具：

**`create_plan`** — 创建任务执行计划
```json
{
  "name": "create_plan",
  "description": "Create a structured execution plan before starting complex tasks. Use this when the user's request involves multiple files, multiple steps, or complex logic.",
  "parameters": {
    "summary": "Brief description of the overall goal",
    "steps": [
      { "description": "Step description with specific actions" }
    ]
  }
}
```

**`update_plan_step`** — 更新计划步骤状态
```json
{
  "name": "update_plan_step",
  "description": "Update the status of a plan step after attempting it.",
  "parameters": {
    "step_id": 1,
    "status": "completed|failed",
    "notes": "Optional notes about the result"
  }
}
```

#### 5.2.3 系统提示词增强

在 `agent-system-prompt.md` 中添加规划指令：

```markdown
## Task Planning

For complex tasks (multiple files, multi-step logic, or significant changes), you MUST:

1. **First**, call `create_plan` to outline your approach before writing any code
2. **Then**, execute each step sequentially, calling `update_plan_step` as you complete each one
3. **If a step fails**, update its status to "failed" with notes, then reassess

A task is "complex" if it involves:
- Creating or modifying 3+ files
- Implementing a feature with multiple interacting components
- Fixing a bug that requires understanding multiple code paths
- Any request that could be broken into discrete sub-tasks

For simple tasks (single file edits, minor fixes), proceed directly without a plan.
```

#### 5.2.4 Agent Loop 修改

在 `DefaultAgentLoopCoordinator` 中添加计划状态追踪：

```kotlin
// 在 run() 方法中维护一个 plan 变量
var currentPlan: AgentPlan? = null

// 在工具执行时，特殊处理 create_plan 和 update_plan_step
when (call.name) {
    "create_plan" -> {
        currentPlan = parsePlan(call.arguments, iteration)
        emit(AgentLoopEvent.PlanCreated(currentPlan!!))
        // 返回成功结果，包含格式化的计划
    }
    "update_plan_step" -> {
        currentPlan = updatePlanStep(currentPlan, call.arguments)
        emit(AgentLoopEvent.PlanUpdated(currentPlan!!))
        // 返回当前计划状态摘要
    }
}
```

新增事件类型：

```kotlin
// AgentModels.kt 的 AgentLoopEvent 中新增
data class PlanCreated(val plan: AgentPlan) : AgentLoopEvent
data class PlanUpdated(val plan: AgentPlan) : AgentLoopEvent
```

#### 5.2.5 UI 展示

新增 `AgentStepType.PLAN`，在聊天界面展示计划进度：

```
📋 执行计划 (2/5 完成)
  ✅ 1. 创建 LoginActivity 布局文件
  ✅ 2. 实现 LoginActivity Java 代码
  🔄 3. 添加网络请求逻辑
  ⬜ 4. 创建注册页面
  ⬜ 5. 运行构建并修复错误
```

#### 5.2.6 计划感知的上下文管理

在 `buildInstructions()` 中，如果存在活跃计划，自动注入计划状态：

```kotlin
if (currentPlan != null) {
    append("\n\n[Active Plan]\n")
    append("Goal: ${currentPlan.summary}\n")
    currentPlan.steps.forEachIndexed { i, step ->
        val icon = when (step.status) {
            COMPLETED -> "✅"
            IN_PROGRESS -> "🔄"
            FAILED -> "❌"
            PENDING -> "⬜"
        }
        append("$icon ${i+1}. ${step.description}\n")
    }
    append("\nContinue with the next pending step.\n")
}
```

### 5.3 需要修改的文件清单

| 文件 | 修改内容 |
|------|---------|
| `feature/agent/AgentModels.kt` | 新增 `AgentPlan`、`AgentPlanStep`、`PlanStepStatus` 数据类；新增 `PlanCreated`/`PlanUpdated` 事件；新增 `AgentStepType.PLAN` |
| `feature/agent/loop/DefaultAgentLoopCoordinator.kt` | 维护 `currentPlan` 状态；在 `buildInstructions()` 中注入活跃计划；特殊处理规划工具调用 |
| `feature/agent/tool/ProjectTools.kt` | 注册 `create_plan` 和 `update_plan_step` 工具；实现工具执行逻辑 |
| `app/src/main/assets/agent-system-prompt.md` | 添加 Task Planning 规划指令段 |
| `presentation/ui/chat/AgentStepBubble.kt` | 新增 PLAN 类型的步骤展示组件 |
| `presentation/ui/chat/ChatViewModel.kt` | 处理 `PlanCreated`/`PlanUpdated` 事件，更新 UI 状态 |
| `feature/agent/loop/AgentSessionManager.kt` | 在会话状态中追踪计划；持久化计划到数据库（可选） |

### 5.4 渐进式实施路径

**Phase 1（最小可行）**：仅修改 system prompt，添加规划指令段。无代码改动，效果依赖模型能力。

**Phase 2（工具化）**：新增 `create_plan` / `update_plan_step` 工具，在 Agent Loop 中追踪计划状态。

**Phase 3（UI 展示）**：新增计划进度展示组件，让用户看到结构化的执行进度。

**Phase 4（用户交互）**：支持用户在计划创建后确认/修改再执行（需要 UI 中断 + 恢复机制）。

### 5.5 自动测试与 Plan Mode 的集成（Phase 2 待实施）

> 前置依赖：`launch_app` 工具已在 Phase 1 中实现（2026-04-05），Agent 已具备启动应用和 UI 检查的能力。

#### 5.5.1 目标

让 `create_plan` 工具在生成计划时**自动决定是否需要测试步骤**，并将测试作为计划步骤纳入执行流程。

#### 5.5.2 自动决策规则

在 `create_plan` 的 system prompt 指令中定义触发规则：

| 场景 | 是否生成测试步骤 | 测试范围 |
|------|----------------|---------|
| 简单文本/颜色修改 | ❌ | — |
| 新增 UI 布局 | ✅ | `launch_app` + `inspect_ui` 验证 View 树结构 |
| 涉及网络请求 | ✅ | `launch_app` + `read_runtime_log` 检查网络错误 |
| 涉及用户交互（按钮/输入/列表） | ✅ | `interact_ui` 模拟点击/输入/滚动 |
| 用户报告 bug/崩溃 | ✅ | 完整测试：启动 + 操作 + 日志验证 |
| 修复编译错误 | ❌ | — |

#### 5.5.3 计划中的测试步骤示例

```
📋 执行计划 (3/5 完成)
  ✅ 1. 创建新闻列表布局 (activity_main.xml + item_news.xml)
  ✅ 2. 实现 MainActivity 数据加载和 RecyclerView 绑定
  ✅ 3. 构建 APK
  🔄 4. 启动应用并验证列表正常显示    ← launch_app + inspect_ui
  ⬜ 5. 验证下拉加载和列表滚动        ← interact_ui scroll + inspect_ui
```

#### 5.5.4 实现要点

1. **`create_plan` 工具逻辑**：在解析用户请求后，根据上述规则自动追加测试步骤到计划末尾
2. **`update_plan_step` 更新**：测试步骤的完成条件是 `inspect_ui` 或 `interact_ui` 返回的结果中无异常
3. **上下文管理**：测试步骤产生的 View tree 数据较大，在 `ToolResultTrimStrategy` 中应优先裁剪已完成测试步骤的 view_tree payload
4. **迭代预算**：如果剩余迭代 ≤ 5 且计划中有未执行的测试步骤，自动将其标记为 skipped 并在 wind-down 中说明

#### 5.5.5 需额外修改的文件

| 文件 | 修改内容 |
|------|---------|
| `feature/agent/tool/PlanTools.kt`（新建） | `create_plan` 中集成测试步骤生成逻辑 |
| `agent-system-prompt.md` | 在 Task Planning 段落中增加"测试步骤自动规划"的指令和规则 |
| `feature/agent/loop/compaction/ToolResultTrimStrategy.kt` | 对 `launch_app` 和 `inspect_ui` 的结果应用与 `run_build_pipeline` 相同的裁剪策略 |

## 6. 关键技术风险

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| 弱模型不遵循规划指令 | 跳过 create_plan 直接写代码 | 第一次迭代已强制工具调用，可在工具选择策略中优先 create_plan |
| 计划过于宽泛无用 | 生成"第一步：写代码"这种无意义计划 | 在 system prompt 中给出具体的好/坏计划示例 |
| 增加 token 消耗 | 计划内容占用上下文窗口 | 计划完成后在压缩策略中裁剪已完成步骤 |
| 简单任务产生不必要的计划 | 用户体验下降 | 在 prompt 中明确"简单任务直接执行" |

## 7. 总结

| 维度 | 结论 |
|------|------|
| Superpowers 原理 | 基于提示词工程的技能链系统，通过 Claude Code 插件形式运行，非 MCP Server |
| Plan Mode 本质 | 技能链式触发（brainstorming → spec → plan → execute），核心是结构化的提示词 |
| 三方 API 支持 | 所有已接入的提供商均可支持，Anthropic 和 OpenAI 效果最佳 |
| 推荐方案 | 提示词增强 + 规划工具（create_plan/update_plan_step），渐进式实施 |
| 最小改动起步 | 仅修改 agent-system-prompt.md 即可获得基础效果 |
