# Continuous Iteration — Design Spec

> Phase 3 的第三项：让用户通过多轮对话持续打磨同一个生成的 App，不再每轮都从零开始。

## 1. 背景与目标

VibeApp 当前在"从零生成一个 App"场景下工作良好，但在"继续改一个已有 App"场景下暴露出四类痛点：

- **D. 增量意图失真**（最痛）——用户的需求是"再加一个页面"这种增量，但 agent 按新建 App 的流程处理，rename、create_plan、全量 list_project_files、重读代码，消耗大量 turn 才回到正题。
- **A. 跨会话冷启动** —— 隔天回到同一个项目，agent 不记得上次的设计决策和功能边界，容易把之前的特性改坏。
- **C. 不可逆** —— 用户一句"再调一下颜色"可能把别的东西顺手改坏，没有可靠的撤销路径。
- **B. 同会话漂移** —— 长对话里 agent 重复读文件、忘早先约束（已被 context compaction 缓解一部分，本设计不单独处理）。

优先级共识：**D > A > C > B**。本设计聚焦 D / A / C，B 被 MEMO 注入和迭代模式压缩策略顺带解决。

**重要前置发现**：README 中声称已完成的 "version snapshots" 功能**实际并未实现**——`ProjectManager` 只提供 create/open/delete/observe，无任何 snapshot / version 相关 API。本设计必须把"快照子系统"作为前置基础设施从零建立。

## 2. 设计总览

四个新子系统，松耦合通过 `AgentLoopCoordinator` 的 turn lifecycle 装配：

| 子系统 | 位置 | 责任 |
|---|---|---|
| **Snapshot** | `app/feature/project/snapshot/` | 每轮自动快照 + 还原；与未来手动快照共用存储 |
| **MEMO** | `app/feature/project/memo/` | 两层项目记忆：确定性 outline + LLM 维护的 intent |
| **Iteration Mode** | `app/feature/agent/loop/iteration/` | 检测迭代模式，装配不同 prompt 与行为策略 |
| **Turn Lifecycle 集成** | `app/feature/agent/loop/` | 把前三者串进 Coordinator 的每轮执行 |

依赖关系（单向，避免循环）：

```
SnapshotManager ──────┐
OutlineGenerator ─────┤
IntentStore ──────────┼──► AgentLoopCoordinator
IterationModeDetector ┘
```

运行时关键路径（用户发一条消息 → 完成一轮迭代）：

1. **PREPARE** `Coordinator` 调 `IterationModeDetector.detect(projectId)`，iterate 模式加载 MEMO 注入 system prompt
2. **PENDING_SNAP** 预先准备一个 snapshot handle，但不落盘
3. **TOOL LOOP** agent 正常工具循环；`WriteInterceptor` 在首次 write/edit/rename/icon 工具调用时触发 snapshot 真正落盘
4. **FINALIZE** build 成功 → 重新生成 outline.json；不论成败都 finalize snapshot、执行 retention、发诊断事件

## 3. Snapshot 子系统

### 3.1 数据模型

```kotlin
// feature/project/snapshot/SnapshotModels.kt
data class Snapshot(
    val id: String,                 // "snap_20260411_143012_a1b2"
    val projectId: String,
    val type: SnapshotType,         // TURN | MANUAL
    val createdAt: Instant,
    val turnIndex: Int?,            // TURN 专属
    val label: String,              // TURN 默认 = 用户 prompt 前 40 字；MANUAL = 用户手填
    val parentSnapshotId: String?,  // 链式溯源
    val buildSucceeded: Boolean,
    val affectedFiles: List<String>,
    val deletedFiles: List<String>
)

enum class SnapshotType { TURN, MANUAL }
```

### 3.2 存储布局

```
files/projects/{projectId}/.vibe/
└── snapshots/
    ├── index.json                         # 全部 snapshot 元数据，原子替换
    ├── snap_20260411_143012_a1b2/
    │   ├── manifest.json                  # 本快照的全量文件清单
    │   └── files/
    │       └── app/src/main/java/.../MainActivity.java
    └── snap_20260411_145533_c3d4/
        └── ...
```

