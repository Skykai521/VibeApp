# 架构设计 | Architecture

本文档描述当前 VibeApp 仓库中的真实模块边界、核心时序和关键职责。它不是理想化蓝图，而是和当前代码结构保持一致的开发参考。

## 1. 架构总览

VibeApp 现在由 4 条主线组成：

1. `presentation`：Compose UI、导航、ViewModel，负责把用户意图转成状态和操作。
2. `feature`：编排 Agent、项目工作区和构建入口，是主要业务层。
3. `data`：Room、DataStore、Repository、网络 API client，为上层提供持久化和模型访问。
4. `build-engine`：设备端编译链，负责把项目目录构建成可安装 APK。

整体关系：

```text
User
  ↓
Compose Screen
  ↓
ViewModel
  ↓
Feature Service / Coordinator
  ↓                    ↓
Repository / API       Project Workspace
  ↓                    ↓
Room / DataStore       build-engine
  ↓                    ↓
Local state            signed.apk
```

## 2. 模块分层

### 2.1 Presentation Layer

目录：

- `app/src/main/kotlin/com/vibe/app/presentation/common`
- `app/src/main/kotlin/com/vibe/app/presentation/theme`
- `app/src/main/kotlin/com/vibe/app/presentation/ui/*`

职责：

- 组织 Compose 页面和导航图
- 管理 `ViewModel` 状态
- 把用户动作映射成 feature 层调用
- 监听构建事件，例如安装 APK、展示构建进度

当前主要页面：

- `startscreen`：启动欢迎页
- `setup`：平台接入与模型配置
- `home`：项目列表与项目入口
- `chat`：对话式生成、构建、导出
- `setting`：平台配置、主题、许可证、关于页

状态模式：

- `MVVM + UDF`
- `StateFlow` / `SharedFlow`
- Compose 使用 `collectAsStateWithLifecycle()`

### 2.2 Feature Layer

目录：

- `app/src/main/kotlin/com/vibe/app/feature/agent`
- `app/src/main/kotlin/com/vibe/app/feature/project`
- `app/src/main/kotlin/com/vibe/app/feature/projectinit`
- `app/src/main/kotlin/com/vibe/app/feature/projecticon`

这一层不负责“存储细节”或“界面渲染”，重点是流程编排。

#### Agent 子系统

核心对象：

- `AgentLoopCoordinator`
- `ProviderAgentGatewayRouter`
- `OpenAiResponsesAgentGateway`
- `AnthropicMessagesAgentGateway`
- `QwenChatCompletionsAgentGateway`
- `AgentToolRegistry`

职责：

- 根据当前模型路由到不同 provider 协议
- 组织多轮 agent loop
- 执行工具调用
- 把工具结果回灌给模型继续生成

当前工具主要位于 `feature/agent/tool/ProjectTools.kt`，包括：

- 读写项目文件
- 列出文件
- 清理构建缓存
- 运行构建流程
- 重命名项目
- 更新项目图标

#### Project 子系统

核心对象：

- `ProjectManager`
- `ProjectWorkspace`
- `ProjectInitializer`
- `ProjectWorkspaceService`

职责：

- 打开并管理项目工作目录
- 从模板生成项目初始结构
- 为 chat / agent 提供项目文件系统访问
- 触发 `build-engine` 进行真实构建

#### Project Icon 子系统

核心对象：

- `ProjectIconRenderer`

职责：

- 生成/更新 launcher icon 相关资源
- 与 agent tool 协同，允许模型写入图标 XML

### 2.3 Data Layer

目录：

- `app/src/main/kotlin/com/vibe/app/data/database`
- `app/src/main/kotlin/com/vibe/app/data/datastore`
- `app/src/main/kotlin/com/vibe/app/data/network`
- `app/src/main/kotlin/com/vibe/app/data/repository`
- `app/src/main/kotlin/com/vibe/app/data/dto`

职责：

- 保存 chat、project、platform 配置
- 存储 API key、主题、用户偏好
- 封装 OpenAI / Anthropic / Google / Qwen 网络调用
- 给上层暴露 repository 抽象

关键子层：

- `database`：Room DB、DAO、Entity、schema
- `datastore`：设置项持久化
- `network`：SSE / HTTP client 和 provider API
- `repository`：给 ViewModel / feature 层提供统一接口

