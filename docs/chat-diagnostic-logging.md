# 聊天诊断日志设计

本文档描述 VibeApp 聊天内诊断日志能力的设计目标、事件模型、存储策略和实施计划。目标不是替代现有 Markdown 聊天导出，而是在出现网络异常、模型异常、构建失败或用户反馈“这次对话为什么坏了”时，提供一套可控、低开销、可导出的排障日志。

## 当前实现进度

截至当前代码实现，首版日志系统已经进入“可用”状态，下面是与 `4E. 首版实现规约` 对齐的落地结果。

### 已完成

1. 已新增诊断日志基础设施：
   - 新增 `DiagnosticEvent` / `DiagnosticIndex` / `ModelExecutionTrace`
   - 新增 `ChatDiagnosticLogger`
   - 存储位置已按首版规约落在 `files/logs/chats/{chatId}/current.ndjson` 和 `index.json`
   - 存储层已预留 `rollIfNeeded()` 与 `pruneChatLogsIfNeeded()` 扩展点

2. 已落地首版要求的 6 类事件：
   - `CHAT_TURN`
   - `MODEL_REQUEST`
   - `MODEL_RESPONSE`
   - `LATENCY_BREAKDOWN`
   - `BUILD_RESULT`
   - `AGENT_TOOL`

3. 已完成首版主要接入点：
   - `ChatViewModel.askQuestion()` / 编辑重试 / 停止响应：生成并传递 `turnId`
   - `ChatRepositoryImpl`：普通聊天链路汇总模型输出、thinking、失败与耗时
   - `OpenAIAPIImpl` / `AnthropicAPIImpl` / `GoogleAPIImpl`：请求起点、首包与响应摘要
   - `DefaultAgentLoopCoordinator`：tool 开始与结束事件
   - `ProjectInitializer`：构建结果、阶段耗时摘要与触发来源

4. 已完成导出集成：
   - 聊天导出已从单独 `md` 文件升级为 zip
   - zip 中会带上：
     - `chat.md`
     - `diagnostic-log.ndjson`（若存在）
     - `manifest.json`

5. 已完成首版性能与安全约束的基本落实：
   - 日志写入走 IO 线程，不在主线程直写
   - provider 流式过程中不按 chunk 单独落盘，而是聚合后写摘要事件
   - 请求与响应仅记录摘要，不落完整 body
   - 不记录 token 明文与图片 base64

### 当前实现边界

当前实现仍然有意停留在首版范围内，以下能力尚未进入代码：

1. `AGENT_TOOL_EXPECTATION`
2. `MODEL_STREAM`
3. `BUILD_STAGE`
4. `MODEL_CONFIG_SNAPSHOT`
5. `TOOL_SCHEMA_METRICS`
6. `CONTEXT_TRIM`
7. `TOOL_OPTIMIZATION_HINT`
8. 多文件滚动归档与 `.gz` 压缩
9. 日志查看 UI

### 当前实现说明

1. 新建 chat 的首轮请求在 chat 尚未持久化时，会先写入临时 chat 日志目录；chat 真正保存后，会迁移到最终 chatId 对应目录。
2. `BUILD_RESULT` 已统一放在 `ProjectInitializer` 记录，因此聊天按钮触发和 agent tool 触发的构建都会进入同一日志模型。
3. 首版导出仍然以“排障文件包”为目标，不提供日志筛选 UI。

## 1. 背景与目标

当前仓库已经具备几类分散的排障信息：

- 网络层有 `NetworkLogcatLogger`，但只输出到 Logcat，不持久化，也不和具体 chat 关联。
- 构建链返回 `BuildResult.logs`，UI 只消费“是否成功”和部分错误摘要。
- 聊天页持久化了用户/模型消息，但没有保留“这条回复对应的模型配置、网络往返、构建结果、耗时”等诊断上下文。

这导致两个问题：

1. 用户反馈问题后，历史上下文不够，难以复现。
2. 现有日志要么过于原始（Logcat），要么过于稀疏（仅聊天消息），缺少一套面向单次 chat 排障的中间层。

当前还存在三类高频问题，需要在诊断日志设计里专门考虑：

1. 多轮对话后，模型开始“偷懒”，不再调用 tools，只返回文字。
2. 有时模型长时间不回复，难以判断是网络传输慢、provider 处理慢，还是本地流程卡住。
3. 后续需要持续优化 tools 和 agent loop 设计，因此日志不仅要“排错”，还要支持“调优”。

本设计目标：

1. 为每个 chat 提供可持久化、可导出、可裁剪的诊断日志。
2. 覆盖网络请求、模型调用、构建结果三条主链路。
3. 严格控制性能、文件体积和敏感信息泄露风险。
4. 与现有聊天导出、网络 client、构建链路兼容，尽量复用已存在的观测点。
5. 能定位“为什么没调 tool”和“到底慢在哪里”。
6. 为 future tool / prompt / agent loop 优化提供稳定指标。

非目标：

1. 不做通用埋点/统计分析平台。
2. 不记录完整 prompt、完整响应全文或完整二进制请求体。
3. 不把日志直接同步到外部服务器。
4. 不替代 Room 中已有的聊天消息持久化。

## 2. 设计原则

### 2.1 以 chat 为中心

所有诊断日志都应能关联到：

- `chatId`
- `projectId`（如果存在）
- `platformUid`
- 本轮用户消息时间点或 turn 序号

这样导出时可以得到一份完整的“这次对话发生了什么”。

### 2.2 事件化，不存全文转储

日志以结构化事件为单位存储，而不是直接把 Logcat 文本整段拷贝进文件。优势：

- 更容易过滤和裁剪
- 更易做脱敏
- 更利于未来按事件类型导出或在 UI 中展示

### 2.3 默认轻量，失败时保留更多

日志策略应偏保守：

- 成功路径记录摘要
- 失败路径记录更多上下文
- 流式输出按聚合后记录，而不是 token 级逐条落盘

### 2.4 先本地持久化，再导出

日志应先保存在 app 私有目录，用户需要排障时再导出。这样不会因为导出失败丢日志，也避免每次对话都做额外 IO。

### 2.5 默认脱敏

以下信息不得明文落盘：

- `Authorization` / `x-api-key` / `api-key`
- 平台 token
- 完整图片 base64
- 完整请求体中可能出现的敏感文件内容

## 3. 需要覆盖的日志范围

### 3.1 网络请求摘要

覆盖对象：

- `OpenAIAPIImpl`
- `AnthropicAPIImpl`
- `GoogleAPIImpl`

记录内容：

