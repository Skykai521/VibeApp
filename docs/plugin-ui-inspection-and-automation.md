# 插件模式 UI 检查与自动化操作方案

> 在插件模式运行生成的 App 时，从主进程读取插件 UI 的 View 树结构，并支持自动点击、文本输入、滚动和手势操作，用于 AI 自动测试和调试复现。

## 背景与动机

当前 AI Agent 生成并编译 App 后，在插件模式运行时缺乏以下能力：

1. **无法看到 UI 结构** — Agent 无法知道插件 App 当前界面的 View 布局和状态，难以调试 UI 问题
2. **无法交互操作** — Agent 无法模拟用户操作（点击、输入、滚动），无法自动测试 UI 交互是否正常
3. **调试效率低** — UI 问题只能靠用户描述或截图反馈，Agent 无法主动复现

### 使用场景

- AI 生成 App 后**自动测试** UI 交互是否正常
- AI 在调试时**模拟用户操作**来复现问题
- AI 通过 View 树结构**定位 UI 问题**（布局错误、控件缺失等）

## 技术挑战：跨进程 View 访问

插件运行在独立进程 (`:pluginN`)，AI Agent 在主进程。跨进程无法直接访问 View 对象。

### 候选方案对比

| 方案 | 用户感知 | 延迟 | 可靠性 | 可行性 |
|------|:---:|:---:|:---:|:---:|
| **AIDL / Binder IPC（选定）** | 无 | <10ms | 高 | 同 app 同 UID，无需权限 |
| 取消进程隔离 | 无 | 0 | 低 | 插件崩溃带崩宿主 |
| AccessibilityService | 需手动开启 | 50-150ms | 中 | 用户体验差 |
| UiAutomator | — | — | — | 需 Instrumentation，生产不可用 |
| 共享文件 + FileObserver | 无 | 100ms-1s | 低 | 不可靠 |
| ContentProvider.call() | 无 | 5-15ms | 中 | 可行但不如 AIDL 清晰 |

### 选择 AIDL 的理由

1. **零用户感知** — 同 app 同 UID，不需要任何额外权限
2. **低延迟** — 同应用 Binder 调用 <10ms
3. **高可靠性** — Binder 是 Android IPC 的基础设施
4. **保留进程隔离** — 插件崩溃不影响宿主，安全稳定
5. **类型安全** — AIDL 提供编译期类型检查

## 整体架构

```
┌─────────────────────┐          AIDL/Binder          ┌─────────────────────────┐
│   主进程 (host)      │  ◄──────────────────────────►  │   :pluginN 进程          │
│                     │                                │                         │
│  AI Agent           │   dumpViewTree()               │  PluginContainerActivity │
│    ↓                │   performClick(selector)       │    ↓                     │
│  AgentTool          │   inputText(selector, text)    │  PluginInspectorService  │
│  (inspect_ui,       │   scroll(selector, dir)        │    ↓                     │
│   interact_ui)      │   performGesture(params)       │  View Tree (plugin UI)   │
│    ↓                │   ◄── 返回 JSON 结果            │                         │
│  ServiceConnection  │                                │                         │
└─────────────────────┘                                └─────────────────────────┘
```

**三层设计：**

1. **AIDL 接口层** — 定义跨进程通信协议
2. **插件端 Service** — `PluginInspectorService`，在插件进程中执行 View 遍历和 UI 操作
3. **宿主端 Agent Tool** — `inspect_ui` 和 `interact_ui` 两个 Agent 工具

## 详细设计

### 1. AIDL 接口

```aidl
// IPluginInspector.aidl
package com.vibe.app.plugin;

interface IPluginInspector {

    // 获取 View 树结构（返回 JSON 字符串）
    String dumpViewTree();

    // 点击指定 View（selector 为 JSON 格式）
    String performClick(String selector);

    // 文本输入
    String inputText(String selector, String text);

    // 滚动（direction: "up", "down", "left", "right"）
    String scroll(String selector, String direction, int amount);

    // 通用手势（gestureJson 包含类型、坐标、时长等）
    String performGesture(String gestureJson);
}
```

### 2. Selector 格式

统一的 View 定位语法，优先用 ID，找不到时用文本匹配：

