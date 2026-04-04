# Plugin UI Inspection & Automation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable the AI Agent to inspect plugin UI View trees and perform automated UI interactions (click, input, scroll) via AIDL IPC.

**Architecture:** AIDL defines the cross-process contract. A PluginInspectorService in each plugin process traverses the View tree and executes UI actions. The host process binds to these services via PluginManager and exposes them as `inspect_ui` / `interact_ui` Agent tools.

**Tech Stack:** AIDL/Binder IPC, Android View system, CountDownLatch for thread synchronization, Hilt DI

---

### Task 1: Create AIDL interface

**Files:**
- Create: `app/src/main/aidl/com/vibe/app/plugin/IPluginInspector.aidl`

- [ ] **Step 1: Create AIDL directory and interface file**

Create the directory `app/src/main/aidl/com/vibe/app/plugin/` and write:

```aidl
package com.vibe.app.plugin;

interface IPluginInspector {
    String dumpViewTree();
    String performClick(String selector);
    String inputText(String selector, String text);
    String scroll(String selector, String direction, int amount);
    String performGesture(String gestureJson);
}
```

- [ ] **Step 2: Verify AIDL generates stubs**

```bash
./gradlew :app:compileDebugAidl
```

Expected: BUILD SUCCESSFUL. The generated Java stub will be at `app/build/generated/aidl_source_output_dir/debug/out/com/vibe/app/plugin/IPluginInspector.java`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/aidl/
git commit -m "feat: add IPluginInspector AIDL interface"
```

---

### Task 2: Create PluginInspectorService with slot subclasses

**Files:**
- Create: `app/src/main/kotlin/com/vibe/app/plugin/PluginInspectorService.kt`

This file contains:
- `ActivityHolder` object — static registry for Activity references per slot
- `PluginInspectorService` — Bound Service implementing IPluginInspector.Stub
- `PluginInspectorSlot0..4` — empty subclasses for process binding

- [ ] **Step 1: Create the full PluginInspectorService.kt**

```kotlin
package com.vibe.app.plugin

import android.app.Activity
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Static registry for passing Activity references between
 * PluginContainerActivity and PluginInspectorService in the same process.
 */
object ActivityHolder {
    private val activities = arrayOfNulls<Activity>(PluginManager.MAX_SLOTS)

    fun set(slotIndex: Int, activity: Activity) {
        activities[slotIndex] = activity
    }

    fun get(slotIndex: Int): Activity? = activities.getOrNull(slotIndex)

    fun clear(slotIndex: Int) {
        if (slotIndex in activities.indices) {
            activities[slotIndex] = null
        }
    }
}

/**
 * Bound Service running in each plugin process.
 * Traverses the View tree and executes UI actions on behalf of the AI Agent.
 */