- 时间戳
- `chatId` / `platformUid`
- 请求方向：request / response / stream / error
- provider 类型：OpenAI / Anthropic / Google / Qwen / Kimi / etc
- endpoint 路径摘要
- HTTP 方法
- model 标识
- stream 是否开启
- 状态码
- 耗时
- 请求体摘要
- 响应体摘要
- 错误信息摘要

不记录内容：

- token
- 完整 headers
- 完整 response body
- SSE 每个 chunk 的全文

### 3.2 模型执行摘要

覆盖对象：

- 普通聊天链路：`ChatRepositoryImpl`
- Agent 模式：`AgentLoopCoordinator` 与 provider gateway

记录内容：

- turn 开始 / 结束
- 使用的平台与最终模型名
- 是否 agent mode
- 是否有附件、附件数量、附件 MIME 摘要
- 用户输入字符数
- 输出字符数
- thinking 字符数摘要
- 首包耗时、总耗时
- 完成原因：success / cancelled / network_error / provider_error / decode_error / tool_error

Agent 模式额外记录：

- iteration 数
- 触发的 tool 名称列表
- 每个 tool 的开始/结束时间
- tool 是否成功
- tool 调用次数
- 是否出现“应调 tool 但本轮零 tool call”
- 本轮是否以纯文本直接结束

#### 针对“模型开始偷懒不调 tool”的专项记录

这是当前排障里最关键的专项场景之一。

建议在 agent mode 下额外记录以下信号：

- 当前 turn 是否允许使用 tools
- `toolChoiceMode`
- 当前 turn 提供给模型的 tool 数量
- 每轮 iteration 中：
  - 模型是否返回了 `tool_calls`
  - 若没有 `tool_calls`，是否直接输出最终文本
  - 最终文本长度
  - 是否连续多轮零 tool call
- 最近 N 轮 tool 使用统计：
  - 总轮数
  - 有 tool call 的轮数
  - 零 tool call 连续轮数

建议增加一个专门的诊断事件概念：

- `AGENT_TOOL_EXPECTATION`

用于记录“系统为什么认为这一轮大概率应该调 tool”。这个判断不需要复杂 AI 逻辑，首版只要基于规则即可，例如：

- 当前在 agent mode
- `projectId` 非空
- tool registry 非空
- 用户请求包含“修改/修复/查看文件/构建/更新图标”等操作意图

这样后续排查“模型偷懒”时，可以回答：

1. 这一轮本来有没有资格用 tool？
2. 系统是否判断这轮高概率应调 tool？
3. 模型最终有没有调？
4. 从哪一轮开始连续不调？

#### 针对 tool 退化的上下文记录

为了排查“前几轮正常，后几轮开始偷懒”，建议同时记录：

- 当前 full conversation item 数
- context trimming 前后条数
- 是否发生了上下文裁剪
- 最后一次 tool 成功调用距今多少轮
- 当前 system prompt 长度摘要
- 当前 tool schema 数量与总大小摘要

这些信息有助于判断问题更像：

- 上下文裁剪导致模型丢失 tool 使用先例
- prompt 漂移
- tool schema 太大或太复杂
- provider 在长上下文下更偏向直接文本回答

### 3.3 构建执行摘要

覆盖对象：

- `ProjectInitializer`
- `ChatViewModel.runBuild()`
- agent tool 中的构建入口

记录内容：

- 构建开始 / 结束
- `projectId`
- 触发来源：chat run button / agent tool / startup template init
- 最终状态：success / failed
- 总耗时
- 阶段级耗时：RESOURCE / COMPILE / DEX / PACKAGE / SIGN
- `BuildResult.errorMessage`
- 错误日志摘要
- 成功产物路径摘要，例如 `signed.apk`

对于 `BuildResult.logs`：

- 不直接全量永久保存全部 info 日志
- 默认仅保留：
  - 所有 `ERROR`
  - 所有 `WARN`
  - 每个阶段最后 N 条 `INFO`
  - 失败时额外保留最后 M 条全局日志

### 3.4 模型配置快照

用户排障时，最关键的是“当时到底用的是哪个模型、哪个 provider、哪些参数”。因此在每次 turn 开始时记录平台快照：

- `platformUid`
- `compatibleType`
- `apiUrl` 的脱敏摘要
- `model`
- `temperature`
- `topP`
- `reasoning`
- `stream`
- 是否多平台 chat
- 当前聊天绑定的平台集合

不记录：

- token 原文
- system prompt 全文

对于 `systemPrompt`，仅记录：

- 是否存在
- 字符数
- 可选的哈希摘要

### 3.5 耗时分解与卡顿诊断

“模型很久不回复”不能只记录一个总耗时，需要拆成多个阶段。

建议记录以下时间点：

- `turnQueuedAt`
- `networkRequestStartedAt`
- `requestBodyPreparedAt`
- `firstResponseByteAt`
- `firstSemanticEventAt`
  - 非流式：收到完整响应
  - 流式：收到第一个有效 delta / chunk
- `firstToolCallAt`
- `firstOutputTextAt`
- `completedAt`

由此计算：

- 请求准备耗时
- 网络首包耗时
- provider 首有效事件耗时
- tool 首次调用耗时
- 最终输出耗时
- 总耗时

这样日志可以帮助回答：

1. 请求是不是很晚才真正发出？
2. 发出后是不是很久才拿到首包？
3. 首包来了但一直没有有效内容？
4. 是不是在等 tool 执行？
5. 是不是本地已取消或超时，但 UI 还在等待？

对于超时或长时间无响应，还应记录：

- 配置的超时阈值
- 最后一个已知阶段
- 最后一个事件时间
- 失败分类：network_timeout / provider_timeout / app_timeout / cancelled

## 4. 建议的事件模型

建议新增一个独立的诊断日志事件模型，例如：

```text
DiagnosticEvent
├── id
├── timestamp
├── chatId
├── projectId?
├── platformUid?
├── turnId?
├── category
├── level
├── summary
└── payload(json)
```

推荐分类：

- `CHAT_TURN`
- `MODEL_REQUEST`
- `MODEL_RESPONSE`
- `MODEL_STREAM`
- `MODEL_ERROR`
- `LATENCY_BREAKDOWN`
- `AGENT_TOOL_EXPECTATION`
- `BUILD_START`
- `BUILD_STAGE`
- `BUILD_RESULT`
- `AGENT_TOOL`
- `EXPORT`
- `SYSTEM`

推荐日志级别：

- `DEBUG`
- `INFO`
- `WARN`
- `ERROR`

建议 payload 只放结构化字段，不放拼接大文本。大文本若必须保留，也应先裁剪。

### 4.1 通用事件头字段

无论属于哪个 category，所有 `DiagnosticEvent` 都建议包含以下通用字段：

