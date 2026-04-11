package com.vibe.app.feature.agent.tool

import com.vibe.app.feature.agent.AgentTool
import com.vibe.app.feature.agent.AgentToolCall
import com.vibe.app.feature.agent.AgentToolContext
import com.vibe.app.feature.agent.AgentToolDefinition
import com.vibe.app.feature.agent.AgentToolResult
import com.vibe.app.feature.agent.service.BuildMutex
import com.vibe.app.feature.build.BuildFailureAnalyzer
import com.vibe.app.feature.project.ProjectManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

@Singleton
class RunBuildPipelineTool @Inject constructor(
    private val projectManager: ProjectManager,
    private val buildMutex: BuildMutex,
    private val buildFailureAnalyzer: BuildFailureAnalyzer,
) : AgentTool {

    override val definition = AgentToolDefinition(
        name = "run_build_pipeline",
        description = "Clean build cache and run the on-device Android build pipeline. Returns errors on failure.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put("clean", booleanProp("Clean build cache before building. Defaults to true."))
                },
            )
        },
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val clean = call.arguments.optionalBoolean("clean", default = true)
        val workspace = projectManager.openWorkspace(context.projectId)
        if (clean) {
            workspace.cleanBuildCache()
        }
        val result = buildMutex.withBuildLock {
            workspace.buildProject()
        }
        val analysis = buildFailureAnalyzer.analyze(result, workspace.rootDir)
        val output = result.toFilteredJson(analysis).let { json ->
            if (result.errorMessage == null) {
                // Add hint so the model knows it can launch and test the app
                JsonObject(json.toMutableMap().apply {
                    put("hint", JsonPrimitive(
                        "Build succeeded. Call launch_app to start the app, then use inspect_ui to verify the UI."
                    ))
                })
            } else {
                JsonObject(json.toMutableMap().apply {
                    analysis?.summary?.let {
                        put("hint", JsonPrimitive("Fix the primary errors in analysis first, then rebuild."))
                    }
                })
            }
        }
        return call.result(
            output = output,
            isError = result.errorMessage != null,
        )
    }
}