```json
// 通过 resource ID 名称定位（优先）
{"type": "id", "value": "btn_submit"}

// 通过精确文本匹配
{"type": "text", "value": "Submit"}

// 通过文本包含匹配
{"type": "text_contains", "value": "提交"}

// 通过 contentDescription
{"type": "desc", "value": "submit button"}

// 通过类名 + 索引（如第 2 个 EditText）
{"type": "class", "value": "EditText", "index": 1}
```

### 3. View 树输出格式

`dumpViewTree()` 返回 JSON，每个节点包含关键属性：

```json
{
  "class": "CoordinatorLayout",
  "bounds": {"left": 0, "top": 0, "right": 1080, "bottom": 1920},
  "children": [
    {
      "class": "LinearLayout",
      "id": "main_container",
      "children": [
        {
          "class": "TextView",
          "id": "tv_title",
          "text": "天气预报",
          "visibility": "visible",
          "bounds": {"left": 0, "top": 0, "right": 1080, "bottom": 120}
        },
        {
          "class": "Button",
          "id": "btn_refresh",
          "text": "刷新",
          "clickable": true,
          "enabled": true,
          "bounds": {"left": 400, "top": 130, "right": 680, "bottom": 230}
        },
        {
          "class": "EditText",
          "id": "et_city",
          "text": "",
          "hint": "输入城市名",
          "focusable": true,
          "bounds": {"left": 50, "top": 250, "right": 1030, "bottom": 370}
        }
      ]
    }
  ]
}
```

**节点属性：**
- `class` — View 类名（简名）
- `id` — resource ID 名称（如有）
- `text` — 文本内容（TextView 及子类）
- `hint` — 提示文字（EditText）
- `contentDescription` — 无障碍描述（如有）
- `bounds` — 屏幕坐标 (left, top, right, bottom)
- `visibility` — visible / invisible / gone
- `clickable` / `enabled` / `focusable` — 交互状态
- `children` — 子 View 列表（ViewGroup）

### 4. 插件端实现 — PluginInspectorService

在插件进程中运行的 Bound Service：

```kotlin
class PluginInspectorService : Service() {

    private var activity: PluginContainerActivity? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val binder = object : IPluginInspector.Stub() {

        override fun dumpViewTree(): String {
            // 在主线程遍历 View 树，阻塞等待结果
            return runOnMainThreadBlocking {
                val root = activity?.window?.decorView
                    ?: return@runOnMainThreadBlocking """{"error": "no activity"}"""
                dumpView(root).toString()
            }
        }

        override fun performClick(selector: String): String {
            return runOnMainThreadBlocking {
                val view = findView(parseSelector(selector))
                    ?: return@runOnMainThreadBlocking """{"success": false, "error": "view not found"}"""
                view.performClick()
                """{"success": true}"""
            }
        }

        override fun inputText(selector: String, text: String): String {
            return runOnMainThreadBlocking {
                val view = findView(parseSelector(selector)) as? EditText
                    ?: return@runOnMainThreadBlocking """{"success": false, "error": "EditText not found"}"""
                view.setText(text)
                """{"success": true}"""
            }
        }

        override fun scroll(selector: String, direction: String, amount: Int): String {
            return runOnMainThreadBlocking {
                val view = findView(parseSelector(selector))
                    ?: return@runOnMainThreadBlocking """{"success": false, "error": "view not found"}"""
                val scrolled = when (direction) {
                    "up" -> view.scrollBy(0, -amount)
                    "down" -> view.scrollBy(0, amount)
                    "left" -> view.scrollBy(-amount, 0)
                    "right" -> view.scrollBy(amount, 0)
                    else -> null
                }
                """{"success": true}"""
            }
        }

        override fun performGesture(gestureJson: String): String {
            // 解析手势参数，使用 Instrumentation 或 MotionEvent 模拟
            // 支持：长按、双击、滑动等
            return runOnMainThreadBlocking { /* ... */ }
        }
    }

    // 阻塞等待主线程执行完毕并返回结果
    private fun <T> runOnMainThreadBlocking(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        var result: T? = null
        mainHandler.post {
            result = block()
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
        return result ?: throw TimeoutException("Main thread operation timed out")
    }

    // View 树遍历
    private fun dumpView(view: View, depth: Int = 0): JSONObject {
        val node = JSONObject()
        node.put("class", view.javaClass.simpleName)

        // ID
        if (view.id != View.NO_ID) {
            try {
                node.put("id", view.resources.getResourceEntryName(view.id))
            } catch (_: Exception) {}
        }

        // 文本
        if (view is TextView) {
            node.put("text", view.text?.toString() ?: "")
            if (view is EditText) {
                node.put("hint", view.hint?.toString() ?: "")
            }
        }

        // contentDescription
        view.contentDescription?.let { node.put("contentDescription", it.toString()) }

        // 位置和状态
        val rect = IntArray(2)
        view.getLocationOnScreen(rect)
        node.put("bounds", JSONObject().apply {
            put("left", rect[0])
            put("top", rect[1])
            put("right", rect[0] + view.width)
            put("bottom", rect[1] + view.height)
        })
        node.put("visibility", when (view.visibility) {
            View.VISIBLE -> "visible"
            View.INVISIBLE -> "invisible"
            else -> "gone"
        })
        node.put("clickable", view.isClickable)
        node.put("enabled", view.isEnabled)
        node.put("focusable", view.isFocusable)

        // 子 View
        if (view is ViewGroup) {
            val children = JSONArray()
            for (i in 0 until view.childCount) {
                children.put(dumpView(view.getChildAt(i), depth + 1))
            }
            node.put("children", children)
        }

        return node
    }

    // View 查找（深度优先）
    private fun findView(selector: Selector): View? {
        val root = activity?.window?.decorView ?: return null
        return findViewRecursive(root, selector)
    }

    private fun findViewRecursive(view: View, selector: Selector, counter: IntArray = intArrayOf(0)): View? {
        if (selector.matches(view, counter)) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findViewRecursive(view.getChildAt(i), selector, counter)
                if (found != null) return found
            }
        }
        return null
    }

    override fun onBind(intent: Intent): IBinder = binder
}
```