| 字段 | 类型 | 首版 | 说明 |
| --- | --- | --- | --- |
| `id` | `String` | 必需 | 事件唯一 ID，建议 `timestamp + random suffix` |
| `timestamp` | `Long` | 必需 | 事件写入时间，Unix epoch millis |
| `chatId` | `Int` | 必需 | 所属 chat |
| `projectId` | `String?` | 可选 | 若 chat 绑定项目则带上 |
| `platformUid` | `String?` | 可选 | 若事件与某个平台有关则记录 |
| `turnId` | `String?` | 可选 | 单轮对话唯一 ID，建议一轮用户输入一个 |
| `category` | `String` | 必需 | 事件类型 |
| `level` | `String` | 必需 | `DEBUG/INFO/WARN/ERROR` |
| `summary` | `String` | 必需 | 面向导出与快速排查的摘要 |
| `payload` | `JsonObject` | 必需 | 结构化扩展字段 |

### 4.2 字段约定

为了保证版本演进可控，建议统一以下约定：

1. 所有耗时字段统一以 `Ms` 结尾，例如 `durationMs`。
2. 所有长度字段统一使用字符数或字节数，名称显式区分：
   - `textChars`
   - `schemaBytes`
   - `payloadBytes`
3. 布尔字段使用可读前缀：
   - `isAgentMode`
   - `hasAttachments`
   - `wasTrimmed`
4. 枚举值优先使用稳定字符串，不直接依赖 Kotlin enum 名字。
5. 首版字段一旦落地，不轻易重命名；新能力优先追加字段。

## 4A. 首版事件字段清单

首版目标是尽快形成排障闭环，因此只定义实现成本低、排障价值高的事件。

### 4A.1 `CHAT_TURN`

适用场景：

- 用户发起一轮新问题
- 一轮对话结束
- 一轮对话取消/失败

建议拆为 3 类 action：

- `turn_started`
- `turn_completed`
- `turn_failed`

首版 `payload` 字段：

| 字段 | 类型 | 必需 | 说明 |
| --- | --- | --- | --- |
| `action` | `String` | 是 | `turn_started/turn_completed/turn_failed` |
| `turnIndex` | `Int` | 是 | chat 内第几轮 |
| `isAgentMode` | `Boolean` | 是 | 是否走 agent loop |
| `enabledPlatformCount` | `Int` | 是 | 当前 chat 绑定的平台数量 |
| `platformUids` | `List<String>` | 是 | 当前 chat 平台集合 |
| `userTextChars` | `Int` | 是 | 用户输入字符数 |
| `hasAttachments` | `Boolean` | 是 | 是否有附件 |
| `attachmentCount` | `Int` | 是 | 附件数量 |
| `attachmentKinds` | `List<String>` | 否 | 例如 `image` |
| `startedAt` | `Long` | 否 | `turn_started` 时记录 |
| `completedAt` | `Long` | 否 | `turn_completed/failed` 时记录 |
| `durationMs` | `Long` | 否 | 结束类事件记录 |
| `finishReason` | `String` | 否 | `success/cancelled/network_error/provider_error/tool_error/build_error` |
| `outputChars` | `Int` | 否 | 输出字符数摘要 |
| `thinkingChars` | `Int` | 否 | thinking 字符数摘要 |

### 4A.2 `MODEL_REQUEST`

适用场景：

- 真正准备向 provider 发起请求时

首版 `payload` 字段：

| 字段 | 类型 | 必需 | 说明 |
| --- | --- | --- | --- |
| `providerType` | `String` | 是 | `openai/anthropic/google/qwen/kimi/...` |
| `apiFamily` | `String` | 是 | `chat_completions/responses/messages/generate_content/...` |
| `requestMethod` | `String` | 是 | `POST` |
| `endpointPath` | `String` | 是 | 只记录脱敏 path 摘要 |
| `model` | `String` | 是 | 实际使用模型 |
| `stream` | `Boolean` | 是 | 是否流式 |
| `reasoningEnabled` | `Boolean` | 是 | 是否开启 reasoning/thinking |
| `messageCount` | `Int` | 是 | 请求中消息数量 |
| `userMessageCount` | `Int` | 否 | 用户消息数 |
| `assistantMessageCount` | `Int` | 否 | assistant 消息数 |
| `toolCount` | `Int` | 否 | 若存在 tools 则记录 |
| `toolChoiceMode` | `String?` | 否 | agent mode 时记录 |
| `systemPromptPresent` | `Boolean` | 否 | 是否有 system prompt |
| `systemPromptChars` | `Int` | 否 | system prompt 长度 |
| `hasImages` | `Boolean` | 否 | 是否含图片输入 |
| `imageCount` | `Int` | 否 | 图片数量 |
| `requestBodyBytesApprox` | `Int` | 否 | 近似请求体大小，便于判断是否过大 |
| `startedAt` | `Long` | 是 | 请求开始时间 |

### 4A.3 `MODEL_RESPONSE`

适用场景：

- provider 正常返回完成
- provider 返回错误

建议 action：

- `response_completed`
- `response_failed`

首版 `payload` 字段：

| 字段 | 类型 | 必需 | 说明 |
| --- | --- | --- | --- |
| `action` | `String` | 是 | `response_completed/response_failed` |
| `providerType` | `String` | 是 | provider 类型 |
| `model` | `String` | 是 | 实际模型 |
| `statusCode` | `Int?` | 否 | 若可获得则记录 |
| `durationMs` | `Long` | 否 | 从请求发出到结束 |
| `firstByteLatencyMs` | `Long?` | 否 | 首包耗时 |
| `firstSemanticLatencyMs` | `Long?` | 否 | 首个有效事件耗时 |
| `outputChars` | `Int` | 否 | 输出字符数 |
| `thinkingChars` | `Int` | 否 | thinking 字符数 |
| `toolCallCount` | `Int` | 否 | 若模型返回了 tool calls |
| `finishReason` | `String?` | 否 | provider finish reason |
| `errorKind` | `String?` | 否 | `network_error/http_error/decode_error/provider_error` |
| `errorMessagePreview` | `String?` | 否 | 裁剪后的错误摘要 |

### 4A.4 `LATENCY_BREAKDOWN`

适用场景：

- 一轮请求结束后统一写入时序分解结果

首版 `payload` 字段：

