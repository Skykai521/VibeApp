# Function Calling 与 Agent Loop 设计

## 目标

这份文档基于当前代码结构，定义 VibeApp 下一阶段的对话能力：

- 在聊天过程中支持模型主动发起 function calling
- 在单次用户提问内支持 agent loop
- 将工具执行、构建、错误读取等能力收敛成统一工具层
- 保持现有 `ChatViewModel -> ChatRepository -> provider API` 主链路可渐进演进

当前阶段先定义架构和接口，不直接实现完整运行时。

## 现状 Review

### 现有优点

- `ChatViewModel` 已经把聊天 UI 状态和消息持久化分开，适合插入新的编排层。
- `ChatRepository` 已经集中承接不同 provider 的请求构造和流式响应解析。
- `MessageV2` 已经有 `thoughts`、`content`、`files` 字段，至少可以承接最终回复和思考内容。
- `build-engine` 已经能真实编译，说明工具执行链路具备落地价值。

### 当前不适合直接承载 agent loop 的点

#### 1. 流式事件模型过于扁平

`ApiState` 只有：

- `Loading`
- `Thinking`
- `Success`
- `Error`
- `Done`

这只能表达“模型吐文本”，无法表达：

- tool schema 下发
- tool call 参数增量
- tool call 完整结束
- tool 执行开始 / 结束
- loop 第 N 轮
- 模型要求继续推理

如果继续复用 `ApiState`，后面会把工具调用硬塞进 `Success/Error` 字符串里，UI 和持久化都会失真。

#### 2. `ChatRepository.completeChat()` 本质是单轮 request

当前接口：

```kotlin
suspend fun completeChat(
    userMessages: List<MessageV2>,
    assistantMessages: List<List<MessageV2>>,
    platform: PlatformV2
): Flow<ApiState>
```

它默认流程是：

1. 组装历史消息
2. 调一次 provider
3. 流式接收文本
4. 结束

这与 agent loop 的真实流程不匹配。agent loop 需要：

1. 模型先输出 tool call
2. 客户端执行工具
3. 将 tool result 回灌模型
4. 继续下一轮，直到模型明确完成

所以单轮 provider 调用应该下沉为“model turn”，而不是继续把整个 agent loop 塞进 `ChatRepository.completeChat()`。

#### 3. `ChatViewModel` 当前直接并发扇出到多个平台

当前 `completeChat()` 会对 `enabledPlatformsInChat` 全部并发请求。

这对“文本对比模式”是合理的，但对 agent loop 有两个问题：

- 不同平台会触发不同 tool call，执行副作用无法共享
- 工具可能涉及文件写入、构建、安装，多个平台并发执行会互相污染工作目录

结论：

- Phase 1 的 agent mode 应限制为单平台会话
- 多平台对比模式先保留为“纯聊天模式”

#### 4. Provider DTO 还没有 tool calling 字段

当前 request/response DTO 只覆盖文本和 reasoning：

- OpenAI Responses
- OpenAI Chat Completions
- Anthropic Messages
- Google GenerateContent

还缺少：

- tool / function schema
- tool choice 策略
- tool use / tool result 消息结构
- provider 侧统一事件归一化

#### 5. 持久化模型还没有 agent trace

`MessageV2` 当前适合保存最终用户消息和最终助手回复，但不适合完整保存：

- tool call 参数
- tool result
- loop 中间态
- provider 原始事件

不建议把这些结构化数据直接拼到 `content` 或 `revisions` 字符串里。

## 推荐架构

### 总体分层

建议新增一层 agent orchestration，放在 `ViewModel` 和 `ChatRepository` 之间：

```text
ChatViewModel
    ->
AgentLoopCoordinator
    ->
AgentModelGateway
    ->
Provider-specific API

AgentLoopCoordinator
    ->
AgentToolRegistry
    ->
AgentTool
```

职责划分：

- `ChatViewModel`
  - 管理页面状态
  - 发起“用户提问”
  - 展示 agent 事件流
- `AgentLoopCoordinator`
  - 驱动单次 agent loop
  - 决定何时调模型、何时执行工具、何时结束
- `AgentModelGateway`
  - 把 provider 差异收敛成统一的模型事件
- `AgentToolRegistry`
  - 管理可调用工具及其 schema
