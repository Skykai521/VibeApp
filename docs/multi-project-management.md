# 多项目管理设计文档

## 背景与目标

### 当前状态

VibeApp 目前的 "项目" 概念隐式存在于代码中：

- `ProjectInitializer` 将 `templates.zip` 解压到固定路径 `filesDir/templates/EmptyActivity/app`
- `ProjectWorkspaceService` 是全局单例，所有对话共用一套工作区
- `ChatRoomV2` 是唯一的 "会话" 实体，与构建工作区无绑定关系
- 多条对话在同一个模板目录上并发修改，会互相污染

### 目标

1. 将 HomeScreen 中 "Chats" 改为 "Projects"，FAB 改为 "new project"
2. 点击 "new project" 时，以当前日期（格式 `YYYYMMDD`，如 `20260313`）作为项目名，自动创建并初始化项目工作区
3. 每个项目拥有**独立的磁盘工作区**，互不干扰
4. 每个项目绑定一个主对话（chat），Agent Loop 的工具调用作用于该项目专属目录
5. 项目列表替代聊天列表作为应用主界面

---

## 核心概念

### 项目（Project）

一个 Project 包含：
- **元数据**：名称、创建时间、状态等
- **工作区**：磁盘上的 Android 模板项目目录（`filesDir/projects/{projectId}/app`）
- **关联对话**：唯一绑定的 `ChatRoomV2`（Phase 1 为 1:1 关系）
- **构建状态**：最后一次构建是成功还是失败

### 项目 ID 与命名规则

- **projectId**：系统内部唯一标识，格式为 `YYYYMMDD`，同日创建多个则追加 `_N`（`20260313_2`）
- **name（用户可改）**：初始值等于 projectId，用户可在项目详情中修改

### Project ↔ Chat 关系（Phase 1）

```
Project 1 ──── 1 ChatRoomV2
```

- 每个 Project 绑定一个 ChatRoomV2，从创建起就关联
- ChatRoomV2 的 `title` 初始值等于项目名（`20260313`）
- 未来 Phase 2 可扩展为 1:N（一个项目多条对话分支）

### 工作区隔离

```
filesDir/
├── templates/               ← 原始模板，只读，作为复制源
│   └── EmptyActivity/
│       └── app/
└── projects/                ← 各项目独立工作区（新增）
    ├── 20260313/
    │   └── app/
    └── 20260313_2/
        └── app/
```

---

## 数据模型变更

### 新增实体：`Project`

```kotlin
@Entity(tableName = "projects")
data class Project(
    @PrimaryKey
    @ColumnInfo("project_id")
    val projectId: String,               // "20260313", "20260313_2", ...

    @ColumnInfo("name")
    val name: String,                    // 用户可编辑的显示名称

    @ColumnInfo("chat_id")
    val chatId: Int,                     // FK → ChatRoomV2.id

    @ColumnInfo("workspace_path")
    val workspacePath: String,           // 绝对路径：filesDir/projects/{projectId}/app

    @ColumnInfo("build_status")
    val buildStatus: ProjectBuildStatus, // PENDING / BUILDING / SUCCESS / FAILED

    @ColumnInfo("last_built_at")
    val lastBuiltAt: Long? = null,

    @ColumnInfo("created_at")
    val createdAt: Long = System.currentTimeMillis() / 1000,

    @ColumnInfo("updated_at")
    val updatedAt: Long = System.currentTimeMillis() / 1000,
)

enum class ProjectBuildStatus {
    PENDING,   // 刚创建，尚未执行首次构建
    BUILDING,  // 初始化构建进行中
    SUCCESS,   // 最近一次构建成功
    FAILED,    // 最近一次构建失败
}
```

### 新增视图：`ProjectWithChat`

```kotlin
data class ProjectWithChat(
    val project: Project,
    val chat: ChatRoomV2,
    val lastMessage: MessageV2?,          // 列表中的预览内容
)
```

### 数据库版本升级

`ChatDatabaseV2` 版本从 2 → 3，新增 `projects` 表。

---

## 接口定义

### ProjectRepository