| 字段 | 类型 | 必需 | 说明 |
| --- | --- | --- | --- |
| `providerType` | `String` | 是 | provider 类型 |
| `model` | `String` | 是 | 模型名 |
| `requestPreparedMs` | `Long?` | 否 | 请求准备耗时 |
| `firstByteLatencyMs` | `Long?` | 否 | 首包耗时 |
| `firstSemanticLatencyMs` | `Long?` | 否 | 首有效事件耗时 |
| `firstToolCallLatencyMs` | `Long?` | 否 | 首次 tool call 耗时 |
| `firstOutputLatencyMs` | `Long?` | 否 | 首次输出正文耗时 |
| `totalDurationMs` | `Long` | 是 | 总耗时 |
| `maxSilentGapMs` | `Long?` | 否 | 流式期间最大静默时间 |
| `timeoutKind` | `String?` | 否 | 若超时则记录类别 |
| `lastObservedStage` | `String?` | 否 | 卡住时最后已知阶段 |

### 4A.5 `BUILD_RESULT`

适用场景：

- 构建完成或失败

首版 `payload` 字段：

| 字段 | 类型 | 必需 | 说明 |
| --- | --- | --- | --- |
| `triggerSource` | `String` | 是 | `chat_button/agent_tool/template_init` |
| `projectId` | `String` | 是 | 所属项目 |
| `status` | `String` | 是 | `success/failed` |
| `durationMs` | `Long` | 是 | 构建总耗时 |
| `currentStage` | `String?` | 否 | 失败时最后阶段 |
| `resourceStageMs` | `Long?` | 否 | 阶段耗时摘要 |
| `compileStageMs` | `Long?` | 否 | 阶段耗时摘要 |
| `dexStageMs` | `Long?` | 否 | 阶段耗时摘要 |
| `packageStageMs` | `Long?` | 否 | 阶段耗时摘要 |
| `signStageMs` | `Long?` | 否 | 阶段耗时摘要 |
| `errorCount` | `Int` | 否 | `ERROR` 日志条数 |
| `warningCount` | `Int` | 否 | `WARN` 日志条数 |
| `errorMessagePreview` | `String?` | 否 | 裁剪后的 errorMessage |
| `artifactSummary` | `List<String>` | 否 | 例如 `SIGN:signed.apk` |

### 4A.6 `AGENT_TOOL`

适用场景：

- tool 开始执行
- tool 执行完成

建议 action：

- `tool_started`
- `tool_finished`

首版 `payload` 字段：

| 字段 | 类型 | 必需 | 说明 |
| --- | --- | --- | --- |
| `action` | `String` | 是 | `tool_started/tool_finished` |
| `iteration` | `Int` | 是 | agent iteration |
| `toolName` | `String` | 是 | tool 名称 |
| `toolCallId` | `String?` | 否 | provider 返回的 tool call id |
| `argumentBytesApprox` | `Int?` | 否 | 参数大小摘要 |
| `startedAt` | `Long?` | 否 | 开始时间 |
| `completedAt` | `Long?` | 否 | 完成时间 |
| `durationMs` | `Long?` | 否 | 执行耗时 |
| `success` | `Boolean?` | 否 | 完成类事件记录 |
| `errorKind` | `String?` | 否 | 若失败则记录 |
| `outputBytesApprox` | `Int?` | 否 | 结果大小摘要 |

### 4A.7 `AGENT_TOOL_EXPECTATION`

适用场景：

- 记录“系统认为本轮应调 tool”的规则化判断

首版 `payload` 字段：

| 字段 | 类型 | 必需 | 说明 |
| --- | --- | --- | --- |
| `isAgentMode` | `Boolean` | 是 | 是否 agent mode |
| `toolCount` | `Int` | 是 | 可用 tool 数量 |
| `projectPresent` | `Boolean` | 是 | 是否存在 projectId |
| `matchedIntentRules` | `List<String>` | 否 | 命中的规则名称 |
| `expectedToolUse` | `Boolean` | 是 | 本轮是否判断应调 tool |
| `actualToolCallCount` | `Int` | 否 | 最终实际调了几个 |
| `zeroToolCallStreak` | `Int?` | 否 | 连续零 tool call 轮数 |
| `contextItemCount` | `Int?` | 否 | 当前上下文规模 |
| `wasTrimmed` | `Boolean?` | 否 | 是否发生裁剪 |

## 4B. 第二版事件字段清单

第二版目标不是扩大量，而是提高“为什么退化/为什么慢”的定位精度。

### 4B.1 对首版事件的追加字段

#### `CHAT_TURN`

建议追加：

- `questionPreview`
- `questionHash`
- `selectedPlatformModels`
- `cancelledByUser`
- `assistantPlatformOutcomes`

#### `MODEL_REQUEST`

建议追加：

- `apiHostMasked`
- `requestHeadersSummary`
- `conversationItemsBeforeTrim`
- `conversationItemsAfterTrim`
- `conversationBytesApprox`
- `toolSchemaBytesTotal`
- `toolNames`
- `attachmentMimeKinds`

#### `MODEL_RESPONSE`

建议追加：

- `rawChunkCount`
- `responseId`
- `reasoningSummaryPresent`
- `providerFinishReason`
- `httpStatusText`
- `retryable`

#### `LATENCY_BREAKDOWN`

建议追加：

- `queuedBeforeRequestMs`
- `networkCompletedMs`
- `streamWindowMs`
- `toolExecutionTotalMs`
- `idleGapsMsTopN`

#### `BUILD_RESULT`

建议追加：

- `logTailPreview`
- `stageStatusMap`
- `classCountApprox`
- `resourceFileCountApprox`
- `sourceFileCountApprox`

#### `AGENT_TOOL`

建议追加：

- `argumentPreview`
- `outputPreview`
- `toolCategory`
- `retryCount`

#### `AGENT_TOOL_EXPECTATION`

建议追加：

- `lastToolSuccessTurnsAgo`
- `toolSchemaComplexityScore`
- `promptChars`
- `contextPressureScore`

### 4B.2 第二版新增事件

#### `MODEL_STREAM`

用途：

- 对流式输出做更精细但仍聚合的观测

建议 `payload` 字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `chunkCount` | `Int` | chunk 数 |
| `firstChunkAt` | `Long?` | 第一个 chunk 时间 |
| `lastChunkAt` | `Long?` | 最后一个 chunk 时间 |
| `textChars` | `Int` | 文本总长度 |
| `thinkingChars` | `Int` | thinking 总长度 |
| `maxSilentGapMs` | `Long?` | 最大静默区间 |
| `streamCompleted` | `Boolean` | 是否完整结束 |

#### `BUILD_STAGE`

用途：

- 记录各阶段开始/结束与阶段内错误摘要

建议 `payload` 字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `stage` | `String` | `RESOURCE/COMPILE/DEX/PACKAGE/SIGN` |
| `action` | `String` | `started/completed/failed` |
| `startedAt` | `Long?` | 开始时间 |
| `completedAt` | `Long?` | 完成时间 |
| `durationMs` | `Long?` | 阶段耗时 |
| `errorCount` | `Int` | 阶段错误数 |
| `warningCount` | `Int` | 阶段警告数 |
| `errorPreview` | `String?` | 阶段错误摘要 |

