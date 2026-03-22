# AI 代码生成策略 | AI Strategy

## 核心原则

VibeApp 的 AI 代码生成遵循一个核心原则：**约束优于自由**。

给 AI 越多的自由度，生成的代码就越不可控。通过严格的 System Prompt 约束和模板项目骨架，将 AI 的创造力限制在可编译、可运行的范围内。

## 模板系统

### 设计理念

AI 在预定义的项目骨架上增量开发，通过 tool calling 读写文件、触发构建、修复错误。

```
模板提供（app/src/main/assets/templates/EmptyActivity/）：
├── AndroidManifest.xml       (固定结构，含 CrashHandlerApp)
├── MainActivity.java         → AI 通过 write_project_file 工具重写
├── activity_main.xml         → AI 通过 write_project_file 工具重写
├── CrashHandlerApp.java      (崩溃处理，通常不修改)
├── themes.xml                (NoActionBar 主题)
├── colors.xml
└── strings.xml               → AI 按需修改
```

### 生成的应用约束

- **语言**：Java 8（不支持 lambda、method reference、try-with-resources）
- **UI**：XML 布局 + View 系统
- **基类**：必须使用 `AppCompatActivity`（来自 AndroidX）
- **主题**：必须使用 `Theme.MaterialComponents.DayNight.NoActionBar`
- **依赖**：仅限 bundled AndroidX/Material 库，不支持 Gradle 依赖解析

## System Prompt 架构

### 文件位置

System prompt 以独立文件存放，运行时加载：

```
app/src/main/assets/agent-system-prompt.md
```

由 `DefaultAgentLoopCoordinator` 在启动时通过 `context.assets.open()` 读取，使用 `lazy` 缓存。

### 模板变量

| 变量 | 说明 | 示例值 |
|------|------|--------|
| `{{PACKAGE_NAME}}` | 生成应用的包名 | `com.vibe.generated.p202603222` |
| `{{PACKAGE_PATH}}` | 包名对应的路径 | `com/vibe/generated/p202603222` |

变量替换在 `buildInstructions()` 中执行，发生在每次 agent loop 请求时。

### Prompt 结构

```
[角色定义]                    — 1 行
[CRITICAL CONSTRAINTS]        — NEVER / ALWAYS 规则（核心防崩溃约束）
[Bundled Libraries]           — 仅列出已打包的 AndroidX/Material 库清单
[Template Project Structure]  — 模板文件列表
[App Icon Requests]           — 图标生成规则
[Phased Workflow]             — 5 阶段工作流程
[Hard Rules]                  — 4 条硬性规则
[Additional System Prompt]    — 用户在平台设置中自定义的补充指令（可选）
```

### 设计原则

1. **只告诉 AI 不知道的事** — 标准 Android API、Material Component style 名称等 LLM 训练数据已覆盖的内容不需要列出。Prompt 的价值在于告诉 AI 这个环境的*特殊约束*（无 Gradle、无 lambda、特定打包库）。

2. **内容与代码分离** — Prompt 内容在 markdown 文件中维护，不硬编码在 Kotlin 代码里。新增规则或修复崩溃模式时只需编辑 markdown 文件，无需修改 Kotlin 代码。

3. **信噪比优先** — 每个 token 都占用 context window，会压缩留给用户对话历史的空间。低价值的 API 文档会稀释高价值的约束规则。

### 维护指南

**添加新的防崩溃规则**：编辑 `agent-system-prompt.md` 中的 NEVER/ALWAYS 段落。

**添加新的可用库**：编辑 `agent-system-prompt.md` 中的 Bundled Libraries 段落。同时需要确保 `build-engine` 中已打包对应的预编译产物。

**添加新的模板变量**：
1. 在 `agent-system-prompt.md` 中使用 `{{VARIABLE_NAME}}` 占位符
2. 在 `DefaultAgentLoopCoordinator.buildInstructions()` 中添加对应的 `.replace()` 调用

## 多模型适配

不同 provider 通过 `AgentModelGateway` 接口适配，但 system prompt 是统一的。用户可在平台设置中添加 `systemPrompt` 做 provider 级别的微调，会作为 `[Additional System Prompt]` 追加到主 prompt 之后。

## 评估指标

| 指标 | 目标 | 说明 |
|------|------|------|
| 首次编译成功率 | > 60% | 预检通过后直接编译成功 |
| 修复后编译成功率 | > 90% | 经过自动修复循环 |
| 平均修复次数 | < 2 | 大多数问题 1-2 次修复搞定 |
| 功能符合率 | > 70% | 生成的 App 功能符合用户描述 |
