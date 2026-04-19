package com.vibe.app.feature.agent.tool

import com.vibe.app.data.database.entity.ProjectEngine
import com.vibe.app.data.repository.ProjectRepository
import com.vibe.build.gradle.GradleBuildService
import com.vibe.build.gradle.HostEvent
import com.vibe.build.gradle.diagnostic.BuildDiagnostic
import com.vibe.build.gradle.diagnostic.BuildDiagnosticFormatter
import com.vibe.build.gradle.diagnostic.BuildDiagnosticIngest
import com.vibe.build.runtime.bootstrap.BootstrapFileSystem
import com.vibe.build.runtime.bootstrap.BootstrapState
import com.vibe.build.runtime.bootstrap.RuntimeBootstrapper
import com.vibe.app.feature.agent.AgentTool
import com.vibe.app.feature.agent.AgentToolCall
import com.vibe.app.feature.agent.AgentToolContext
import com.vibe.app.feature.agent.AgentToolDefinition
import com.vibe.app.feature.agent.AgentToolResult
import javax.inject.Inject
import javax.inject.Singleton
import android.util.Log
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.io.File

/**
 * Agent tool: run `:app:assembleDebug` on the current chat's v2 GRADLE_COMPOSE
 * project via [GradleBuildService]. Errors out if the current project is
 * a LEGACY (v1) project — those use `run_build_pipeline` instead.
 *
 * Wait time: cold cache 5–10 min (Maven resolution + Kotlin daemon spinup),
 * warm cache 30–60 s. The model SHOULD warn the user before calling.
 *
 * On success: returns the absolute APK path so a follow-up `install_apk_v2`
 * can hand it to the system installer.
 */
