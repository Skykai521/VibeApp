# Agent Auto-Test (Phase 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable the AI agent to launch built APKs and automatically test them via UI inspection/interaction tools.

**Architecture:** Add a `launch_app` tool that starts the plugin and waits for Inspector binding. Enhance `run_build_pipeline` success output with next-step hints. Update the system prompt to guide the model on when to test vs. stop.

**Tech Stack:** Kotlin, Hilt DI (`@IntoSet`), PluginManager, AgentTool interface

---

### Task 1: Create `LaunchAppTool`

**Files:**
- Create: `app/src/main/kotlin/com/vibe/app/feature/agent/tool/LaunchAppTool.kt`

- [ ] **Step 1: Create the tool implementation**

```kotlin
// LaunchAppTool.kt
package com.vibe.app.feature.agent.tool

import com.vibe.app.feature.agent.AgentTool
import com.vibe.app.feature.agent.AgentToolCall
import com.vibe.app.feature.agent.AgentToolContext
import com.vibe.app.feature.agent.AgentToolDefinition
import com.vibe.app.feature.agent.AgentToolResult
import com.vibe.app.feature.project.ProjectManager
import com.vibe.app.plugin.PluginManager
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

@Singleton
class LaunchAppTool @Inject constructor(
    private val projectManager: ProjectManager,
    private val pluginManager: PluginManager,
) : AgentTool {

    override val definition = AgentToolDefinition(
        name = "launch_app",
        description = "Launch the most recently built APK in plugin mode and wait for it to be ready. " +
            "Call this after a successful run_build_pipeline to test the app. " +
            "Returns the initial View tree on success.",
        inputSchema = buildJsonObject {},
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val workspace = projectManager.openWorkspace(context.projectId)
        val signedApk = File(workspace.rootDir, "build/bin/signed.apk")

        if (!signedApk.exists()) {
            return call.errorResult("No built APK found. Run run_build_pipeline first.")
        }

        val packageName = "com.vibe.generated.p${context.projectId}"
        pluginManager.launchPlugin(signedApk.absolutePath, packageName, context.projectId)

        // Wait for Inspector to bind (plugin process needs time to start)
        var inspector: com.vibe.app.plugin.IPluginInspector? = null
        for (attempt in 1..20) {
            delay(500)
            inspector = pluginManager.getInspector(context.projectId)
            if (inspector != null) break
        }

        if (inspector == null) {
            return call.errorResult("App launched but Inspector did not connect within 10s.")
        }

        // Return the initial View tree so the model can immediately see the UI
        return try {
            val viewTree = inspector.dumpViewTree()
            call.result(
                buildJsonObject {
                    put("status", JsonPrimitive("running"))
                    put("view_tree", JsonPrimitive(viewTree))
                },
            )
        } catch (e: Exception) {
            call.result(
                buildJsonObject {
                    put("status", JsonPrimitive("running"))
                    put("note", JsonPrimitive("App launched but view tree not yet available: ${e.message}"))
                },
            )
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```
git add app/src/main/kotlin/com/vibe/app/feature/agent/tool/LaunchAppTool.kt
git commit -m "feat(agent): add launch_app tool for starting built APKs in plugin mode"
```

---

### Task 2: Register `LaunchAppTool` in Hilt

**Files:**
- Modify: `app/src/main/kotlin/com/vibe/app/di/AgentToolModule.kt`

- [ ] **Step 1: Add the binding**

Add import and binding line:

```kotlin
import com.vibe.app.feature.agent.tool.LaunchAppTool

// Inside the abstract class, add:
@Binds @IntoSet abstract fun bindLaunchApp(tool: LaunchAppTool): AgentTool
```

- [ ] **Step 2: Verify full build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL (including Hilt code generation)

- [ ] **Step 3: Commit**

```
git add app/src/main/kotlin/com/vibe/app/di/AgentToolModule.kt
git commit -m "feat(agent): register launch_app tool in Hilt DI"
```

---

### Task 3: Enhance `run_build_pipeline` success output

**Files:**
- Modify: `app/src/main/kotlin/com/vibe/app/feature/agent/tool/BuildTool.kt`

- [ ] **Step 1: Add next-step hint on success**

In `RunBuildPipelineTool.execute()`, after getting the build result, enhance the success output:

```kotlin
override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
    val clean = call.arguments.optionalBoolean("clean", default = true)
    val workspace = projectManager.openWorkspace(context.projectId)
    if (clean) {
        workspace.cleanBuildCache()
    }
    val result = buildMutex.withBuildLock {
        workspace.buildProject()
    }
    val output = result.toFilteredJson().toMutableMap().let { map ->
        if (result.errorMessage == null) {
            map["hint"] = JsonPrimitive(
                "Build succeeded. Call launch_app to start the app, then use inspect_ui to verify the UI."
            )
        }
        kotlinx.serialization.json.JsonObject(map)
    }
    return call.result(
        output = output,
        isError = result.errorMessage != null,
    )
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```
git add app/src/main/kotlin/com/vibe/app/feature/agent/tool/BuildTool.kt
git commit -m "feat(agent): add next-step hint to build success output"
```

---

### Task 4: Update system prompt with auto-test guidance

**Files:**
- Modify: `app/src/main/assets/agent-system-prompt.md`

- [ ] **Step 1: Replace the Hard Rules section and add testing phase**

Replace the "Hard Rules" section (lines 152-157) and add Phase 5 after Phase 4:

Add after Phase 4:

```markdown
**Phase 5 — Verify** (when applicable)
  - After build succeeds, decide whether to verify:
    - **Skip testing** for: simple text/color changes, build-error-only fix iterations, icon-only updates
    - **Test the app** for: new features, UI layouts, user interactions, network requests, bug fixes
  - To test: call launch_app → inspect the View tree → use interact_ui for interactive elements
  - If running low on iterations (≤ 5 remaining), skip testing and finish
```

Replace Hard Rules:

```markdown
## Hard Rules
1. Use write_project_file for new/full rewrites, edit_project_file for targeted changes.
2. If running low on iterations, call run_build_pipeline immediately.
3. After build succeeds, verify the app if the task warrants it (see Phase 5). For simple fixes, stop after build succeeds.
4. Keep the final answer concise: summarize what was built and whether it was verified.
```

- [ ] **Step 2: Also update the UI Inspection section**

Replace the current "UI Inspection & Automation" section to mention launch_app:

```markdown
## UI Inspection & Automation

After a successful build, call **launch_app** to start the app in plugin mode.

**inspect_ui** — Get View hierarchy (class, ID, text, bounds, interaction state).

**interact_ui** — Simulate actions:
- click: `{"action":"click","selector":{"type":"id","value":"btn_submit"}}`
- input: `{"action":"input","selector":{"type":"id","value":"et_city"},"value":"Beijing"}`
- scroll: `{"action":"scroll","selector":{"type":"id","value":"scroll_view"},"value":"down","amount":500}`

Selectors: `id`, `text`, `text_contains`, `class` (with index). Updated View tree returned after each action.
```

- [ ] **Step 3: Commit**

```
git add app/src/main/assets/agent-system-prompt.md
git commit -m "feat(agent): add auto-test guidance and launch_app instructions to system prompt"
```

---

### Task 5: Final integration build verification

- [ ] **Step 1: Full build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Squash or bundle commit**

```
git add -A
git commit -m "feat(agent): enable auto-test flow — launch_app tool + system prompt guidance

Add launch_app tool that starts the built APK in plugin mode, waits for
Inspector binding, and returns the initial View tree. Enhance build success
output with next-step hint. Update system prompt with Phase 5 (Verify) and
auto-test decision criteria."
```