#### `MODEL_CONFIG_SNAPSHOT`

用途：

- 单独保留每轮平台配置快照，便于事后比对

建议 `payload` 字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `providerType` | `String` | provider |
| `model` | `String` | 模型 |
| `temperature` | `Float?` | 温度 |
| `topP` | `Float?` | topP |
| `reasoningEnabled` | `Boolean` | 是否 reasoning |
| `stream` | `Boolean` | 是否流式 |
| `systemPromptPresent` | `Boolean` | 是否有 system prompt |
| `systemPromptChars` | `Int` | system prompt 长度 |
| `apiUrlMasked` | `String` | 脱敏地址 |

## 4C. 最终版事件字段清单

最终版目标是让日志既能排障，也能支持长期优化 tool 设计、provider 选择和交互流畅度。

### 4C.1 对第二版事件的进一步扩展

建议最终版追加的方向：

#### 行为退化分析

- `toolUseRateRollingWindow`
- `zeroToolCallProbability`
- `assistantOnlyTextStreak`
- `contextCompressionStrategy`
- `toolSchemaVersion`
- `promptTemplateVersion`

#### 体验性能分析

- `uiRenderLatencyMs`
- `dbPersistLatencyMs`
- `exportLatencyMs`
- `attachmentReadLatencyMs`
- `imageEncodingLatencyMs`

#### tool 设计优化分析

- `toolSelectionEntropy`
- `toolArgumentValidationFailures`
- `toolResultRejectedByModelCount`
- `toolReentryCount`
- `toolChainLength`

### 4C.2 最终版新增事件

#### `TOOL_SCHEMA_METRICS`

用途：

- 用于长期优化 tool 定义

建议 `payload` 字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `toolName` | `String` | tool 名称 |
| `schemaBytes` | `Int` | schema 大小 |
| `propertyCount` | `Int` | 属性数量 |
| `requiredCount` | `Int` | required 字段数量 |
| `enumValueCount` | `Int` | enum 总数 |
| `descriptionChars` | `Int` | description 长度 |
| `callFrequencyRolling` | `Float?` | 滚动调用率 |
| `failureRateRolling` | `Float?` | 滚动失败率 |

#### `CONTEXT_TRIM`

用途：

- 专门排查长对话后模型退化

建议 `payload` 字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `beforeItemCount` | `Int` | 裁剪前条数 |
| `afterItemCount` | `Int` | 裁剪后条数 |
| `beforeCharsApprox` | `Int` | 裁剪前大小 |
| `afterCharsApprox` | `Int` | 裁剪后大小 |
| `droppedTurnCount` | `Int` | 丢弃轮数 |
| `preservedToolHistory` | `Boolean` | 是否保留最近工具历史 |
| `strategy` | `String` | 使用的裁剪策略 |

#### `TOOL_OPTIMIZATION_HINT`

用途：

- 基于离线统计生成建议，不一定首版在线生成

建议 `payload` 字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `hintType` | `String` | `unused_tool/slow_tool/high_failure_tool/schema_too_large` |
| `toolName` | `String?` | 若针对具体 tool |
| `evidenceWindow` | `String` | 统计窗口 |
| `evidenceSummary` | `String` | 证据摘要 |
| `recommendedAction` | `String` | 建议动作 |

## 4D. 版本化建议

为了避免日志 schema 演进混乱，建议从首版开始就在文件中带上版本信息：

- `schemaVersion: 1`

推荐策略：

1. 首版实现 `schemaVersion = 1`
2. 第二版扩展为 `schemaVersion = 2`
3. 最终版若字段继续增加但保持兼容，可继续追加字段并升级版本
4. 导出时在 `manifest.json` 中写入：
   - `diagnosticSchemaVersion`
   - `appVersion`
   - `exportedAt`

这样未来解析旧日志时，不需要猜测字段是否存在。

## 4E. 首版实现规约

本节用于把首版从“设计”收紧到“可以直接实现”的状态。若本节与前文更宽泛的建议冲突，以本节为准。

### 4E.1 首版必须落地的事件

首版只要求实现以下 6 类事件：

1. `CHAT_TURN`
2. `MODEL_REQUEST`
3. `MODEL_RESPONSE`
4. `LATENCY_BREAKDOWN`
5. `BUILD_RESULT`
6. `AGENT_TOOL`

`AGENT_TOOL_EXPECTATION` 在文档中保留，但首版实现优先级为“低”。如果实现进度紧张，可以先不落地该事件，只需在代码结构上预留扩展点。

### 4E.2 首版必须保证的字段

所有首版事件必须保证：

- 通用头字段全部存在
- `payload` 必须是合法 JSON object
- 必需字段不可缺失
- 可选字段允许缺省，但不应写入语义不清的占位值，例如空字符串 `""`

字段填充规则：

1. 拿不到值时：
   - 可选字段直接省略
   - 必需字段必须给出稳定默认值或在更上游补齐
2. 近似值允许进入首版，但字段名要明确：
   - 使用 `BytesApprox`
   - 使用 `Preview`
3. 首版不要求所有耗时都绝对精确，但要求：
   - 计算方式前后一致
   - 日志能区分“首包慢”与“总耗时长”

### 4E.3 首版事件发射矩阵

| 事件 | 首版必做 | 发射时机 | 主要生产者 |
| --- | --- | --- | --- |
| `CHAT_TURN.turn_started` | 是 | 用户问题进入发送流程时 | `ChatViewModel.askQuestion()` |
| `CHAT_TURN.turn_completed` | 是 | 某轮全部完成时 | `ChatRepositoryImpl` / `AgentLoopCoordinator` 结束后，由聚合层统一发 |
| `CHAT_TURN.turn_failed` | 是 | 某轮失败时 | 同上 |
| `MODEL_REQUEST` | 是 | 真正发起 provider 请求前 | `OpenAIAPIImpl` / `AnthropicAPIImpl` / `GoogleAPIImpl` 外层调用方 |
| `MODEL_RESPONSE.response_completed` | 是 | provider 请求结束成功后 | provider completion 路径 |
| `MODEL_RESPONSE.response_failed` | 是 | provider 请求失败后 | provider completion 路径 |
| `LATENCY_BREAKDOWN` | 是 | 一轮 provider 调用完成后 | 请求生命周期聚合器 |
| `BUILD_RESULT` | 是 | 构建结束后 | `ProjectInitializer` 或 chat/build 触发层 |
| `AGENT_TOOL.tool_started` | 是 | tool 执行前 | `DefaultAgentLoopCoordinator` |
| `AGENT_TOOL.tool_finished` | 是 | tool 执行后 | `DefaultAgentLoopCoordinator` |
| `AGENT_TOOL_EXPECTATION` | 否 | turn 开始或结束时 | 后续版本 |