open class PluginInspectorService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Subclasses override to return their slot index. */
    protected open val slotIndex: Int = -1

    private val binder = object : IPluginInspector.Stub() {

        override fun dumpViewTree(): String {
            return runOnMainThreadBlocking {
                val activity = ActivityHolder.get(slotIndex)
                    ?: return@runOnMainThreadBlocking """{"error":"no activity"}"""
                val root = activity.window?.decorView
                    ?: return@runOnMainThreadBlocking """{"error":"no decor view"}"""
                dumpView(root, 0).toString()
            }
        }

        override fun performClick(selector: String): String {
            return runOnMainThreadBlocking {
                val view = findViewBySelector(selector)
                    ?: return@runOnMainThreadBlocking """{"success":false,"error":"view not found"}"""
                view.performClick()
                """{"success":true}"""
            }
        }

        override fun inputText(selector: String, text: String): String {
            return runOnMainThreadBlocking {
                val view = findViewBySelector(selector)
                if (view !is EditText) {
                    return@runOnMainThreadBlocking """{"success":false,"error":"EditText not found"}"""
                }
                view.setText(text)
                """{"success":true}"""
            }
        }

        override fun scroll(selector: String, direction: String, amount: Int): String {
            return runOnMainThreadBlocking {
                val view = findViewBySelector(selector)
                    ?: return@runOnMainThreadBlocking """{"success":false,"error":"view not found"}"""
                when (direction) {
                    "up" -> view.scrollBy(0, -amount)
                    "down" -> view.scrollBy(0, amount)
                    "left" -> view.scrollBy(-amount, 0)
                    "right" -> view.scrollBy(amount, 0)
                    else -> return@runOnMainThreadBlocking """{"success":false,"error":"invalid direction: $direction"}"""
                }
                """{"success":true}"""
            }
        }

        override fun performGesture(gestureJson: String): String {
            return """{"success":false,"error":"gesture not yet supported"}"""
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    // --- View tree traversal ---

    private fun dumpView(view: View, depth: Int): JSONObject {
        val node = JSONObject()
        node.put("class", view.javaClass.simpleName)

        if (view.id != View.NO_ID) {
            try {
                node.put("id", view.resources.getResourceEntryName(view.id))
            } catch (_: Exception) { /* dynamic or unnamed ID */ }
        }

        if (view is TextView) {
            val text = view.text?.toString() ?: ""
            node.put("text", if (text.length > MAX_TEXT_LENGTH) text.take(MAX_TEXT_LENGTH) + "…" else text)
            if (view is EditText) {
                node.put("hint", view.hint?.toString() ?: "")
            }
        }

        view.contentDescription?.let { desc ->
            val s = desc.toString()
            node.put("contentDescription", if (s.length > MAX_TEXT_LENGTH) s.take(MAX_TEXT_LENGTH) + "…" else s)
        }

        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        node.put("bounds", JSONObject().apply {
            put("left", loc[0])
            put("top", loc[1])
            put("right", loc[0] + view.width)
            put("bottom", loc[1] + view.height)
        })

        node.put("visibility", when (view.visibility) {
            View.VISIBLE -> "visible"
            View.INVISIBLE -> "invisible"
            else -> "gone"
        })
        node.put("clickable", view.isClickable)
        node.put("enabled", view.isEnabled)
        node.put("focusable", view.isFocusable)

        if (view is ViewGroup && depth < MAX_DEPTH) {
            val children = JSONArray()
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                if (child.visibility == View.GONE) continue
                children.put(dumpView(child, depth + 1))
            }
            node.put("children", children)
        }

        return node
    }

    // --- View finding ---

    private fun findViewBySelector(selectorJson: String): View? {
        val activity = ActivityHolder.get(slotIndex) ?: return null
        val root = activity.window?.decorView ?: return null
        val sel = JSONObject(selectorJson)
        val type = sel.getString("type")
        val value = sel.getString("value")
        val index = sel.optInt("index", 0)
        return findViewRecursive(root, type, value, index, intArrayOf(0))
    }

    private fun findViewRecursive(
        view: View,
        type: String,
        value: String,
        targetIndex: Int,
        counter: IntArray,
    ): View? {
        if (matchesSelector(view, type, value)) {
            if (counter[0] == targetIndex) return view
            counter[0]++
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findViewRecursive(view.getChildAt(i), type, value, targetIndex, counter)
                if (found != null) return found
            }
        }
        return null
    }

    private fun matchesSelector(view: View, type: String, value: String): Boolean {
        return when (type) {
            "id" -> {
                if (view.id == View.NO_ID) return false
                try {
                    view.resources.getResourceEntryName(view.id) == value
                } catch (_: Exception) { false }
            }
            "text" -> (view as? TextView)?.text?.toString() == value
            "text_contains" -> (view as? TextView)?.text?.toString()?.contains(value) == true
            "desc" -> view.contentDescription?.toString() == value
            "class" -> view.javaClass.simpleName == value
            else -> false
        }
    }

    // --- Thread helpers ---

    private fun <T> runOnMainThreadBlocking(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        var result: T? = null
        var error: Throwable? = null
        mainHandler.post {
            try {
                result = block()
            } catch (e: Throwable) {
                error = e
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw TimeoutException("Main thread operation timed out after ${TIMEOUT_SECONDS}s")
        }
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    companion object {
        private const val MAX_DEPTH = 15
        private const val MAX_TEXT_LENGTH = 100
        private const val TIMEOUT_SECONDS = 5L
    }
}

// 5 process-isolated inspector slots — each declared in manifest with matching plugin process
class PluginInspectorSlot0 : PluginInspectorService() { override val slotIndex = 0 }
class PluginInspectorSlot1 : PluginInspectorService() { override val slotIndex = 1 }
class PluginInspectorSlot2 : PluginInspectorService() { override val slotIndex = 2 }
class PluginInspectorSlot3 : PluginInspectorService() { override val slotIndex = 3 }
class PluginInspectorSlot4 : PluginInspectorService() { override val slotIndex = 4 }
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/plugin/PluginInspectorService.kt
git commit -m "feat: add PluginInspectorService with View tree traversal and UI automation"
```

---

### Task 3: Register Activity with ActivityHolder in PluginContainerActivity

**Files:**
- Modify: `app/src/main/kotlin/com/vibe/app/plugin/PluginContainerActivity.kt`

- [ ] **Step 1: Add ActivityHolder.set() in onCreate**

After the line `projectId = intent.getStringExtra(EXTRA_PROJECT_ID)` (line 43), add:

```kotlin
val slotIndex = intent.getIntExtra(EXTRA_SLOT_INDEX, -1)
if (slotIndex >= 0) {
    ActivityHolder.set(slotIndex, this)
}
```

Note: `EXTRA_SLOT_INDEX` is already defined in the companion object (line 274). The `slotIndex` variable is new — it's used later in `onDestroy` too.

- [ ] **Step 2: Store slotIndex as a field**

Add a field after `private var projectId: String? = null` (line 36):

```kotlin
private var slotIndex: Int = -1
```

Then change the code in Step 1 to use the field:

```kotlin
slotIndex = intent.getIntExtra(EXTRA_SLOT_INDEX, -1)
if (slotIndex >= 0) {
    ActivityHolder.set(slotIndex, this)
}
```

- [ ] **Step 3: Add ActivityHolder.clear() in onDestroy**

In `onDestroy()`, before `pluginActivity = null` (line 165), add:

```kotlin
if (slotIndex >= 0) {
    ActivityHolder.clear(slotIndex)
}
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/plugin/PluginContainerActivity.kt
git commit -m "feat: register Activity with ActivityHolder for plugin UI inspection"
```

---

### Task 4: Add inspector binding to PluginManager

**Files:**
- Modify: `app/src/main/kotlin/com/vibe/app/plugin/PluginManager.kt`

- [ ] **Step 1: Add imports**

Add these imports after the existing imports:

```kotlin
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
```

- [ ] **Step 2: Add inspector fields**

Add after `private val slots = arrayOfNulls<SlotInfo>(MAX_SLOTS)` (line 25):

```kotlin
private val inspectorConnections = arrayOfNulls<ServiceConnection>(MAX_SLOTS)
private val inspectors = arrayOfNulls<IPluginInspector>(MAX_SLOTS)

private val inspectorServices: Array<Class<*>> = arrayOf(
    PluginInspectorSlot0::class.java,
    PluginInspectorSlot1::class.java,
    PluginInspectorSlot2::class.java,
    PluginInspectorSlot3::class.java,
    PluginInspectorSlot4::class.java,
)
```

- [ ] **Step 3: Add bindInspector and unbindInspector methods**

Add before the `companion object`:

```kotlin
/**
 * Returns the IPluginInspector for the given project, or null if no plugin is running.
 */
fun getInspector(projectId: String): IPluginInspector? {
    val slotIndex = slots.indices.firstOrNull { slots[it]?.projectId == projectId }
        ?: return null
    return inspectors[slotIndex]
}

private fun bindInspector(slotIndex: Int) {
    unbindInspector(slotIndex)
    val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            inspectors[slotIndex] = IPluginInspector.Stub.asInterface(service)
            Log.d(TAG, "Inspector bound for slot $slotIndex")
        }
        override fun onServiceDisconnected(name: ComponentName) {
            inspectors[slotIndex] = null
            Log.d(TAG, "Inspector disconnected for slot $slotIndex")
        }
    }
    inspectorConnections[slotIndex] = connection
    val intent = Intent(context, inspectorServices[slotIndex])
    context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
}

