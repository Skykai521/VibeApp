package com.vibe.app.feature.agent.tool

import com.vibe.app.data.database.entity.ProjectEngine
import com.vibe.app.data.repository.ProjectRepository
import com.vibe.app.feature.agent.AgentTool
import com.vibe.app.feature.agent.AgentToolCall
import com.vibe.app.feature.agent.AgentToolContext
import com.vibe.app.feature.agent.AgentToolDefinition
import com.vibe.app.feature.agent.AgentToolResult
import com.vibe.build.gradle.ApkInstaller
import com.vibe.build.gradle.StandaloneApkBuilder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File

/**
 * Agent tool: build the project's standalone (`normal` Shadow flavor)
 * APK and hand it to the system installer.
 *
 * Why a dedicated build here: `assemble_debug_v2` builds the `plugin`
 * flavor with Shadow's bytecode transform applied. That APK's
 * Activities extend `ShadowActivity`, which needs Shadow's runtime
 * classes loaded by `ShadowPluginHost` at launch time. Installing it
 * standalone would crash on first Activity start (`ClassNotFoundException:
 * com.tencent.shadow.core.runtime.ShadowActivity`).
 *
 * The `normal` flavor has NO Shadow transform — Activities stay as
 * plain `android.app.Activity`, so the installed APK runs normally
 * from the launcher. We always build it ourselves, never assume it's
 * already there, so the agent can chain run_in_process_v2 → install_apk_v2
 * without a separate assemble step.
 *
 * Build time: cold `:app:assembleNormalDebug` after a preceding
 * `:app:assemblePluginDebug` reuses ~80% of the Kotlin compile output
 * (same source set, just a different transform). Expect 5–15s on warm
 * cache, up to ~60s cold.
 */
@Singleton
class InstallApkV2Tool @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val apkInstaller: ApkInstaller,
    private val standaloneApkBuilder: StandaloneApkBuilder,
) : AgentTool {

    override val definition = AgentToolDefinition(
        name = "install_apk_v2",
        description = "Build a STANDALONE APK of the current v2 (GRADLE_COMPOSE) project and " +
            "hand it to the system installer so the user can install it as a regular app on " +
            "their phone. Runs `:app:assembleNormalDebug` internally (the non-Shadow flavor — " +
            "never use the plugin-flavor APK for standalone install, it crashes at launch). " +
            "Do NOT run `assemble_debug_v2` first — the plugin build doesn't produce the " +
            "normal-flavor APK this tool needs. The user is shown a system confirmation dialog " +
            "and decides whether to install. Build time: 5–15s warm, up to 60s cold.",
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
        val workspaceDir = File(project.workspacePath)
        if (!workspaceDir.isDirectory) {
            return call.errorResult("workspace_path missing on disk: $workspaceDir")
        }
        val apk = try {
            standaloneApkBuilder.buildStandaloneApk(workspaceDir)
        } catch (t: Throwable) {
            return call.errorResult(
                "install_apk_v2: building normal-flavor APK failed: " +
                    "${t.javaClass.simpleName}: ${t.message ?: "(no message)"}",
            )
        }
        return try {
            apkInstaller.install(apk)
            call.result(
                buildJsonObject {
                    put("status", JsonPrimitive("INSTALLER_LAUNCHED"))
                    put("apkPath", JsonPrimitive(apk.absolutePath))
                    put("apkSizeBytes", JsonPrimitive(apk.length()))
                    put("flavor", JsonPrimitive("normal"))
                    put(
                        "hint",
                        JsonPrimitive(
                            "Standalone (non-Shadow) APK built and system installer launched. " +
                                "The user must confirm in the system dialog. The installed app " +
                                "runs directly from the launcher — no VibeApp host required.",
                        ),
                    )
                },
            )
        } catch (t: Throwable) {
            call.errorResult("install_apk_v2 threw: ${t.javaClass.simpleName}: ${t.message}")
        }
    }
}