**存储策略：每次快照都存完整文件集**（不用内容寻址、不用增量 diff）。理由：
- 单个 App 平均 15~30 个源文件，单快照 ~100KB；20 轮 retention → 每项目 ~2-4MB，可接受
- restore 是一次性覆盖，无需 replay 链条，逻辑极简
- 未来若项目体积超阈值（>10MB）再降级到增量存储（设计时预留 `StorageStrategy` 接口占位，Phase 3.5 再实现）

`index.json` 是单一真相源。Room 侧的 `SnapshotIndexDao` 只是它的读缓存，用于 UI 快速列表，启动时从 index.json 重建以避免不一致。

### 3.3 核心 API

```kotlin
interface SnapshotManager {
    /** 准备一个未落盘的 handle；真正 dump 在首次 write 工具命中时才触发 */
    suspend fun prepare(
        projectId: String,
        type: SnapshotType,
        label: String,
        turnIndex: Int? = null
    ): SnapshotHandle

    suspend fun restore(snapshotId: String): RestoreResult

    suspend fun list(projectId: String): List<Snapshot>

    suspend fun delete(snapshotId: String)

    /** 清理 TURN 类型超出 retention 的旧快照，MANUAL 不动 */
    suspend fun enforceRetention(projectId: String, keepTurnCount: Int = 20)
}

interface SnapshotHandle {
    suspend fun commit()    // 把当前 workspace 全量 dump 到盘
    suspend fun finalize(
        buildSucceeded: Boolean,
        affectedFiles: List<String>,
        deletedFiles: List<String>
    )
}

data class RestoreResult(
    val restoredFiles: List<String>,
    val deletedFiles: List<String>,
    val backupSnapshotId: String    // 还原动作本身打一个 MANUAL 快照作为备份
)
```

### 3.4 Restore 语义

因为每次都存完整文件集，restore 极简：
1. 先打一个 MANUAL 快照（label="撤销前状态"）作为备份
2. 读目标 snapshot 的 manifest.json
3. 把 snapshot 里的全部文件覆写回 workspace
4. 删除 workspace 中存在但 snapshot 中不存在的文件
5. 通过 `.vibe/snapshots/.pending_restore` 标记位保护崩溃恢复：先写临时目录 → 文件系统 rename 替换 → 更新 index.json；启动时检查标记位，未完成则回滚

## 4. MEMO 子系统

### 4.1 Layer A — Outline（确定性层）

**生成时机**：每轮 build 成功后由 Coordinator 同步触发。构建失败的 turn 不更新 outline（保留上次成功状态）。

**存储**：`files/projects/{projectId}/.vibe/memo/outline.json`

```json
{
  "generatedAt": "2026-04-11T14:30:12Z",
  "appName": "Weather Station",
  "packageName": "com.user.weatherstation",
  "activities": [
    { "name": "MainActivity", "layout": "activity_main", "purpose": "主界面：搜索 + 当前天气" }
  ],
  "files": [
    {
      "path": "src/main/java/.../MainActivity.java",
      "classes": ["MainActivity extends ShadowActivity"],
      "methods": ["onCreate", "onSearchClick", "fetchWeather", "updateUi"],
      "viewIds": ["et_city", "btn_search", "tv_temperature"]
    }
  ],
  "permissions": ["android.permission.INTERNET"],
  "stringKeys": ["app_name", "hint_city"],
  "recentTurns": [
    { "turnIndex": 3, "userPrompt": "加一个 7 天预报页", "changedFiles": 4, "buildOk": true }
  ]
}
```

**生成器 `OutlineGenerator`** 复用现有 `list_project_files` 的符号抽取逻辑。`purpose` 字段用启发式从 layout id、activity 名、最近 turn 的 user prompt 推断，**不调 LLM**，零 token 成本。

### 4.2 Layer B — Intent（LLM 层）

