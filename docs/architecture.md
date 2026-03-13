# 架构设计 | Architecture

## 整体架构

VibeApp 采用分层架构，每层职责明确、可独立测试：

```
┌─────────────────────────────────────────────┐
│          UI Layer (Compose)                 │  ← 用户看到的一切
├─────────────────────────────────────────────┤
│          Feature API Layer                  │  ← 业务逻辑编排
├─────────────────────────────────────────────┤
│          Build Pipeline                     │  ← 编译引擎
├─────────────────────────────────────────────┤
│          AI Agent Layer                     │  ← 大模型交互
├─────────────────────────────────────────────┤
│          Data Layer                         │  ← 持久化
└─────────────────────────────────────────────┘
```

## 各层详解

### 1. UI Layer

技术选型：Jetpack Compose + Material 3

| 页面 | 职责 |
|------|------|
| ChatScreen | 对话式交互主界面，接收自然语言输入 |
| ProjectListScreen | 多项目管理列表 |
| SettingsScreen | API Key 配置、模型选择 |

架构模式：MVVM + UDF（单向数据流）

```
User Action → ViewModel → Repository → Data Source
                ↓
            UI State → Compose UI
```

### 2. Feature API Layer

核心业务逻辑层，编排 AI 生成 → 预检 → 编译 → 安装的全流程。

#### CodeGen（代码生成）

```
用户输入 → Prompt 组装 → AI 调用 → 响应解析 → 代码提取 → 文件写入
```

关键设计：
- **模板系统**：AI 在预定义骨架内填空，而非从零生成
- **预检机制**：编译前扫描黑白名单，过滤不合法的 API 调用
- **流式输出**：支持 SSE 流式接收 AI 响应，实时展示生成进度

#### FixLoop（自动修复循环）

```
编译失败 → 错误日志清洗 → 构造修复 Prompt → AI 修复 → 重新编译
                                                    ↓
                                            最多重试 3 次
```

错误清洗策略：
- 去除绝对路径，保留相对路径
- 提取关键错误行号和错误类型
- 聚合同类错误，避免 Prompt 过长

### 3. Build Pipeline

独立 Gradle module（`build-engine`），封装完整编译链。

```
           ┌─────────┐
           │ PreCheck│  黑白名单扫描
           └────┬────┘
                ↓
           ┌─────────┐
           │   ECJ   │  .java → .class
           └────┬────┘
                ↓
           ┌─────────┐
           │   D8    │  .class → .dex
           └────┬────┘
                ↓
           ┌─────────┐
           │  AAPT2  │  资源编译 + 打包
           └────┬────┘
                ↓
           ┌──────────┐
           │ ApkSigner│  V1 + V2 签名
           └────┬─────┘
                ↓
           ┌──────────────────┐
           │ PackageInstaller │  引导安装
           └──────────────────┘
```

各组件封装为独立接口，便于单元测试和替换：

```kotlin
interface Compiler {
    suspend fun compile(input: CompileInput): CompileResult
}

interface DexConverter {
    suspend fun convert(classFiles: List<File>): File  // returns .dex
}

interface ResourceCompiler {
    suspend fun compile(resDir: File, manifest: File): File  // returns resources.arsc
}

interface ApkBuilder {
    suspend fun build(dexFile: File, resources: File): File  // returns unsigned.apk
}

interface ApkSigner {
    suspend fun sign(unsignedApk: File): File  // returns signed.apk
}
```

### 4. AI Agent Layer

多模型适配，Provider 模式：

```kotlin
interface AiProvider {
    val name: String
    suspend fun chat(messages: List<Message>, config: AiConfig): Flow<String>
    suspend fun chatComplete(messages: List<Message>, config: AiConfig): String
}

class ClaudeProvider : AiProvider { ... }
class OpenAiProvider : AiProvider { ... }
class DeepSeekProvider : AiProvider { ... }
class OllamaProvider : AiProvider { ... }
```

Prompt 管理：
- System Prompt 存储在 `assets/prompts/` 目录
- 支持模板变量替换（项目名称、已有代码上下文等）
- 白名单 JSON 定义允许使用的 Android SDK API

### 5. Data Layer

| 组件 | 技术 | 用途 |
|------|------|------|
| 项目数据 | Room Database | 项目列表、对话历史、编译记录 |
| 用户配置 | DataStore | API Key、模型选择、偏好设置 |
| 项目文件 | 文件系统 | 生成的源码、编译产物、APK |
| 版本快照 | 文件系统 + DB | 每次编译自动存档源码快照 |

## 关键数据模型

```kotlin
data class Project(
    val id: String,
    val name: String,
    val description: String,
    val packageName: String,
    val createdAt: Long,
    val updatedAt: Long,
    val status: ProjectStatus,  // CREATED, GENERATING, COMPILING, SUCCESS, ERROR
)

data class ChatMessage(
    val id: String,
    val projectId: String,
    val role: Role,             // USER, ASSISTANT, SYSTEM
    val content: String,
    val timestamp: Long,
    val metadata: MessageMetadata?, // 编译结果、错误信息等
)

data class BuildRecord(
    val id: String,
    val projectId: String,
    val status: BuildStatus,    // SUCCESS, ECJ_ERROR, D8_ERROR, AAPT2_ERROR, SIGN_ERROR
    val errorLog: String?,
    val duration: Long,
    val snapshotPath: String?,
    val apkPath: String?,
    val timestamp: Long,
)
```

## 依赖注入

使用 Hilt，主要 Module：

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object AiModule {
    @Provides fun provideAiProviderFactory(): AiProviderFactory
}

@Module
@InstallIn(SingletonComponent::class)
object BuildModule {
    @Provides fun provideCompiler(): Compiler
    @Provides fun provideDexConverter(): DexConverter
    @Provides fun provideBuildPipeline(): BuildPipeline
}

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides fun provideDatabase(): AppDatabase
    @Provides fun provideProjectRepository(): ProjectRepository
}
```

## 线程模型

- UI 层：Main thread
- AI 调用：IO Dispatcher + Flow
- 编译任务：IO Dispatcher + WorkManager（长时间任务）
- 文件操作：IO Dispatcher