private fun unbindInspector(slotIndex: Int) {
    inspectorConnections[slotIndex]?.let { conn ->
        try {
            context.unbindService(conn)
        } catch (_: Exception) { /* already unbound */ }
    }
    inspectorConnections[slotIndex] = null
    inspectors[slotIndex] = null
}
```

- [ ] **Step 4: Call bindInspector in launchPlugin**

In `launchPlugin()`, after `context.startActivity(intent)` (line 56), add:

```kotlin
bindInspector(slotIndex)
```

- [ ] **Step 5: Call unbindInspector in killPluginProcess**

In `killPluginProcess()`, at the beginning of the method (before `val processName = ...`, line 94), add:

```kotlin
unbindInspector(slotIndex)
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/plugin/PluginManager.kt
git commit -m "feat: add inspector binding lifecycle to PluginManager"
```

---

### Task 5: Register InspectorService slots in AndroidManifest.xml

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Add 5 service declarations**

Before `</application>` (line 95), add:

```xml
        <!-- Plugin inspector services (0-4): each in matching plugin process -->
        <service
            android:name="com.vibe.app.plugin.PluginInspectorSlot0"
            android:process=":plugin0"
            android:exported="false" />
        <service
            android:name="com.vibe.app.plugin.PluginInspectorSlot1"
            android:process=":plugin1"
            android:exported="false" />
        <service
            android:name="com.vibe.app.plugin.PluginInspectorSlot2"
            android:process=":plugin2"
            android:exported="false" />
        <service
            android:name="com.vibe.app.plugin.PluginInspectorSlot3"
            android:process=":plugin3"
            android:exported="false" />
        <service
            android:name="com.vibe.app.plugin.PluginInspectorSlot4"
            android:process=":plugin4"
            android:exported="false" />
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/AndroidManifest.xml
git commit -m "feat: register PluginInspectorSlot services in manifest"
```

---

### Task 6: Add inspect_ui and interact_ui Agent Tools

**Files:**
- Modify: `app/src/main/kotlin/com/vibe/app/feature/agent/tool/ProjectTools.kt`

- [ ] **Step 1: Add tool name constants**

After `private const val FIX_CRASH_GUIDE = "fix_crash_guide"` (line 36), add:

```kotlin
private const val INSPECT_UI = "inspect_ui"
private const val INTERACT_UI = "interact_ui"
```

- [ ] **Step 2: Add InspectUiTool class**

Add before `DefaultAgentToolRegistry` (before line 668), after the `FixCrashGuideTool` class:

```kotlin
@Singleton
class InspectUiTool @Inject constructor(
    private val pluginManager: PluginManager,
) : AgentTool {

    override val definition: AgentToolDefinition = AgentToolDefinition(
        name = INSPECT_UI,
        description = "Get the View tree of the currently running plugin UI.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {})
            put("required", buildJsonArray {})
        },
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val inspector = pluginManager.getInspector(context.projectId)
            ?: return AgentToolResult(
                toolCallId = call.id,
                toolName = call.name,
                output = buildJsonObject {
                    put("error", JsonPrimitive("plugin not running"))
                    put("hint", JsonPrimitive("Please build and run the app first using run_build_pipeline."))
                },
            )
        return try {
            val viewTree = inspector.dumpViewTree()
            AgentToolResult(
                toolCallId = call.id,
                toolName = call.name,
                output = JsonPrimitive(viewTree),
            )
        } catch (e: Exception) {
            AgentToolResult(
                toolCallId = call.id,
                toolName = call.name,
                output = buildJsonObject {
                    put("error", JsonPrimitive(e.message ?: "inspection failed"))
                },
                isError = true,
            )
        }
    }
}

