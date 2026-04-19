package com.vibe.app.feature.agent.tool

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.vibe.app.feature.agent.AgentTool
import com.vibe.app.feature.agent.AgentToolCall
import com.vibe.app.feature.agent.AgentToolContext
import com.vibe.app.feature.agent.AgentToolDefinition
import com.vibe.app.feature.agent.AgentToolResult
import com.vibe.app.feature.project.ProjectManager
import com.vibe.app.plugin.legacy.PluginManager
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
            "Returns the initial View tree on success. " +
            "Fails if VibeApp itself is not in the foreground — in that case stop testing and finish the turn.",
        inputSchema = buildJsonObject {},
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val workspace = projectManager.openWorkspace(context.projectId)
        val signedApk = File(workspace.rootDir, "build/bin/signed.apk")

        if (!signedApk.exists()) {
            return call.errorResult("No built APK found. Run run_build_pipeline first.")
        }

        if (!isVibeAppInForeground()) {
            return call.errorResult(
                "VibeApp is not in the foreground, so the plugin UI cannot be launched. " +
                    "Skip UI testing and finish the turn — report the build result to the user and stop calling launch_app / inspect_ui / interact_ui.",
            )
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
            val viewTree = inspector.dumpViewTree("""{"scope":"visible","include_windows":true}""")
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

    private suspend fun isVibeAppInForeground(): Boolean = withContext(Dispatchers.Main) {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }
}
