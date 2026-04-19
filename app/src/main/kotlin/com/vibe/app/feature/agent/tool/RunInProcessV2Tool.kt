package com.vibe.app.feature.agent.tool

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.vibe.app.data.database.entity.ProjectEngine
import com.vibe.app.data.repository.ProjectRepository
import com.vibe.app.feature.agent.AgentTool
import com.vibe.app.feature.agent.AgentToolCall
import com.vibe.app.feature.agent.AgentToolContext
import com.vibe.app.feature.agent.AgentToolDefinition
import com.vibe.app.feature.agent.AgentToolResult
import com.vibe.app.plugin.v2.ShadowPluginHost
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Agent tool: launch the most recently built v2 (GRADLE_COMPOSE) APK
 * inside one of VibeApp's plugin process slots and return the initial
 * View tree, so the agent can verify the UI without going through the
 * system installer.
 *
 * v2 analogue of [LaunchAppTool] (which targets v1 LEGACY projects).
 * Goes through [ShadowPluginHost] — i.e. Tencent Shadow — so plugin
 * Activities can use Fragments, nested Activity navigation, and the
 * other three components v1's hand-rolled DexClassLoader host never
 * supported.
 *
 * APK lookup: `<workspacePath>/app/build/outputs/apk/plugin/debug/app-plugin-debug.apk`
 * (the `plugin` Shadow flavor runs Shadow's bytecode transform;
 * `assemble_debug_v2` invokes `:app:assemblePluginDebug` accordingly).
 *
 * Package name lookup: parsed from `app/build.gradle.kts`'s
 * `applicationId = "..."` line (the v2 template guarantees this is
 * present and unique).
 */
@Singleton
class RunInProcessV2Tool @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val pluginHost: ShadowPluginHost,
) : AgentTool {

    override val definition = AgentToolDefinition(
        name = "run_in_process_v2",
        description = "Launch the most recently built v2 (Compose) APK inside one of VibeApp's " +
            "plugin process slots, without going through the system installer. Returns the " +
            "initial View tree on success so you can verify the UI with `inspect_ui` / " +
            "`interact_ui`. Always call `close_app` when done — don't leave the plugin in the " +
            "foreground. Fails if the project is engine LEGACY (use `launch_app` for v1) or " +
            "if VibeApp is not in the foreground.",
        inputSchema = buildJsonObject {},
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val project = projectRepository.fetchProject(context.projectId)?.project
            ?: return call.errorResult("project not found: ${context.projectId}")
        if (project.engine != ProjectEngine.GRADLE_COMPOSE) {
            return call.errorResult(
                "current project is engine=${project.engine}, not GRADLE_COMPOSE; use `launch_app` for LEGACY projects.",
            )
        }
        val rootDir = File(project.workspacePath)
        val apk = File(rootDir, "app/build/outputs/apk/plugin/debug/app-plugin-debug.apk")
        if (!apk.isFile) {
            return call.errorResult(
                "No v2 Shadow-plugin APK found at $apk. Run assemble_debug_v2 first " +
                    "(it invokes :app:assemblePluginDebug, which runs the Shadow transform).",
            )
        }
        if (!isVibeAppInForeground()) {
            return call.errorResult(
                "VibeApp is not in the foreground, so the plugin UI cannot be launched. " +
                    "Skip UI testing and finish the turn — report the build result to the user.",
            )
        }
        val packageName = readApplicationIdFromBuildGradle(rootDir)
            ?: return call.errorResult(
                "Could not read applicationId from app/build.gradle.kts under $rootDir. " +
                    "Use add_dependency_v2 / write_project_file to ensure the file is intact.",
            )

        pluginHost.launchPlugin(
            apkPath = apk.absolutePath,
            packageName = packageName,
            projectId = context.projectId,
            projectName = project.name,
        )

        // Inspector bridging through Shadow's PPS is pending Phase 5b-6.
        // Until then, getInspector() returns null and we report
        // "running" without a view tree.
        var inspector: com.vibe.app.plugin.IPluginInspector? = null
        for (attempt in 1..30) {
            delay(500)
            inspector = pluginHost.getInspector(context.projectId)
            if (inspector != null) break
        }
        return if (inspector != null) {
            try {
                val viewTree = inspector.dumpViewTree(
                    """{"scope":"visible","include_windows":true}""",
                )
                call.result(
                    buildJsonObject {
                        put("status", JsonPrimitive("running"))
                        put("packageName", JsonPrimitive(packageName))
                        put("view_tree", JsonPrimitive(viewTree))
                    },
                )
            } catch (e: Exception) {
                call.result(
                    buildJsonObject {
                        put("status", JsonPrimitive("running"))
                        put("packageName", JsonPrimitive(packageName))
                        put(
                            "note",
                            JsonPrimitive(
                                "App launched but view tree not yet available " +
                                    "(Compose first frame may still be rendering): " +
                                    "${e.message}",
                            ),
                        )
                    },
                )
            }
        } else {
            call.result(
                buildJsonObject {
                    put("status", JsonPrimitive("running"))
                    put("packageName", JsonPrimitive(packageName))
                    put(
                        "note",
                        JsonPrimitive(
                            "App launched via Shadow; UI inspection not wired " +
                                "(Phase 5b-6). Skip inspect_ui / interact_ui for now.",
                        ),
                    )
                },
            )
        }
    }

    private suspend fun isVibeAppInForeground(): Boolean = withContext(Dispatchers.Main) {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }

    /**
     * Extract `applicationId = "..."` from the v2 project's `app/build.gradle.kts`.
     * The KotlinComposeApp template guarantees this line is present.
     */
    private fun readApplicationIdFromBuildGradle(rootDir: File): String? {
        val buildGradle = File(rootDir, "app/build.gradle.kts")
        if (!buildGradle.isFile) return null
        return Regex("""applicationId\s*=\s*"([^"]+)"""")
            .find(buildGradle.readText())
            ?.groupValues
            ?.getOrNull(1)
    }
}