### 2.4 Build Engine

目录：

- `build-engine/src/main/java/com/vibe/build/engine/*`

这是一个独立 Gradle module，用于设备端完整构建链。

当前真实阶段：

```text
RESOURCE → COMPILE → DEX → PACKAGE → SIGN
```

对应实现：

- `Aapt2ResourceCompiler`
- `JavacCompiler`
- `D8DexConverter`
- `AndroidApkBuilder`
- `DebugApkSigner`
- `DefaultBuildPipeline`

支撑对象：

- `BuildWorkspace`
- `BuildWorkspacePreparer`
- `BuildStep`
- `RecordingLogger`
- `BuildResult`
- `CompileInput`

设计特点：

- 每一步通过接口隔离，便于替换和测试
- 构建在项目工作目录内进行，而不是依赖外部 Gradle 构建
- 通过 `BuildResult` 汇总 artifacts、logs 和失败信息
- UI 可订阅构建阶段进度，但无需侵入式重写每个编译器实现

## 3. 关键时序

### 3.1 聊天生成主链路

```text
ChatScreen
  → ChatViewModel
  → ChatRepository / AgentLoopCoordinator
  → Provider gateway
  → Remote model response
  → Message persistence
  → UI state update
```

如果当前平台进入 agent mode，`ChatViewModel` 会通过 `AgentLoopCoordinator` 调用工具读写项目文件和执行构建；否则走普通聊天补全链路。

### 3.2 项目初始化与构建链路

```text
User clicks Run
  → ChatViewModel.runBuild()
  → ProjectInitializer.buildProject(projectId)
  → BuildPipeline.run()
  → RESOURCE / COMPILE / DEX / PACKAGE / SIGN
  → BuildResult
  → emit install event / send build error back to chat
```

这里的工作目录通常是：

```text
/files/projects/{projectId}/app
```

模板初始化、源码写入、图标资源更新和最终 APK 都围绕这个目录组织。

### 3.3 Agent 工具调用链路

```text
Model output tool call
  → AgentLoopCoordinator
  → AgentToolRegistry.findTool(name)
  → ProjectTools implementation
  → Workspace filesystem / build pipeline
  → Tool result JSON
  → model continues reasoning
```

这使得模型不是只“生成一段代码”，而是可以基于当前项目上下文持续修改、构建、修复。

## 4. 当前项目结构映射

```text
app/
  presentation/
    common/
    icons/
    theme/
    ui/
  feature/
    agent/
      loop/
      tool/
    project/
    projecticon/
    projectinit/
  data/
    database/
    datastore/
    dto/
    model/
    network/
    repository/
  di/
  util/

build-engine/
  apk/
  compiler/
  dex/
  internal/
  model/
  pipeline/
  resource/
  sign/
```

## 5. 依赖注入

项目使用 Hilt，将各层解耦：

- `BuildEngineModule`：提供 `BuildPipeline` 及每个构建组件
- `AgentModule`：提供 agent coordinator、tool registry、gateway router
- `DatabaseModule` / `DataStoreModule`：提供 Room 与 DataStore
- `*RepositoryModule`：注入 repository 实现
- `ProjectModule`：注入 project manager / workspace 相关对象

这让 `ViewModel -> feature -> data/build-engine` 的依赖方向始终保持单向。

## 6. 设计原则

当前仓库隐含的几个原则：

1. **尽量在设备端完成完整链路**：生成、编译、签名、安装都在手机上执行。
2. **项目文件系统是第一等公民**：agent 和 build 都围绕 workspace 工作，而不是围绕内存对象工作。
3. **业务编排与编译器实现解耦**：`feature` 负责流程，`build-engine` 负责构建细节。
4. **provider 协议与 agent 能力分离**：不同模型协议由 gateway 适配，工具能力由 registry 提供。
5. **UI 只消费状态和事件**：不直接承担复杂业务逻辑。

## 7. 后续维护建议

更新本文档时，优先保持它和真实目录结构一致，而不是写成理想化架构图。

尤其在以下场景应同步更新：

- 新增 feature 子系统
- 调整 build-engine 阶段
- agent tool 能力发生变化
- repository / storage 边界重构
- 分支协作或发布流程影响开发入口