### 5. 宿主端 — ServiceConnection 管理

**PluginManager 扩展：**

```kotlin
// 每个 slot 维护 Inspector 连接
private val inspectorConnections = arrayOfNulls<ServiceConnection>(SLOT_COUNT)
private val inspectors = arrayOfNulls<IPluginInspector>(SLOT_COUNT)

fun getInspector(projectId: String): IPluginInspector? {
    val slotIndex = findSlotByProjectId(projectId) ?: return null
    return inspectors[slotIndex]
}

// 插件启动时自动绑定
private fun bindInspector(slotIndex: Int) {
    val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            inspectors[slotIndex] = IPluginInspector.Stub.asInterface(service)
        }
        override fun onServiceDisconnected(name: ComponentName) {
            inspectors[slotIndex] = null
        }
    }
    inspectorConnections[slotIndex] = connection
    bindService(
        Intent(context, PluginInspectorSlot::class.java /* 对应 slot 的 Service */),
        connection,
        Context.BIND_AUTO_CREATE
    )
}
```

### 6. Agent Tools

#### inspect_ui — 检查 UI 结构

| 属性 | 值 |
|------|------|
| 名称 | `inspect_ui` |
| 参数 | `project_id` |
| 返回 | View 树 JSON |
| 用途 | AI 查看当前插件界面的 View 结构和属性 |

#### interact_ui — 执行 UI 操作

| 属性 | 值 |
|------|------|
| 名称 | `interact_ui` |
| 参数 | `project_id`, `action`(click/input/scroll/gesture), `selector`(JSON), `value`(可选) |
| 返回 | 操作结果 + 操作后的 View 树快照 |
| 用途 | AI 模拟用户操作并立即看到 UI 变化 |

**interact_ui 自动附带操作后的 View 树**，让 AI 立即看到 UI 变化，减少一次额外的 inspect_ui 调用。

### 7. Manifest 注册

每个 slot 需要对应一个 InspectorService，运行在相同进程：