**存储**：`files/projects/{projectId}/.vibe/memo/intent.md`，硬上限 3~10 行：

```markdown
<!-- Maintained by AI, edited by you -->
# Weather Station

**Purpose**: 用户输入城市名，展示当前天气和 7 天预报，支持收藏常用城市。

**Key Decisions**:
- 数据源：wttr.in（无需 API key，Jsoup 直接抓）
- 存储：SharedPreferences 存收藏城市列表
- 导航：双 Activity，不用 Fragment

**Known Limits**:
- 不支持离线缓存
- 温度只展示摄氏度
```

**更新方式**：新增 agent 工具

```kotlin
tool: update_project_intent
  purpose: String                  // ≤80 字
  keyDecisions: List<String>       // ≤60 字 × 最多 5 项
  knownLimits: List<String>        // ≤60 字 × 最多 5 项
```

调用时机：
- **新建项目**：第一次 build 成功后（prompt 硬要求）
- **迭代**：仅当用户需求引入新决策或新限制时追加/修改相关条目，不做整体重写

**不做兜底**：agent 偷懒不更新不做 nag，先观察实际失败率。

### 4.3 注入方式

turn 开始时（iterate 模式），Coordinator 把两层合并成 `<project-memo>` 块注入 system prompt：

```
<project-memo>
## Intent
{{intent.md 原文}}

## Outline
- Activities: MainActivity (主界面), ForecastActivity (7 天预报)
- Files: 8 个 Java + 5 个 layout + strings(12) + colors(6)
- Recent: 第 3 轮加了预报页；第 4 轮调字体；第 5 轮加收藏
</project-memo>
```

outline.json 不整个塞——Coordinator 做 prompt-aware 压缩：只写 activities、recentTurns、permissions、文件数量。具体 methods/viewIds 留给 agent 真需要时走 `grep_project_files` 按需拉。

**token 预算**：intent.md ≈ 150 tokens；outline 摘要 ≈ 200 tokens；每轮固定注入 ~350 tokens。对比现状 turn 1 平均读 5~10 个文件（3000+ tokens），节省一个量级。

### 4.4 可见性

- **intent.md**：用户可见、可编辑（项目设置里的"项目记忆"面板）。agent 下一轮会尊重用户的编辑。
- **outline.json**：对用户隐藏，纯内部状态。
- **两层均不进 APK**：`.vibe/` 目录在打包管线中被忽略。

## 5. Iteration Mode 子系统

### 5.1 检测

```kotlin
class IterationModeDetector(
    private val intentStore: IntentStore,
    private val snapshotManager: SnapshotManager
) {
    suspend fun detect(projectId: String): AgentMode {
        val hasIntent = intentStore.exists(projectId)
        val hasSuccessfulTurn = snapshotManager.list(projectId)
            .any { it.type == SnapshotType.TURN && it.buildSucceeded }
        return if (hasIntent && hasSuccessfulTurn) AgentMode.ITERATE
               else AgentMode.GREENFIELD
    }
}

enum class AgentMode { GREENFIELD, ITERATE }
```

两个条件同时成立才算 iterate：有 intent.md **且**至少一次成功构建。避免半成品误判。

**无 UI 入口**——用户继续发言即隐式切换。

### 5.2 行为分支对照

| 维度 | GREENFIELD | ITERATE |
|---|---|---|
| System prompt | 现有全量 | 基础部分 + `<project-memo>` + 迭代附录 |
| Phased Workflow | Inspect → Rename → Write → Build → Verify | 优先读 memo 而非全量 list_project_files |
| create_plan | 复杂请求自动建议 | 仅当触碰 ≥3 个新文件时建议；单点修改直接跳过 |
| rename_project | 可用 | **照常可用**（不限制对话式改名） |
| update_project_intent | 强制在 build 成功后调用 | 仅引入新决策/限制时调用 |
| 默认起点 | agent 调 `list_project_files` 全量探索 | memo 已在 system prompt 注入，agent 直接基于 memo 规划；`get_project_memo` 工具仅用于 compaction 后 re-fetch |
| Context compaction | 现有策略 | 新 `IterationCompactionStrategy`：pin memo + 原始用户需求 |