### 4E.4 首版 `turnId` 规则

首版必须为每轮对话生成稳定 `turnId`，用于把网络、模型、构建、tool 事件串起来。

推荐规则：

```text
turnId = "{chatId}-{turnIndex}-{startedAt}"
```

要求：

- `turn_started` 产生 `turnId`
- 同一轮内所有后续事件都沿用该 `turnId`
- 多平台 chat 下，不同平台共享同一 `turnId`，通过 `platformUid` 区分

### 4E.5 首版首要接入点

为了降低改动范围，首版只要求以下位置接入：

#### 聊天入口

- `ChatViewModel.askQuestion()`

职责：

- 生成 `turnId`
- 记录 `CHAT_TURN.turn_started`
- 将 `turnId` 传给后续链路

#### 普通聊天链路

- `ChatRepositoryImpl.completeChat(...)`
- provider-specific completion methods

职责：

- 记录模型快照摘要
- 收集输出字符数、thinking 字符数、finish reason
- 统一发射 `CHAT_TURN.turn_completed/failed`

#### 网络层

- `OpenAIAPIImpl`
- `AnthropicAPIImpl`
- `GoogleAPIImpl`

职责：

- 记录 `MODEL_REQUEST`
- 记录 `MODEL_RESPONSE`
- 给 `LATENCY_BREAKDOWN` 提供时间点

#### Agent/tool 链路

- `DefaultAgentLoopCoordinator`

职责：

- 记录 `AGENT_TOOL.tool_started/tool_finished`
- 汇总 tool 调用次数
- 把 tool 时延交给 turn 聚合器

#### 构建链路

- `ProjectInitializer.buildProject(...)`
- `ChatViewModel.runBuild()`

职责：

- 记录 `BUILD_RESULT`
- 补充触发来源与 chat/project 关联

### 4E.6 首版文件格式规约

首版文件格式固定为：

```text
files/logs/chats/{chatId}/
├── current.ndjson
└── index.json
```

首版暂不强制实现：

- `archived/*.gz`
- 多文件滚动
- 全局清理索引

但代码结构必须预留：

- `rollIfNeeded()`
- `pruneChatLogsIfNeeded()`

这样第二版扩展时不用重写存储层。

#### `current.ndjson`

规则：

- UTF-8
- 每行一个完整 JSON object
- 不允许跨行格式化
- append-only

#### `index.json`

首版最小字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `chatId` | `Int` | 所属 chat |
| `schemaVersion` | `Int` | 首版固定为 `1` |
| `currentFile` | `String` | 固定 `current.ndjson` |
| `eventCount` | `Long` | 当前累计事件数 |
| `lastUpdatedAt` | `Long` | 最后更新时间 |
| `byteSizeApprox` | `Long` | 文件近似大小 |

### 4E.7 首版导出规约

首版导出新增目标：

```text
chat_export_<title>_<timestamp>.zip
├── chat.md
├── diagnostic-log.ndjson
└── manifest.json
```

`manifest.json` 首版最小字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `chatId` | `Int` | chat id |
| `chatTitle` | `String` | chat 标题 |
| `exportedAt` | `Long` | 导出时间 |
| `appVersion` | `String` | app 版本 |
| `diagnosticSchemaVersion` | `Int` | 首版固定 `1` |
| `diagnosticEventCount` | `Long` | 导出日志条数 |

首版要求：

- 若诊断日志不存在，也能正常导出 `chat.md`
- 若诊断日志存在，则导出 `diagnostic-log.ndjson`
- 不要求首版直接导出 `.gz`

### 4E.8 首版允许延后的能力

以下内容明确不进入首版实现：

- `MODEL_STREAM`
- `BUILD_STAGE`
- `MODEL_CONFIG_SNAPSHOT`
- `TOOL_SCHEMA_METRICS`
- `CONTEXT_TRIM`
- `TOOL_OPTIMIZATION_HINT`
- 自动分析“模型偷懒”的复杂规则
- 日志查看 UI
- 压缩归档与多文件滚动的完整策略

### 4E.9 首版性能约束

首版实现必须满足以下约束：

1. 日志写入不得发生在主线程。
2. provider 流式响应过程中不得对每个 chunk 单独落盘。
3. 单次事件序列化后建议不超过 `8 KB`。
4. `MODEL_REQUEST` / `MODEL_RESPONSE` 只能记录摘要，不得写入原始大 body。
5. 图片输入不得记录 base64 本体。

### 4E.10 首版错误处理策略

诊断日志系统自身不得影响主业务链路。

要求：

1. 任意日志写入失败不能中断聊天、构建或导出主流程。
2. 日志子系统内部异常只能：
   - 吞掉并降级
   - 可选写入 Logcat
3. 不允许因为日志写入失败导致模型请求失败或构建失败。

### 4E.11 首版验收标准

完成首版后，应至少满足以下验收项：

1. 新建 chat，发送一次普通请求，能落下：
   - `CHAT_TURN.turn_started`
   - `MODEL_REQUEST`
   - `MODEL_RESPONSE`
   - `LATENCY_BREAKDOWN`
   - `CHAT_TURN.turn_completed` 或 `turn_failed`
2. 单平台 agent chat 调用 tool 时，能看到：
   - `AGENT_TOOL.tool_started`
   - `AGENT_TOOL.tool_finished`
3. 触发一次构建成功和一次构建失败时，都能落下 `BUILD_RESULT`
4. 导出聊天时，zip 中能带上 `diagnostic-log.ndjson`
5. 日志中不出现 token 明文、图片 base64、完整绝对文件路径正文

## 4F. 第二版实现规约

第二版不是推翻首版，而是在首版之上追加更强的时序与退化定位能力。

### 4F.1 第二版必须新增

1. `MODEL_STREAM`
2. `BUILD_STAGE`
3. `MODEL_CONFIG_SNAPSHOT`
4. `AGENT_TOOL_EXPECTATION`
5. `context trimming` 相关观测字段

### 4F.2 第二版主要目标

1. 能明确定位“长时间无响应”到底卡在首包、流式静默还是 tool 执行。
2. 能回答“为什么前几轮会调 tool，后几轮不调了”。
3. 能支持按阶段分析 build 变慢或失败聚集在哪个阶段。

### 4F.3 第二版新增接入点

- `ConversationContextManager`
- provider streaming 聚合器
- build pipeline 阶段更新回调

