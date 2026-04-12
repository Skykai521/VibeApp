package com.vibe.app.feature.agent.tool

import com.vibe.app.feature.agent.AgentTool
import com.vibe.app.feature.agent.AgentToolCall
import com.vibe.app.feature.agent.AgentToolContext
import com.vibe.app.feature.agent.AgentToolDefinition
import com.vibe.app.feature.agent.AgentToolResult
import com.vibe.app.feature.project.ProjectManager
import com.vibe.app.feature.project.VibeProjectDirs
import com.vibe.app.feature.project.memo.MemoLoader
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

@Singleton
class GetProjectMemoTool @Inject constructor(
    private val projectManager: ProjectManager,
    private val memoLoader: MemoLoader,
) : AgentTool {

    override val definition = AgentToolDefinition(
        name = "get_project_memo",
        description = "Re-fetch the current project memo (intent + outline). The memo is " +
            "already in your system prompt at turn start; only call this if you suspect " +
            "context compaction has dropped it mid-conversation.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject { })
        },
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        if (context.projectId.isBlank()) {
            return call.errorResult("No project context available")
        }
        val workspace = projectManager.openWorkspace(context.projectId)
        val vibeDirs = VibeProjectDirs.fromWorkspaceRoot(workspace.rootDir)
        val memo = memoLoader.load(vibeDirs)
        val text = if (memo == null) {
            "<project-memo>(no memo yet)</project-memo>"
        } else {
            MemoLoader.assembleForPrompt(memo)
        }
        return call.result(buildJsonObject {
            put("memo", JsonPrimitive(text))
        })
    }
}