```kotlin
interface ProjectRepository {
    // 获取所有项目（按 updatedAt 倒序）
    suspend fun fetchProjects(): List<ProjectWithChat>

    // 按 projectId 查询
    suspend fun fetchProject(projectId: String): ProjectWithChat?

    // 通过 chatId 反查 Project（供 ChatViewModel / AgentTools 使用）
    suspend fun fetchProjectByChatId(chatId: Int): Project?

    // 判断某个 projectId 是否已存在
    suspend fun projectExists(projectId: String): Boolean

    // 持久化项目元数据（insert or replace）
    suspend fun saveProject(project: Project)

    // 更新构建状态
    suspend fun updateBuildStatus(projectId: String, status: ProjectBuildStatus, lastBuiltAt: Long? = null)

    // 更新显示名称
    suspend fun renameProject(projectId: String, name: String)

    // 删除项目（级联删除 chat 与 messages）
    suspend fun deleteProject(projectId: String)

    // 搜索（按项目名 / 消息内容）
    suspend fun searchProjects(query: String): List<ProjectWithChat>
}
```

### ProjectManager

负责项目完整生命周期：创建工作区、初始化、提供 workspace 对象。

```kotlin
interface ProjectManager {
    /**
     * 创建一个新项目：
     * 1. 计算唯一 projectId（YYYYMMDD，冲突时加后缀）
     * 2. 在 DB 中创建 Project + ChatRoomV2 记录（buildStatus=PENDING）
     * 3. 启动后台初始化（拷贝模板 + 首次构建）
     * 4. 返回已持久化的 Project（供 UI 立即导航使用）
     *
     * 初始化进度通过 [observeProject] 的 Flow 通知 UI。
     */
    suspend fun createProject(
        enabledPlatforms: List<String>,
        name: String? = null,          // null 时使用日期规则自动生成
    ): Project

    /**
     * 打开已有项目并返回其 workspace（幂等，不触发重建）
     */
    suspend fun openWorkspace(projectId: String): ProjectWorkspace

    /**
     * 观察某个项目状态变化（build status / name 更新）
     */
    fun observeProject(projectId: String): Flow<ProjectWithChat?>

    /**
     * 删除项目（磁盘 + DB）
     */
    suspend fun deleteProject(projectId: String)

    /**
     * 生成唯一 projectId：优先 YYYYMMDD，冲突加 _N
     */
    suspend fun generateProjectId(date: LocalDate = LocalDate.now()): String
}
```

### ProjectWorkspace

替代当前 `ProjectWorkspaceService`，每个 Project 实例化一个，作用域绑定 `projectId`。

```kotlin
interface ProjectWorkspace {
    val projectId: String
    val project: Project

    /** 读取工作区内的文本文件（相对路径） */
    suspend fun readTextFile(relativePath: String): String

    /** 写入工作区内的文本文件（相对路径，自动创建中间目录） */
    suspend fun writeTextFile(relativePath: String, content: String)

    /** 运行构建流水线，返回结构化结果 */
    suspend fun buildProject(): BuildResult

    /** 解析相对路径为绝对文件（含路径越界校验） */
    suspend fun resolveFile(relativePath: String): File

    /** 返回工作区根目录（appModuleDir） */
    val rootDir: File
}
```

### ProjectInitializer（重构）

在现有 `ProjectInitializer` 基础上增加支持多项目的重载，保持向后兼容。

```kotlin
interface ProjectInitializer {
    /**
     * 为指定 projectId 初始化工作区：
     * 1. 拷贝模板到 filesDir/projects/{projectId}/
     * 2. 替换占位符
     * 3. 执行首次构建
     *
     * forceReset=true 时重置已有目录。
     */
    suspend fun initProject(
        projectId: String,
        forceReset: Boolean = false,
    ): BuildResult

    /**
     * 确保项目目录存在（不触发构建），返回 TemplateProject 描述
     */
    suspend fun ensureProject(projectId: String): TemplateProject

    /**
     * 在已有工作区上执行构建
     */
    suspend fun buildProject(projectId: String): BuildResult
}
```

### ProjectListViewModel（新增）

替代现有 `HomeViewModel`（或重命名后逐步迁移）。