@Singleton
class InteractUiTool @Inject constructor(
    private val pluginManager: PluginManager,
) : AgentTool {

    override val definition: AgentToolDefinition = AgentToolDefinition(
        name = INTERACT_UI,
        description = "Perform a UI action (click, input, scroll) on the running plugin and return the updated View tree.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                put("action", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Action type: click, input, scroll"))
                    put("enum", buildJsonArray {
                        add(JsonPrimitive("click"))
                        add(JsonPrimitive("input"))
                        add(JsonPrimitive("scroll"))
                    })
                })
                put("selector", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("JSON selector: {\"type\":\"id\",\"value\":\"btn_submit\"} or {\"type\":\"text\",\"value\":\"Submit\"}"))
                })
                put("value", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Text to input (for action=input) or scroll direction up/down/left/right (for action=scroll)"))
                })
                put("amount", buildJsonObject {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive("Scroll distance in pixels (for action=scroll, default 500)"))
                })
            })
            put("required", buildJsonArray {
                add(JsonPrimitive("action"))
                add(JsonPrimitive("selector"))
            })
        },
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val inspector = pluginManager.getInspector(context.projectId)
            ?: return AgentToolResult(
                toolCallId = call.id,
                toolName = call.name,
                output = buildJsonObject {
                    put("error", JsonPrimitive("plugin not running"))
                    put("hint", JsonPrimitive("Please build and run the app first using run_build_pipeline."))
                },
            )

        val args = call.arguments.jsonObject
        val action = args["action"]?.jsonPrimitive?.content ?: ""
        val selector = args["selector"]?.jsonPrimitive?.content ?: ""
        val value = args["value"]?.jsonPrimitive?.content ?: ""
        val amount = args["amount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 500

        return try {
            val actionResult = when (action) {
                "click" -> inspector.performClick(selector)
                "input" -> inspector.inputText(selector, value)
                "scroll" -> inspector.scroll(selector, value, amount)
                else -> """{"success":false,"error":"unknown action: $action"}"""
            }

            // Auto-append updated View tree after action
            val viewTree = try { inspector.dumpViewTree() } catch (_: Exception) { null }

            AgentToolResult(
                toolCallId = call.id,
                toolName = call.name,
                output = buildJsonObject {
                    put("result", JsonPrimitive(actionResult))
                    if (viewTree != null) {
                        put("view_tree", JsonPrimitive(viewTree))
                    }
                },
            )
        } catch (e: Exception) {
            AgentToolResult(
                toolCallId = call.id,
                toolName = call.name,
                output = buildJsonObject {
                    put("error", JsonPrimitive(e.message ?: "interaction failed"))
                },
                isError = true,
            )
        }
    }
}
```

- [ ] **Step 3: Add import for PluginManager**

Add to imports at top of file:

```kotlin
import com.vibe.app.plugin.PluginManager
```

- [ ] **Step 4: Register tools in DefaultAgentToolRegistry**

Update the `DefaultAgentToolRegistry` constructor to include the new tools. Change the constructor (lines 669-680) from:

```kotlin
class DefaultAgentToolRegistry @Inject constructor(
    readProjectFileTool: ReadProjectFileTool,
    writeProjectFileTool: WriteProjectFileTool,
    editProjectFileTool: EditProjectFileTool,
    deleteProjectFileTool: DeleteProjectFileTool,
    listProjectFilesTool: ListProjectFilesTool,
    runBuildPipelineTool: RunBuildPipelineTool,
    renameProjectTool: RenameProjectTool,
    updateProjectIconTool: UpdateProjectIconTool,
    readRuntimeLogTool: ReadRuntimeLogTool,
    fixCrashGuideTool: FixCrashGuideTool,
) : AgentToolRegistry {
```

To:

```kotlin
class DefaultAgentToolRegistry @Inject constructor(
    readProjectFileTool: ReadProjectFileTool,
    writeProjectFileTool: WriteProjectFileTool,
    editProjectFileTool: EditProjectFileTool,
    deleteProjectFileTool: DeleteProjectFileTool,
    listProjectFilesTool: ListProjectFilesTool,
    runBuildPipelineTool: RunBuildPipelineTool,
    renameProjectTool: RenameProjectTool,
    updateProjectIconTool: UpdateProjectIconTool,
    readRuntimeLogTool: ReadRuntimeLogTool,
    fixCrashGuideTool: FixCrashGuideTool,
    inspectUiTool: InspectUiTool,
    interactUiTool: InteractUiTool,
) : AgentToolRegistry {
```

And add them to the `tools` list (after `fixCrashGuideTool,` in the list):

```kotlin
    inspectUiTool,
    interactUiTool,
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/feature/agent/tool/ProjectTools.kt
git commit -m "feat: add inspect_ui and interact_ui agent tools"
```

---

### Task 7: Update agent system prompt

**Files:**
- Modify: `app/src/main/assets/agent-system-prompt.md`

- [ ] **Step 1: Add UI inspection section**

Before the "## Hard Rules" section at the end of the file, add:

```markdown
## UI Inspection & Automation

When the generated App is running in plugin mode, you can inspect and interact with its UI:

### inspect_ui
Get the current View tree structure (similar to Layout Inspector):
- Returns the full View hierarchy with class name, ID, text, bounds, and interaction state for each View
- Use to debug layout issues or verify the UI rendered correctly

### interact_ui
Simulate user actions. Supported actions:
- **click** — tap a View: `{"action":"click","selector":{"type":"id","value":"btn_submit"}}`
- **input** — type into an EditText: `{"action":"input","selector":{"type":"id","value":"et_city"},"value":"Beijing"}`
- **scroll** — scroll a View: `{"action":"scroll","selector":{"type":"id","value":"scroll_view"},"value":"down","amount":500}`

Selector types (use ID first, fall back to text):
- `{"type":"id","value":"btn_submit"}` — by resource ID name
- `{"type":"text","value":"Submit"}` — by exact text
- `{"type":"text_contains","value":"提交"}` — by text containing
- `{"type":"class","value":"EditText","index":0}` — by class name + index

After each action, the updated View tree is automatically returned.

### Typical Workflow
1. Build and run the app with run_build_pipeline
2. Use inspect_ui to see the current UI structure
3. Use interact_ui to simulate user actions (tap buttons, enter text, scroll)
4. Check the returned View tree to verify the UI responded correctly
5. If issues found, fix the code and rebuild
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/assets/agent-system-prompt.md
git commit -m "feat: document UI inspection tools in agent system prompt"
```

---

### Task 8: Build verification

- [ ] **Step 1: Run assembleDebug**

```bash
./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL. All new code compiles, AIDL stubs generate correctly, Hilt DI wires the new tools.

- [ ] **Step 2: Run tests**

```bash
./gradlew :app:testDebugUnitTest
```

Expected: All existing tests pass.

- [ ] **Step 3: Final commit if any fixes were needed**

Only if previous steps required fixes.