@Singleton
class AssembleDebugV2Tool @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val gradleBuildService: GradleBuildService,
    private val fs: BootstrapFileSystem,
    private val diagnosticIngest: BuildDiagnosticIngest,
    private val diagnosticFormatter: BuildDiagnosticFormatter,
    private val bootstrapper: RuntimeBootstrapper,
) : AgentTool {

    override val definition = AgentToolDefinition(
        name = "assemble_debug_v2",
        description = "Run `:app:assembleDebug` on the current v2 (GRADLE_COMPOSE) project " +
            "via the on-device Gradle pipeline. Use after editing source files. Cold first " +
            "build is 5–10 min (Maven resolution + Kotlin daemon spinup); subsequent builds " +
            "are usually < 60s. Returns the APK path on success — follow up with " +
            "`install_apk_v2` to install. Fails immediately if the current project is a " +
            "v1 (LEGACY) project — use `run_build_pipeline` for those.",
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
                "current project ${project.projectId} is engine=${project.engine}, not GRADLE_COMPOSE; " +
                    "use run_build_pipeline for LEGACY projects.",
            )
        }
        val projectDir = File(project.workspacePath)
        if (!projectDir.isDirectory) {
            return call.errorResult("workspace_path missing on disk: $projectDir")
        }
        val gradleDist = fs.componentInstallDir("gradle-9.3.1")
        if (!gradleDist.isDirectory) {
            // First v2 build on this device — extract the toolchain
            // bundled under `assets/bootstrap/` into filesDir/usr/opt/.
            // Heavy-ish step (a few hundred MB of tar.gz to decompress)
            // but all local I/O; no network required.
            Log.i("AssembleDebugV2Tool", "Gradle dist missing; extracting bundled toolchain")
            var lastState: BootstrapState? = null
            try {
                bootstrapper.bootstrap { state ->
                    lastState = state
                    Log.d("AssembleDebugV2Tool", "bootstrap state: $state")
                }
            } catch (t: Throwable) {
                return call.errorResult(
                    "bootstrap threw ${t.javaClass.simpleName}: ${t.message}",
                )
            }
            val failure = (lastState as? BootstrapState.Failed)?.reason
            if (failure != null) {
                return call.errorResult(
                    "bootstrap failed: $failure. assets/bootstrap/ may be missing — rebuild the APK after running scripts/bootstrap/build-*.sh.",
                )
            }
            if (!gradleDist.isDirectory) {
                return call.errorResult(
                    "bootstrap completed but $gradleDist still missing — bundled manifest likely broken.",
                )
            }
        }

        return try {
            // start() is idempotent — returns "already-running" if a previous
            // call already spun up the host JVM.
            gradleBuildService.start(gradleDist)
            val events = gradleBuildService.runBuild(
                projectDirectory = projectDir,
                // `assemblePluginDebug`, not `assembleDebug`: Shadow's
                // Gradle plugin (wired into the KotlinComposeApp template
                // in Phase 5b-5) adds a "Shadow" flavor dimension with
                // `normal` + `plugin` flavors. Only the `plugin` flavor
                // variant runs Shadow's bytecode transform, which is
                // what ShadowPluginHost needs to load the APK.
                tasks = listOf(":app:assemblePluginDebug"),
                args = emptyList(),
            ).toList()
            val finish = events.filterIsInstance<HostEvent.BuildFinish>().firstOrNull()
            val err = events.filterIsInstance<HostEvent.Error>().firstOrNull()

            if (finish == null) {
                return call.errorResult(
                    "no BuildFinish event; error=${err?.exceptionClass}: ${err?.message}",
                )
            }
            if (!finish.success) {
                // Pull every Log event we got — Kotlin compiler errors can land at any
                // level. The ingest pipeline filters down to actionable diagnostics.
                val allLogLines = events.filterIsInstance<HostEvent.Log>().asSequence()
                    .map { it.text }
                val diagnostics = diagnosticIngest.ingest(
                    lines = allLogLines,
                    projectRoot = projectDir.absolutePath,
                )
                val markdown = diagnosticFormatter.format(diagnostics, projectRoot = projectDir)
                return call.result(
                    buildJsonObject {
                        put("status", JsonPrimitive("FAILED"))
                        put("durationMs", JsonPrimitive(finish.durationMs))
                        finish.failureSummary?.let { put("failureSummary", JsonPrimitive(it)) }
                        put("diagnostics_markdown", JsonPrimitive(markdown))
                        if (diagnostics.isNotEmpty()) {
                            put(
                                "diagnostics",
                                buildJsonArray {
                                    diagnostics.forEach { d ->
                                        add(buildJsonObject {
                                            put("severity", JsonPrimitive(d.severity.name))
                                            put("source", JsonPrimitive(d.source.name))
                                            d.file?.let { put("file", JsonPrimitive(it)) }
                                            d.line?.let { put("line", JsonPrimitive(it)) }
                                            d.column?.let { put("column", JsonPrimitive(it)) }
                                            put("message", JsonPrimitive(d.message))
                                        })
                                    }
                                },
                            )
                        }
                        put(
                            "hint",
                            JsonPrimitive(
                                "Read diagnostics_markdown for the cleaned errors with source snippets, " +
                                    "fix the underlying problems via edit_project_file / write_project_file, " +
                                    "then call assemble_debug_v2 again.",
                            ),
                        )
                    },
                    isError = true,
                )
            }
            val apk = File(projectDir, "app/build/outputs/apk/plugin/debug/app-plugin-debug.apk")
            call.result(
                buildJsonObject {
                    put("status", JsonPrimitive("SUCCESS"))
                    put("durationMs", JsonPrimitive(finish.durationMs))
                    put("apkPath", JsonPrimitive(apk.absolutePath))
                    put("apkExists", JsonPrimitive(apk.isFile))
                    put("apkSizeBytes", JsonPrimitive(if (apk.isFile) apk.length() else 0L))
                    put(
                        "hint",
                        JsonPrimitive(
                            "Build succeeded. Call install_apk_v2 to launch the system installer.",
                        ),
                    )
                },
            )
        } catch (t: Throwable) {
            call.errorResult("assemble_debug_v2 threw: ${t.javaClass.simpleName}: ${t.message}")
        }
    }
}