```kotlin
interface ProjectListViewModel {
    val uiState: StateFlow<ProjectListUiState>

    // 刷新项目列表
    fun refresh()

    // 创建新项目（触发初始化，导航事件通过 uiState.navigationEvent 通知）
    fun createNewProject(enabledPlatforms: List<String>)

    // 搜索
    fun updateSearchQuery(query: String)

    // 批量删除
    fun deleteSelectedProjects()

    // 重命名
    fun renameProject(projectId: String, newName: String)

    // 选择模式
    fun toggleSelectionMode()
    fun selectProject(idx: Int)
}

data class ProjectListUiState(
    val projects: List<ProjectWithChat> = emptyList(),
    val isSelectionMode: Boolean = false,
    val isSearchMode: Boolean = false,
    val selectedProjects: List<Boolean> = emptyList(),
    val creationState: ProjectCreationState = ProjectCreationState.Idle,
    val navigationEvent: NavigationEvent? = null,
)

sealed interface ProjectCreationState {
    data object Idle : ProjectCreationState
    data object Creating : ProjectCreationState          // DB 写入中
    data class Initializing(val projectId: String) : ProjectCreationState  // 模板拷贝 + 构建中
    data class Failed(val message: String) : ProjectCreationState
}

sealed interface NavigationEvent {
    data class OpenProject(val chatId: Int, val enabledPlatforms: List<String>) : NavigationEvent
}
```

---

## Agent Tool 变更

`AgentToolContext` 需要增加 `projectId`，供工具定位正确的工作区：

```kotlin
data class AgentToolContext(
    val chatId: Int,
    val platformUid: String,
    val iteration: Int,
    val projectId: String,    // 新增：关联的项目 ID
)
```

`ChatViewModel` 在启动 agent loop 时，通过 `ProjectRepository.fetchProjectByChatId(chatId)` 查找 `projectId` 并注入。

`ProjectTools` 中的三个工具通过 `AgentToolContext.projectId` 从 `ProjectManager.openWorkspace(projectId)` 获取当前 workspace，不再依赖全局单例 `ProjectWorkspaceService`。

---

## 屏幕与导航变更

### 导航路由新增

```kotlin
object Route {
    // 现有路由保持不变...

    // 新增：项目列表（取代 CHAT_LIST 成为主页面）
    const val PROJECT_LIST = "project_list"

    // 可选：项目详情/设置
    const val PROJECT_SETTINGS = "project_settings/{projectId}"
}
```

> 为兼容已有导航图，`CHAT_LIST` 路由暂时保留并重定向到 `PROJECT_LIST`。

### HomeScreen 改动

| 位置 | 改动前 | 改动后 |
|---|---|---|
| TopAppBar 标题 | "Chats" | "Projects" |
| 大标题 `ChatsTitle` | "Chats" | "Projects" |
| FAB 图标文字 | "new chat" | "new project" |
| FAB 点击行为 | 弹选平台对话框 / 直接导航 | 直接创建项目（无弹窗）→ 显示 `Initializing` 状态 |
| 列表 item | 显示 ChatRoomV2 标题 | 显示 Project 名称 + 构建状态 badge |
| 点击 item | 进入聊天页 | 进入对应聊天页 |
| 长按 item | 进入选择模式批量删除 | 同上（删除项目 = 删除 chat + 工作区） |

### 新项目创建流程

```
用户点击 "new project"
    │
    ▼
ProjectListViewModel.createNewProject()
    │
    ├─ 计算 projectId（当前日期 YYYYMMDD，冲突加 _N）
    ├─ DB: 创建 Project (PENDING) + ChatRoomV2
    ├─ 发送 NavigationEvent.OpenProject（立即导航进入聊天页）
    │
    └─ 后台（coroutineScope）：
         ├─ 拷贝模板到 projects/{projectId}/
         ├─ 替换占位符
         ├─ 执行首次构建
         └─ 更新 DB buildStatus (SUCCESS / FAILED)

聊天页加载时：
    ├─ 若 buildStatus == PENDING / BUILDING → 显示 "项目初始化中..." banner
    └─ 若 buildStatus == SUCCESS → 正常可用
```