### 4F.4 第二版验收标准

1. 流式请求能落下 `MODEL_STREAM`
2. 长对话触发上下文裁剪后，日志中能看到裁剪前后规模变化
3. 构建各阶段都有独立 `BUILD_STAGE`
4. agent mode 下能落下 `AGENT_TOOL_EXPECTATION`

## 4G. 最终版实现规约

最终版关注长期优化，不以“能不能排障”为唯一标准，而是要支持持续迭代 tools 和 prompt。

### 4G.1 最终版必须补齐

1. `TOOL_SCHEMA_METRICS`
2. `CONTEXT_TRIM`
3. `TOOL_OPTIMIZATION_HINT`
4. 多文件滚动与 `.gz` 压缩归档
5. 全局配额清理
6. 可选的日志查看 UI 或统计面板

### 4G.2 最终版主要目标

1. 为 tool 设计优化提供可量化指标
2. 为 provider / prompt / schema 选择提供长期趋势数据
3. 让“模型偷懒”“上下文退化”“tool 过重”“tool 慢”这些问题可被稳定度量

### 4G.3 最终版验收标准

1. 可以基于日志回答：
   - 哪个 tool 最慢
   - 哪个 tool 最少用
   - 哪个 tool 最易失败
   - 哪类请求最容易连续零 tool call
2. 单个 chat 日志可滚动归档，不会无限增长
3. 全局日志总量受控
4. 导出包能带 schema version 与必要统计摘要

## 5. 存储方案

### 5.1 存储位置

建议放在 app 私有目录：

```text
files/logs/chats/{chatId}/
├── index.json
├── current.ndjson
└── archived/
    ├── 2026-03-22T10-14-30.ndjson.gz
    └── ...
```

说明：

- `current.ndjson`：当前活跃日志文件，按行追加 JSON event。
- `archived/*.ndjson.gz`：滚动归档文件，压缩存储。
- `index.json`：记录文件列表、大小、最后更新时间、当前 turn 序号等元数据。

选择 NDJSON 的原因：

- append 成本低
- 出错时不容易损坏整份文件
- 读取导出时可以流式处理

### 5.2 为什么不建议直接存 Room

Room 更适合查询型业务数据，不适合高频 append 的流式日志。若把诊断日志塞入 Room，会带来：

- 表体积膨胀
- schema 和 migration 成本增加
- 大文本 IO 压力

因此日志建议走文件存储，业务实体仍留在 Room。

## 6. 文件大小与性能控制

这是本设计的核心约束。

### 6.1 事件裁剪

每类事件设置不同裁剪上限：

- request/response body 摘要：最多 `1-2 KB`
- SSE 聚合文本：最多 `2 KB`
- 错误堆栈：最多 `4 KB`
- 构建阶段日志摘要：每阶段最多 `20-50` 条

### 6.2 流式输出聚合

不要为每个 SSE chunk 都写文件。建议：

- 内存中维护本轮 stream 聚合器
- 每隔固定字符数或在完成时输出一条聚合事件
- 默认只保留：
  - 首包时间
  - 总 chunk 数
  - 总字符数
  - 最终文本摘要

失败时可额外记录最后若干 raw chunk 摘要。

为了支持“卡了很久”的排查，流式聚合器除了文本摘要外，还应保留时间摘要：

- 第一个 chunk 到达时间
- 最后一个 chunk 到达时间
- chunk 总数
- 最大静默区间时长

这里的“静默区间”很重要，因为它能区分：

- 网络确实没有返回数据
- 还是 provider 在长时间思考后才继续输出

### 6.3 文件滚动策略

建议任一 chat 的单日志文件达到以下任一条件时滚动：

- 文件大小超过 `512 KB`
- 事件数超过 `1000`
- 或结束一个 turn 后检查达到阈值

滚动后压缩归档为 `.gz`。

### 6.4 每 chat 配额

建议设置每个 chat 的最大日志预算，例如：

- 活跃日志 + 归档总计最大 `5 MB`

超限时按最旧归档优先删除。

### 6.5 全局配额

建议增加全局上限，例如：

- 所有 chat 诊断日志总量最大 `64 MB`

超限时根据“最后访问时间 + 是否最近失败”做淘汰。

### 6.6 后台写入

文件写入必须走后台协程，避免阻塞主线程。建议：

- 单独 `Dispatcher.IO`
- 单 writer actor / channel 串行化写入
- UI 与网络层只投递事件，不直接操作文件

## 7. 脱敏与安全策略

### 7.1 头部脱敏

复用 `NetworkLogcatLogger` 已有规则，并扩展到持久化日志：

- `Authorization` -> `***`
- `x-api-key` -> `***`
- `api-key` -> `***`

### 7.2 URL 脱敏

完整 URL 可能泄漏私有部署地址。建议记录：

- scheme
- host 的可选掩码
- path

例如：

```text
https://api.moonshot.cn/v1/chat/completions
http://192.168.x.x:11434/v1/chat/completions
```

### 7.3 请求体脱敏

不记录完整 `messages` 数组，只记录摘要：

- message 条数
- 每条 role
- 文本长度
- 是否含图片
- 图片数量

不要记录：

- base64 内容
- 原始图片 URI
- 文件绝对路径原文

文件路径建议只保留相对标签或哈希。

### 7.4 Prompt 脱敏

用户输入和系统提示词不建议默认全量持久化到诊断日志。因为聊天消息本身已经单独存于数据库。

日志中只记录：

- 文本长度
- 可选首 `120` 个字符摘要
- 可选哈希

## 8. 推荐架构

建议新增一个轻量诊断日志子系统：

```text
presentation / feature / data
        ↓
  DiagnosticLogService
        ↓
 DiagnosticEventBuffer
        ↓
  ChatLogFileStore (NDJSON)
```

建议职责拆分：

### 8.1 `DiagnosticLogService`

职责：

- 暴露统一 API，例如 `record(event)`
- 负责附加通用上下文：时间、chatId、projectId、platformUid
- 对上层隐藏具体存储实现

### 8.2 `DiagnosticEventFactory`

职责：

- 从网络请求、构建结果、agent 事件生成标准化 `DiagnosticEvent`
- 做裁剪和脱敏

### 8.3 `ChatLogFileStore`

职责：

- NDJSON append
- 文件滚动
- 压缩归档
- 配额淘汰
- 导出拼装

### 8.4 `DiagnosticExportService`

职责：

- 将聊天 Markdown 导出与诊断日志打包到一个 zip
- 或单独导出 `chat-log.jsonl.gz`

### 8.5 `AgentPerformanceAnalyzer`（可选后续层）

这不是首版必须实现的服务，但设计上应预留。

职责：

