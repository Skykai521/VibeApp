# Debug Diagnostic Viewer

> 在对话页面内提供可视化的 DiagnosticEvent 日志查看器，通过设置页面开关控制显隐。

## 概述

本功能在 Settings 中新增「开发者选项」分区，提供调试日志开关。开启后，Chat 页面的 More Options 菜单中出现「调试日志」入口，点击进入独立的 DiagnosticScreen，展示当前对话的诊断事件日志。

页面采用**顶部摘要卡片 + 事件时间线**布局，摘要卡片展示 Context 大小、压缩状态等关键指标，时间线按时间倒序展示 DiagnosticEvent 卡片，支持展开查看 payload 详情。

## 设计

### 1. 开关

- **位置**：Settings 页面，平台列表与关于页之间，新增「开发者选项」分区
- **控件**：`SettingItem` 行，标题「调试日志」，描述「在对话页面启用诊断日志查看器」，右侧 Switch
- **持久化**：DataStore，key 为 `booleanPreferencesKey("debug_mode")`
- **接口变更**：`SettingDataSource` 新增 `updateDebugMode(enabled: Boolean)` / `getDebugMode(): Boolean`
- **Repository 层**：`SettingRepository` 新增对应方法，`SettingRepositoryImpl` 实现

### 2. 入口

- 开关开启时，`ChatDropdownMenu` 最前面新增菜单项「调试日志」（`Icons.Outlined.BugReport`）
- 点击导航到 `DiagnosticScreen`，传当前 `chatRoomId`
- 开关关闭时菜单项不显示

### 3. 数据流

```
DataStore(debug_mode)
  → SettingRepository
    → ChatViewModel.isDebugEnabled: StateFlow<Boolean>   // 控制菜单项显隐
    → SettingViewModelV2                                  // Settings UI 渲染 Switch
```

### 4. 导航

- 新增路由：`Route.DIAGNOSTIC = "diagnostic/{chatRoomId}"`，参数类型 `NavType.IntType`
- `NavigationGraph.kt` 新增 `diagnosticNavigation()` composable 目标
- `DiagnosticScreen` 通过 `hiltViewModel()` 获取 ViewModel，ViewModel 从 `SavedStateHandle` 读取 `chatRoomId`

### 5. DiagnosticScreen 页面结构

```
Scaffold
├── TopAppBar（标题「调试日志」+ 返回按钮）
└── Content
    ├── SummaryCard（固定顶部）
    │   ├── 预估 Context 大小（tokens）
    │   ├── 是否进行过上下文压缩（是/否 + 压缩策略名）
    │   ├── 总事件数
    │   ├── 错误/警告数量
    │   └── 日志文件大小
    └── LazyColumn（事件时间线，按时间倒序）
        └── DiagnosticEventCard × N
            ├── 左侧：category 图标 + level 颜色指示条
            ├── 中间：summary 文本 + 时间戳
            └── 点击展开：payload 格式化显示
```

### 6. DiagnosticViewModel

- 注入 `ChatDiagnosticLogger`
- 通过 `readChatLog(chatId)` 读取 NDJSON，解析为 `List<DiagnosticEvent>`
- 暴露 `uiState: StateFlow<DiagnosticUiState>`，包含 `summaryInfo` + `events`
- summary 数据从 events 聚合计算（一次遍历提取 token 估算、压缩事件、error/warn 计数）

### 7. 事件卡片可视化

#### Category 图标与颜色

| Category           | 图标            | 颜色基调                 |
|--------------------|-----------------|--------------------------|
| `CHAT_TURN`        | `Chat`          | Primary                  |
| `MODEL_REQUEST`    | `Upload`        | Secondary                |
| `MODEL_RESPONSE`   | `Download`      | Secondary                |
| `LATENCY_BREAKDOWN`| `Timer`         | Tertiary                 |
| `BUILD_RESULT`     | `Build`         | 成功绿 / 失败红         |
| `AGENT_TOOL`       | `Construction`  | Tertiary                 |
| `AGENT_LOOP`       | `Loop`          | Primary                  |

#### Level 颜色指示

- `INFO` — `MaterialTheme.colorScheme.outline`（低调灰）
- `WARN` — 黄/琥珀色
- `ERROR` — `MaterialTheme.colorScheme.error`（红色）

#### 卡片展开态

- **payload** JSON 格式化输出，Monospace 字体，语法着色（key: primary, string: secondary, number: tertiary）
- `MODEL_REQUEST` / `MODEL_RESPONSE`：额外提取 provider、model、token 数量
- `LATENCY_BREAKDOWN`：各阶段耗时文字列表
- `AGENT_LOOP` 且 action 为 `conversation_compaction`：标注压缩策略和 items 变化

#### 摘要卡片数据聚合逻辑

- **Context 大小**：取最后一个 `MODEL_REQUEST` 事件 payload 中的 `estimatedTokens`，或从 `conversation_compaction` 事件取
- **是否压缩**：检查是否存在 action 为 `conversation_compaction` 的 `AGENT_LOOP` 事件；若有，显示最近一次策略名
- **总事件数 / 错误数 / 警告数**：直接计数
- **日志大小**：从 `DiagnosticLogSnapshot` 的 content 长度近似

## 文件变更清单

### 新增

| 文件                                                   | 用途                                    |
|--------------------------------------------------------|-----------------------------------------|
| `presentation/ui/diagnostic/DiagnosticScreen.kt`       | 调试页面 Composable                     |
| `presentation/ui/diagnostic/DiagnosticViewModel.kt`    | 页面 ViewModel，解析日志、聚合摘要      |
| `presentation/ui/diagnostic/DiagnosticUiState.kt`      | UI 状态模型（SummaryInfo + events 列表）|

### 修改

| 文件                                          | 变更内容                                         |
|-----------------------------------------------|--------------------------------------------------|
| `data/datastore/SettingDataSource.kt`         | 新增 `updateDebugMode()` / `getDebugMode()`      |
| `data/datastore/SettingDataSourceImpl.kt`     | 实现 debug_mode 读写                             |
| `data/repository/SettingRepository.kt`        | 新增 `getDebugMode()` / `updateDebugMode()`      |
| `data/repository/SettingRepositoryImpl.kt`    | 实现接口                                         |
| `presentation/ui/setting/SettingScreen.kt`    | 新增「开发者选项」分区 + Switch                   |
| `presentation/ui/setting/SettingViewModelV2.kt`| 新增 debugMode state + toggle 方法               |
| `presentation/ui/chat/ChatScreen.kt`          | `ChatDropdownMenu` 新增调试日志菜单项 + 导航回调 |
| `presentation/ui/chat/ChatViewModel.kt`       | 新增 `isDebugEnabled: StateFlow<Boolean>`         |
| `presentation/common/Route.kt`               | 新增 `DIAGNOSTIC` 路由常量                        |
| `presentation/common/NavigationGraph.kt`      | 新增 `diagnosticNavigation()`                    |
| `res/values/strings.xml`                      | 新增相关字符串资源                                |

### 不变

- `feature/diagnostic/` — 已有的 `ChatDiagnosticLogger`、`DiagnosticModels.kt` 仅被读取，不修改
- `build-engine/` — 不涉及