> **为什么先导航再构建？**
> 首次构建在低端机上可能需要 5-30 秒。立即导航进入聊天页，用户可以开始输入需求，构建在后台完成。收到构建完成事件后刷新 UI 状态。

### 项目列表 Item 设计

```
┌─────────────────────────────────────────┐
│  📦 20260313                  [SUCCESS] │
│  "帮我做一个简单的计算器 App"           │
│                              2026/3/13  │
└─────────────────────────────────────────┘
```

- 项目名（粗体）
- 最后一条消息预览
- 构建状态 badge（SUCCESS 绿 / FAILED 红 / BUILDING 旋转 / PENDING 灰）
- 右侧时间戳

---

## Phase 1 实施范围

本次只实现 Phase 1（UI + 基础数据层），不做完整工作区隔离：

### 包含
- [ ] DB 迁移：新增 `projects` 表（version 3）
- [ ] `Project`、`ProjectBuildStatus`、`ProjectWithChat` 数据类
- [ ] `ProjectDao` 基础 CRUD
- [ ] `ProjectRepository` 接口 + `ProjectRepositoryImpl`
- [ ] `ProjectManager` 接口 + `DefaultProjectManager`
  - 生成唯一 projectId
  - 创建 Project + ChatRoomV2 记录
  - 调用 `ProjectInitializer.initProject()` 逻辑初始化工作区
- [ ] `ProjectWorkspace` 接口 + `DefaultProjectWorkspace`
  - 路径绑定到 `filesDir/projects/{projectId}/app`
- [ ] `ProjectInitializer` 重构以支持指定 `projectId` 路径
- [ ] `ProjectListViewModel`（取代 `HomeViewModel`）
- [ ] HomeScreen UI 文字 + FAB 改动
- [ ] `AgentToolContext` 新增 `projectId`
- [ ] `ProjectTools` 改为通过 `ProjectManager.openWorkspace()` 获取 workspace

### 暂不包含
- 项目重命名 UI（数据层已预留接口）
- 项目详情/设置页（`PROJECT_SETTINGS` 路由）
- 每个 chat 独立 workspace（Phase 1 仍然 1:1）
- 项目导出（APK 分享）

---

## 与现有代码的兼容策略

| 现有代码 | 处理方式 |
|---|---|
| `ProjectWorkspaceService` | 保留为兼容层，内部委托到 `ProjectManager.openWorkspace()` |
| `ProjectInitializer` | 在现有方法上新增带 `projectId` 参数的重载 |
| `ChatRepository` | 不改动，Project 通过 `chatId` 与之关联 |
| `AgentTools` | 注入 `ProjectManager` 替换 `ProjectWorkspaceService` |
| `HomeViewModel` | 重命名为 `ProjectListViewModel`，扩展字段 |
| `Route.CHAT_LIST` | 保留，NavGraph 中映射到 `PROJECT_LIST` 组合体 |

---

## 文件路径规划

```
app/src/main/kotlin/com/vibe/app/
├── data/
│   ├── database/
│   │   ├── ChatDatabaseV2.kt              ← 版本升到 3，新增 Project entity
│   │   ├── entity/
│   │   │   └── Project.kt                 ← 新增
│   │   └── dao/
│   │       └── ProjectDao.kt              ← 新增
│   └── repository/
│       ├── ProjectRepository.kt           ← 新增接口
│       └── ProjectRepositoryImpl.kt       ← 新增实现
│
├── feature/
│   └── project/                           ← 新增模块目录
│       ├── ProjectManager.kt              ← 接口
│       ├── DefaultProjectManager.kt       ← 实现
│       ├── ProjectWorkspace.kt            ← 接口
│       └── DefaultProjectWorkspace.kt     ← 实现
│
├── presentation/
│   └── ui/
│       └── home/
│           ├── HomeScreen.kt              ← 文字改动 + FAB 行为
│           ├── HomeViewModel.kt           ← 扩展为 ProjectListViewModel
│           └── ProjectListItem.kt         ← 新增列表 item 组合体
│
└── di/
    └── ProjectModule.kt                   ← 新增 DI 绑定
```