- 基于诊断日志生成 tool 使用和耗时统计
- 识别连续零 tool call、异常高延迟、失败率升高等模式
- 为 future tool 设计优化提供数据支撑

## 9. 推荐接入点

### 9.1 网络层

在现有 `NetworkLogcatLogger` 旁边新增持久化通道，不建议直接把当前 Logcat 文本再解析一遍。更合理的方式是：

1. 保留 `NetworkLogcatLogger` 负责开发期 Logcat。
2. 新增 `NetworkDiagnosticLogger` 或 `DiagnosticLogService` 接口。
3. 在 `OpenAIAPIImpl` / `AnthropicAPIImpl` / `GoogleAPIImpl` 的现有 `logRequest/logResponse/logNetworkError` 调用点旁边，直接记录结构化事件。

这样避免“先拼字符串，再拆字符串”的无效开销。

### 9.2 聊天与模型链路

建议接入：

- `ChatViewModel.askQuestion()`
- `ChatRepositoryImpl.completeChat(...)`
- `ChatRepositoryImpl` provider-specific completion methods
- `AgentLoopCoordinator.run(...)`

原因：

- 这里能拿到 `chatId`
- 能拿到平台与模型信息
- 能知道这一轮是普通聊天还是 agent mode
- 能作为耗时拆分的起点

### 9.3 构建链路

建议接入：

- `ProjectInitializer.buildProject(...)`
- `ChatViewModel.runBuild()`
- agent build tool 返回结果处

其中：

- `ProjectInitializer` 负责记录最原始的构建摘要和 `BuildResult`
- `ChatViewModel` 负责补充“这次构建由哪个 chat/用户动作触发”

### 9.4 Agent loop / tool 观测链路

为了支撑 tool 设计优化，建议额外在以下位置接入：

- `DefaultAgentLoopCoordinator`
- `ConversationContextManager`
- `AgentToolRegistry`
- 各具体 tool 执行入口

推荐新增记录：

- 当前 iteration 编号
- 当前对模型提供的 tool 名称列表
- schema 总大小摘要
- 工具执行前参数大小摘要
- 工具执行结果大小摘要
- 工具失败原因分类：
  - 参数解析失败
  - 文件不存在
  - 构建失败
  - 网络失败
  - 未知错误

这样后续优化 tool 设计时，可以直接回答：

1. 哪个 tool 最常被调用？
2. 哪个 tool 最常失败？
3. 哪个 tool 最慢？
4. 哪个 tool 的 schema 太大但几乎不用？
5. 哪些用户请求本该命中某个 tool，但最终没调？

## 10. 导出方案

当前已经支持 Markdown 聊天导出。建议新增两种导出模式：

### 10.1 聊天 + 诊断 zip

导出为：

```text
chat_export_<title>_<timestamp>.zip
├── chat.md
├── diagnostic-log.ndjson
└── manifest.json
```

`manifest.json` 可包含：

- app version
- exportedAt
- chatId
- chat title
- platform summary
- diagnostic file count

这是最适合用户反馈 bug 的模式。

### 10.2 单独诊断日志导出

如果后续 UI 需要，可允许单独导出：

- `diagnostic-log.ndjson.gz`

## 11. 建议的数据保留策略

默认建议：

- 只为最近有活动的 chat 保留日志
- 删除 chat 时同步删除其日志目录
- 导出后不自动删除本地日志

保留时间建议：

- 成功 chat：最近 `7-14` 天
- 最近失败过的 chat：可延长到 `30` 天

如果不想基于时间清理，也可以只基于全局配额做 LRU 淘汰。

## 12. UI 建议

本阶段不强制做复杂日志查看 UI，但建议至少预留：

1. 设置页开关：启用诊断日志
2. 聊天页更多菜单项：导出聊天诊断包
3. 可选的聊天详情项：查看最近一次构建摘要 / 最近一次请求摘要

默认行为建议：

- 诊断日志默认开启，但采用轻量模式
- 若后续担心体积，可提供：
  - `关闭`
  - `轻量`
  - `详细（仅调试）`

## 13. 实施计划

### Phase 1: 基础设施

- 新增 `DiagnosticEvent` 模型
- 新增 `DiagnosticLogService`
- 新增 `ChatLogFileStore`
- 实现 NDJSON append、滚动、压缩、配额清理

### Phase 2: 网络摘要

- 在 `OpenAIAPIImpl` / `AnthropicAPIImpl` / `GoogleAPIImpl` 接入结构化网络事件
- 替换“仅 Logcat 可见”的模式

### Phase 3: 模型与 agent 摘要

- 在 `ChatRepositoryImpl`、`AgentLoopCoordinator` 记录 turn 生命周期、模型配置快照、tool 调用摘要

### Phase 4: 构建摘要与耗时分解

- 在 `ProjectInitializer` 与聊天触发点接入构建摘要事件
- 对 `BuildResult.logs` 做裁剪聚合
- 记录首包、首有效事件、首次 tool call、总完成时间等耗时指标

### Phase 5: tool 退化与调优分析

- 增加“应调 tool 但未调”的规则化诊断事件
- 记录上下文裁剪与 tool 使用趋势
- 生成可导出的 tool 使用统计摘要

### Phase 6: 导出

- 扩展当前聊天导出逻辑，支持 zip 打包 Markdown + 诊断日志

## 14. 关键权衡

### 14.1 为什么不直接记录完整 request/response

因为完整请求体可能非常大，还会包含：

- 用户 prompt 全文
- 图片 base64
- token/私有地址

这既不安全，也会让日志体积快速失控。

### 14.2 为什么不继续只依赖 Logcat

Logcat 的问题是：

- 无法和 `chatId` 稳定关联
- 容易丢失
- 用户导出困难
- 结构化分析困难

### 14.3 为什么不把构建全量日志都写进去

构建日志可能很长，而且成功路径的大量 `INFO` 对排障价值有限。保留错误、警告和尾部摘要更合理。

## 15. 建议的首版最小实现

如果希望尽快落地，建议首版只做下面这组最小闭环：

1. `DiagnosticLogService + ChatLogFileStore`
2. 网络请求摘要
3. turn 开始/结束摘要
4. 构建结果摘要
5. 首包/总耗时拆分
6. `导出聊天诊断包`

首版先不做：

1. 日志查看 UI
2. token 级流式落盘
3. 全量 tool payload 落盘
4. 自动判定“模型偷懒”的复杂 AI 规则

这样可以用较小改动换来明显的排障提升。

## 16. 需要同步更新的文档

该设计落地后，建议同步更新：

- `docs/architecture.md`
- `README.md` 中的导出/排障说明
- 如有设置页开关，再更新相关设置文档