- `AgentTool`
  - 真正执行文件、构建、读取日志等动作

### 状态机

单次 agent loop 建议采用如下状态机：

```text
Idle
  -> StartTurn
  -> ModelStreaming
      -> TextDelta
      -> ThinkingDelta
      -> ToolCallDelta / ToolCallReady
  -> ExecuteTool
  -> AppendToolResult
  -> NextModelTurn
  -> Completed
  -> Failed
```

约束：

- 每轮只允许在一个平台内串行执行工具
- loop 必须带最大轮次 `maxIterations`
- 任意工具失败后可以：
  - 直接结束
  - 或把错误作为 tool result 回灌模型，再由模型决定是否恢复

## 推荐数据流

### 1. 用户发起问题

`ChatViewModel` 仍然负责创建用户消息，但不直接调用 `ChatRepository.completeChat()`，而是改成：

1. 构造 `AgentLoopRequest`
2. 选择单个平台
3. 订阅 `Flow<AgentLoopEvent>`
4. 将事件映射到 UI

### 2. 模型回合

`AgentLoopCoordinator` 每一轮会向 `AgentModelGateway` 发送：

- 当前历史消息
- system prompt
- 已注册 tool schema
- loop policy

`AgentModelGateway` 只负责：

- provider request 构造
- SSE / chunk 解析
- 归一化成统一的 `AgentModelEvent`

它不负责真正执行工具。

### 3. 工具执行

当模型发出完整 tool call 后：

1. `AgentLoopCoordinator` 查找 `AgentToolRegistry`
2. 找到同名 `AgentTool`
3. 执行工具
4. 产出结构化 `AgentToolResult`
5. 将结果追加回会话历史
6. 进入下一轮模型回合

### 4. 结束条件

满足任一条件则结束：

- 模型输出最终文本且没有未完成 tool call
- 达到 `maxIterations`
- 用户取消
- provider 或 tool 返回不可恢复错误

## 推荐工具集

第一批工具建议只开放只读和低风险写操作：

- `read_project_file`
- `list_project_files`
- `write_project_file`
- `apply_template_files`
- `run_build_pipeline`
- `read_build_logs`

其中 `run_build_pipeline` 应直接复用现有 `build-engine`，不要在 agent 层再造编译逻辑。

## Provider 适配原则

### OpenAI

优先接 `Responses API`：

- 能力上最接近 reasoning + tool calling + structured continuation
- 当前仓库已经有 `ResponsesRequest` 和 `ResponsesStreamEvent`

### Anthropic

接 `messages` + tool use / tool result 块。

### Google

Google 工具调用支持形式与 OpenAI / Anthropic 不同，建议放到第二阶段。

### OpenAI-compatible 平台

像 Groq / OpenRouter / Ollama / Custom：

- 不保证都支持统一的 function calling
- Phase 1 只支持“无工具纯文本”
- 或仅在 capability 声明存在时启用 agent mode

## 持久化建议

### Phase 1

先不急着改数据库主表，只做：

- UI 内存态保存完整 `AgentLoopEvent`
- 最终仍落库到现有 `MessageV2`
- 把最终助手文本和思考摘要存回 `MessageV2`

这样能最快把 agent loop 跑通。

### Phase 2

新增独立 trace 表，例如：

- `agent_runs`
- `agent_events`
- `tool_executions`

不要继续滥用 `MessageV2.revisions`。

## 与现有代码的接入建议

### 保留不动的层

- `ChatScreen`
- `MessageV2` 作为最终消息载体
- `build-engine`

### 需要新增的层

- `feature/agent/loop`
- `feature/agent/tool`
- `feature/agent/provider`

### 需要逐步瘦身的层

`ChatRepositoryImpl` 当前承担了三种职责：

- provider request 构造
- provider stream 解析
- 聊天数据仓储

后面应该拆成：

- `ChatRepository`: 只做聊天持久化
- `AgentModelGateway`: 只做模型交互
- `AgentLoopCoordinator`: 只做编排

## Phase 1 实施顺序

建议顺序如下：

1. 先引入统一事件模型和 agent loop 接口
2. 只接 OpenAI Responses 的 function calling
3. 只开放单平台 agent mode
4. 只开放 2-3 个安全工具
5. 跑通“模型生成 -> 写文件 -> 调 build -> 读日志 -> 再修复”的闭环
6. 再考虑 Anthropic / Google / 多平台