### 5.3 迭代附录（注入 system prompt 尾部）

```markdown
## Iteration Mode

You are continuing an existing app. The `<project-memo>` above reflects the current
state — trust it as the starting point, don't re-explore unless memo is insufficient.

### Rules in iteration mode

- **Treat the user's message as a delta**, not a full spec. Do the minimum to satisfy it.
- **Skip create_plan** unless the change touches 3+ new files.
- **Preserve what's not asked to change** — don't refactor, retheme, or "improve" code
  the user didn't mention. Surgical edits only.
- **update_project_intent only if needed** — if the change introduces a new external
  dependency, a new architectural choice, or a new known limit, update the intent.
  Otherwise leave it alone.

### Starting a turn

1. The memo above already tells you the file layout, activities, recent turns, and intent.
2. Decide which files you need to modify based on the user's request + memo.
3. Use `grep_project_files` + `read_project_file` (with line ranges) to pull just those files.
4. Never read a file "just to be safe" — memo + grep is enough to plan the edit.
5. Edit → Build → (Verify if task warrants).
```

### 5.4 新工具

```kotlin
tool: get_project_memo
  // 无参数
  returns: String  // 拼装好的 <project-memo> 文本
```

正常情况 memo 已在 turn 开始注入 system prompt，此工具仅兜底两种场景：(1) 长对话 compaction 后 agent 记忆模糊重新拉取；(2) agent 自觉需要重新确认项目状态。

### 5.5 迭代模式 Context Compaction 策略

新增 `IterationCompactionStrategy`（具体实现留到 plan 阶段）：

- **永远保留**：对话第一条用户需求、`<project-memo>` 系统消息、最近 2 轮完整 user/assistant 交互
- **优先丢弃**：`read_project_file` 中间结果、`grep_project_files` 搜索结果、failed build 的完整错误输出
- **保留摘要**：每轮结束后生成一行"第 N 轮：{prompt 前 30 字} → 改了 X 个文件 → build {ok|fail}"，累加到 system 消息末尾

## 6. Turn Lifecycle 集成

```
onUserMessage(projectId, text)
  │
  ▼
[1. PREPARE]
  mode = IterationModeDetector.detect(projectId)
  memo = if (mode == ITERATE) MemoLoader.load(projectId) else null
  systemPrompt = PromptAssembler.assemble(mode, memo)
  │
  ▼
[2. PENDING_SNAP]
  snapshotHandle = SnapshotManager.prepare(
      projectId, type=TURN,
      label=text.take(40),
      turnIndex=nextTurnIndex()
  )
  // 不落盘；绑定到工具调用监听
  │
  ▼
[3. TOOL LOOP]
  正常 agent 工具循环
  ★ WriteInterceptor 在 write/edit/rename/icon 工具
    第一次命中时调用 snapshotHandle.commit()
    → dump 当前 workspace 全量文件集
  │
  ▼
[4. FINALIZE]
  if (buildSucceeded) OutlineGenerator.regenerate(projectId)
  snapshotHandle.finalize(buildSucceeded, affectedFiles, deletedFiles)
  SnapshotManager.enforceRetention(projectId, keepTurnCount=20)
  DiagnosticLogger.emit(TurnCompleted(...))
```

**关键不变量**：

- **Snapshot lazy commit**：只有真改动文件的 turn 才产生 snapshot 条目，纯对话/只读 turn 零污染
- **Snapshot = 改动前状态**：首次 write 之前 dump，所以 undo 第 N 轮 = 回到第 N-1 轮结束时的状态
- **Outline 全量重生成**：不做增量维护，降低复杂度
- **失败也 finalize**：若 handle 已 commit（发生过写），build 失败/中断/网络错误都走 finalize，snapshot 标 `buildSucceeded=false`，outline 不更新；若从未 commit（纯对话/只读 turn），handle 直接丢弃，无痕

