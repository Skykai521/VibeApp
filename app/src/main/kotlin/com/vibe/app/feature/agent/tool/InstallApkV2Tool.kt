package com.vibe.app.feature.agent.tool

import com.vibe.app.data.database.entity.ProjectEngine
import com.vibe.app.data.repository.ProjectRepository
import com.vibe.app.feature.agent.AgentTool
import com.vibe.app.feature.agent.AgentToolCall
import com.vibe.app.feature.agent.AgentToolContext
import com.vibe.app.feature.agent.AgentToolDefinition
import com.vibe.app.feature.agent.AgentToolResult
import com.vibe.build.gradle.ApkInstaller
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File

/**
 * Agent tool: hand the most recently built v2 APK to the system
 * installer (ACTION_VIEW + FileProvider URI). Errors out if the
 * current project is LEGACY (v1 has its own install path) or if no
 * APK has been built yet.
 *
 * The system installer pops up its own user-confirmation UI; the tool
 * returns immediately after firing the Intent.
 */
@Singleton
class InstallApkV2Tool @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val apkInstaller: ApkInstaller,
) : AgentTool {

    override val definition = AgentToolDefinition(
        name = "install_apk_v2",
        description = "Hand the most recently built debug APK of the current v2 (GRADLE_COMPOSE) " +
            "project to the system installer. The user is shown a system confirmation dialog " +
            "and decides whether to install. Run `assemble_debug_v2` first.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {})
        },
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val project = projectRepository.fetchProject(context.projectId)?.project
            ?: return call.errorResult("project not found: ${context.projectId}")
        if (project.engine != ProjectEngine.GRADLE_COMPOSE) {
            return call.errorResult(
                "current project is engine=${project.engine}, not GRADLE_COMPOSE — install_apk_v2 only works for v2 projects.",
            )
        }
        val apk = File(project.workspacePath, "app/build/outputs/apk/plugin/debug/app-plugin-debug.apk")
        if (!apk.isFile) {
            return call.errorResult(
                "APK not found at $apk. Run assemble_debug_v2 first.",
            )
        }
        return try {
            apkInstaller.install(apk)
            call.result(
                buildJsonObject {
                    put("status", JsonPrimitive("INSTALLER_LAUNCHED"))
                    put("apkPath", JsonPrimitive(apk.absolutePath))
                    put("apkSizeBytes", JsonPrimitive(apk.length()))
                    put(
                        "hint",
                        JsonPrimitive(
                            "System installer launched. The user must confirm the install in the system dialog.",
                        ),
                    )
                },
            )
        } catch (t: Throwable) {
            call.errorResult("install_apk_v2 threw: ${t.javaClass.simpleName}: ${t.message}")
        }
    }
}