```xml
<service
    android:name=".plugin.PluginInspectorSlot0"
    android:process=":plugin0"
    android:exported="false" />
<service
    android:name=".plugin.PluginInspectorSlot1"
    android:process=":plugin1"
    android:exported="false" />
<!-- ... Slot2-4 同理 -->
```

每个 `PluginInspectorSlotN` 是 `PluginInspectorService` 的空子类，仅用于进程绑定（与 PluginSlot0-4 模式一致）。

## 需要变更/新增的文件

| 文件 | 变更 |
|------|------|
| `app/src/main/aidl/com/vibe/app/plugin/IPluginInspector.aidl` | 新增 AIDL 接口定义 |
| `app/.../plugin/PluginInspectorService.kt` | 新增 Bound Service，实现 View 遍历和 UI 操作 |
| `app/.../plugin/PluginContainerActivity.kt` | 启动并绑定 InspectorService，传递 Activity 引用 |
| `app/.../plugin/PluginManager.kt` | 管理 ServiceConnection，暴露 inspector 给工具层 |
| `app/src/main/AndroidManifest.xml` | 注册 5 个 InspectorService（每 slot 一个） |
| `app/.../feature/agent/tool/PluginUiTools.kt` | 新增 inspect_ui 和 interact_ui Agent 工具 |
| `app/.../feature/agent/tool/AgentToolRegistry` | 注册新工具 |
| `app/src/main/assets/agent-system-prompt.md` | 添加 UI 检查和交互工具的使用说明 |

## Agent System Prompt 补充

```markdown
## UI 检查与自动化操作

当生成的 App 在插件模式运行时，可以使用以下工具检查和操控 UI：

### inspect_ui
获取当前界面的 View 树结构（类似 Layout Inspector）：
- 返回完整 View 层级，包含每个 View 的类名、ID、文本、位置、交互状态
- 用于调试 UI 布局问题、确认界面是否按预期渲染

### interact_ui
模拟用户操作，支持以下 action：
- click — 点击指定 View
- input — 向 EditText 输入文本
- scroll — 滚动指定方向和距离
- gesture — 执行长按、双击、滑动等手势

View 定位方式（selector）：
- 优先用 ID：{"type": "id", "value": "btn_submit"}
- 文本匹配：{"type": "text", "value": "Submit"}
- 模糊匹配：{"type": "text_contains", "value": "提交"}
- 类名索引：{"type": "class", "value": "EditText", "index": 0}

操作后会自动返回最新的 View 树，无需额外调用 inspect_ui。

### 典型工作流
1. 用 inspect_ui 查看当前界面结构
2. 用 interact_ui 模拟操作（点击按钮、输入文本等）
3. 从返回的 View 树中验证 UI 是否正确响应
4. 如发现问题，修改代码后重新构建运行
```

## 与现有日志系统的配合

此方案与现有的 `read_runtime_log` 工具互补：

| 能力 | read_runtime_log | inspect_ui / interact_ui |
|------|:---:|:---:|
| 查看运行日志 | ✅ | — |
| 查看崩溃堆栈 | ✅ | — |
| 查看 UI 结构 | — | ✅ |
| 模拟用户操作 | — | ✅ |
| 验证交互行为 | — | ✅ |

AI 调试时的典型流程：
1. 构建并运行 App
2. `inspect_ui` 检查 UI 是否正确渲染
3. `interact_ui` 模拟用户操作（点击、输入等）
4. `read_runtime_log` 查看操作产生的日志
5. 如有崩溃，`read_runtime_log` 读取 crash.log

## 实施注意事项

### 主线程安全
所有 View 操作必须在插件进程的主线程执行。AIDL 调用默认在 Binder 线程，需通过 `Handler(Looper.getMainLooper())` 切换，并使用 `CountDownLatch` 阻塞等待结果。设置 5 秒超时避免死锁。

### View 树大小控制
复杂 UI 的 View 树可能很大。建议：
- 限制遍历深度（默认 15 层）
- 跳过 `visibility=gone` 的 View
- 对超长文本截断（如 >100 字符）

### 插件未运行时的处理
如果目标 `projectId` 没有正在运行的插件，`inspect_ui` 和 `interact_ui` 应返回明确错误信息：`{"error": "plugin not running", "hint": "请先使用 build_project 构建并运行 App"}`。