## 7. 用户 UX

### 7.1 Chat 每轮底部撤销条

```
┌─────────────────────────────────────────────────┐
│ AI: 已加好 7 天预报页面，并更新了主页跳转按钮     │
│     ✅ Build 成功                                │
│ ─────────────────────────────────────────────── │
│ 第 3 轮 · 改动 4 个文件                  ↩ 撤销 │
└─────────────────────────────────────────────────┘
```

点"撤销" → 确认框 → `restore()` → chat 追加系统消息 "已撤销第 3 轮改动 · [重做]"。

### 7.2 项目记忆面板（项目设置里）

展示 intent.md（outline.json 隐藏）。"编辑"进入纯文本编辑器，保存直接覆盖 intent.md，下一轮 iterate 使用用户编辑后的版本。

### 7.3 快照历史面板（项目菜单里）

```
┌─ 历史版本 ─────────────────────────────────────┐
│ ⦿ 当前                                          │
│ ─ 第 5 轮 · 2 分钟前 · "调整配色"       [回到] │
│ ─ 第 4 轮 · 5 分钟前 · "加收藏城市"     [回到] │
│ ★ 收藏前版本 · 10 分钟前 (手动)         [回到] │
│ ─ 第 3 轮 · 12 分钟前 · "加 7 天预报"   [回到] │
└────────────────────────────────────────────────┘
```

TURN 和 MANUAL 混排，图标区分（⦿ / ★）。TURN 超 20 个自动清理，MANUAL 永久保留直到用户删除。

## 8. 错误处理与边界

| 场景 | 行为 |
|---|---|
| 用户手编 intent.md 后 agent 又调 update | intent.md 顶部注释提示 agent 尊重编辑；agent 会做最小改动 merge 但仍可能丢细节；先不做强保护 |
| restore 中途崩溃 | 写入顺序：临时目录 → rename → 更新 index；启动时检查 `.pending_restore` 标记，未完成则回滚 |
| outline.json 生成失败 | 不阻塞 turn 完成，记诊断 error，下轮 memo 只用 intent.md |
| 项目体积过大（>10MB） | `StorageStrategy` 接口占位；Phase 3.5 降级到增量存储 |
| iterate 误判（用户想整个重做）| 让用户删项目重建；不提供"退回 greenfield"的 UI |

## 9. 测试策略

- **SnapshotManager 单元测试**：capture → modify → restore 三段路径；retention 清理；并发 capture
- **OutlineGenerator 单元测试**：固定 workspace → 固定 outline.json（快照测试）
- **IterationModeDetector 单元测试**：intent 存在/不存在 × snapshot 存在/不存在 四象限
- **Coordinator turn lifecycle 集成测试**：mock AI 工具调用序列，验证四子系统联动
- **端到端手动冒烟**：5 轮真实迭代会话，逐轮验证 undo；关闭 App 重开验证跨会话 memo 注入

## 10. Non-goals

- ❌ Git 式分支/多版本并存
- ❌ 远程同步（snapshot 仅本机）
- ❌ 可视化 diff 预览
- ❌ "改动前预览再应用"的 approval flow
- ❌ agent 把 memo 写成长篇综述（硬上限强制防膨胀）
- ❌ intent.md 结构化 schema 校验（纯 markdown，容错）
- ❌ 跨项目 snapshot 传递或合并
- ❌ 同会话漂移痛点（B）的单独处理——由 MEMO 注入 + 迭代压缩顺带解决

## 11. 已知风险

1. **Intent 依从性**：agent 可能忘调 `update_project_intent`。先不兜底，观察后决定是否加 nag
2. **Outline purpose 启发式不准**：零成本 accept 不完美；反馈强烈再升级到 LLM
3. **撤销语义用户理解成本**："撤销第 3 轮"不等于"撤销最近 3 次文件改动"，文案要清晰，需小范围用户验证