## 本次产出对应范围

本次只落两类内容：

- 设计文档
- 最小接口定义

不包含：

- 数据库迁移
- ViewModel 改造
- provider tool calling 实现
- 实际 tool 执行器实现

## Phase 1 已实现范围

当前代码已经先落了第一版可运行闭环，范围刻意收敛：

- 只在 `单平台` 聊天时启用 agent mode
- 支持 OpenAI Responses API 和 Anthropic Messages API
- 只开放 3 个工具：
  - `read_project_file`
  - `write_project_file`
  - `run_build_pipeline`
- loop 为串行工具执行，不支持并行 tool calls
- 项目工作区复用 `templates/EmptyActivity/app`

当前代码路径：

- agent 入口模型与接口：
  - `app/src/main/kotlin/com/vibe/app/feature/agent/`
- provider 路由器（按 platform.compatibleType 分派）：
  - `app/src/main/kotlin/com/vibe/app/feature/agent/loop/ProviderAgentGatewayRouter.kt`
- OpenAI gateway：
  - `app/src/main/kotlin/com/vibe/app/feature/agent/loop/OpenAiResponsesAgentGateway.kt`
- Anthropic gateway：
  - `app/src/main/kotlin/com/vibe/app/feature/agent/loop/AnthropicMessagesAgentGateway.kt`
- loop coordinator：
  - `app/src/main/kotlin/com/vibe/app/feature/agent/loop/DefaultAgentLoopCoordinator.kt`
- 工具实现：
  - `app/src/main/kotlin/com/vibe/app/feature/agent/tool/ProjectTools.kt`
- 项目工作区服务：
  - `app/src/main/kotlin/com/vibe/app/feature/projectinit/ProjectWorkspaceService.kt`

### 当前行为约束

- 多平台聊天仍走旧的并行文本问答逻辑
- Google / OpenAI-compatible provider 仍未接入 tool calling（走无工具纯文本）
- `run_build_pipeline` 直接返回结构化构建结果与日志，不再额外提供 `read_build_logs`
- agent trace 当前只保留在内存态与最终助手消息中，还未做独立数据库持久化

### 上下文窗口管理

长对话场景下，`ConversationContextManager` 负责将跨 turn 的历史消息裁剪到 token 预算内：

- **滑动窗口**：保留最近 4 轮完整内容，更早的轮次压缩为摘要（用户请求 + 工具名 + 结果概要）
- **Token 估算**：基于字符数启发式（拉丁 ~4 chars/token，CJK ~2 chars/token），预算 24K tokens
- **工具记录保留**：从 `MessageV2.thoughts` 提取 `[Tool]` / `[Tool Result]` 行追加到历史 assistant 消息，让模型在后续 turn 看到工具使用惯例
- **渐进降级**：超预算时优先丢弃最早的摘要；不超过 4 轮时行为与无管理时完全一致

### AgentModelRequest 对话字段语义

`AgentModelRequest` 包含两个对话字段，语义不同：

| 字段 | 含义 | 使用方 |
|---|---|---|
| `conversation` | 本轮新增的 delta（工具结果等） | OpenAI gateway（配合 `previousResponseId`）|
| `fullConversation` | 从对话开始至今的完整历史 | Anthropic gateway（无服务端会话状态）|

coordinator 每轮都同步维护两者：
- `conversation` = 本轮新增 tool result items
- `fullConversation` = 累积 initial + assistant turns + tool results

### Anthropic tool calling 数据流

Anthropic SSE 流中，tool call 通过 content block 传递：

```
content_block_start  { type: "tool_use", id: "...", name: "..." }
content_block_delta  { type: "input_json_delta", partial_json: "..." }  ← 可能多个
content_block_stop
message_delta        { stop_reason: "tool_use" }
message_stop
```

gateway 按 block index 累积 `partial_json`，在 `content_block_stop` 时组装完整参数并 emit `ToolCallReady`。

多轮对话中，Anthropic 要求工具结果回灌格式：
- assistant 消息：包含 `tool_use` content blocks（model 的调用意图）
- user 消息：包含 `tool_result` content blocks（一轮所有工具结果合并到同一条消息）
