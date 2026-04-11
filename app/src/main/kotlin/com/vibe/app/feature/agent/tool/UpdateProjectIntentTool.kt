package com.vibe.app.feature.agent.tool

import com.vibe.app.feature.agent.AgentTool
import com.vibe.app.feature.agent.AgentToolCall
import com.vibe.app.feature.agent.AgentToolContext
import com.vibe.app.feature.agent.AgentToolDefinition
import com.vibe.app.feature.agent.AgentToolResult
import com.vibe.app.feature.project.ProjectManager
import com.vibe.app.feature.project.VibeProjectDirs
import com.vibe.app.feature.project.memo.Intent
import com.vibe.app.feature.project.memo.IntentStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Singleton
class UpdateProjectIntentTool @Inject constructor(
    private val projectManager: ProjectManager,
    private val intentStore: IntentStore,
) : AgentTool {

    override val definition = AgentToolDefinition(
        name = "update_project_intent",
        description = "Update the project's intent memo. Call after first successful build " +
            "(greenfield) or when the user's change introduces a new architectural decision " +
            "or known limit (iterate). Do not call for cosmetic edits.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                put("appName", stringProp("App name for the memo heading."))
                put("purpose", stringProp("One-line app purpose (≤80 chars)."))
                put("keyDecisions", arrayProp("≤5 items, each ≤60 chars.", itemType = "string"))
                put("knownLimits", arrayProp("≤5 items, each ≤60 chars.", itemType = "string"))
            })
            put("required", requiredFields("appName", "purpose"))
        },
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        if (context.projectId.isBlank()) {
            return call.errorResult("No project context available")
        }
        val args = call.arguments.jsonObject
        val appName = args["appName"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return call.errorResult("appName is required")
        val purpose = args["purpose"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return call.errorResult("purpose is required")
        val keyDecisions = args["keyDecisions"]?.jsonArray
            ?.map { it.jsonPrimitive.content }
            ?: emptyList()
        val knownLimits = args["knownLimits"]?.jsonArray
            ?.map { it.jsonPrimitive.content }
            ?: emptyList()

        if (keyDecisions.size > Intent.LIST_MAX) {
            return call.errorResult("keyDecisions exceeds max ${Intent.LIST_MAX}")
        }
        if (knownLimits.size > Intent.LIST_MAX) {
            return call.errorResult("knownLimits exceeds max ${Intent.LIST_MAX}")
        }

        val workspace = projectManager.openWorkspace(context.projectId)
        val vibeDirs = VibeProjectDirs.fromWorkspaceRoot(workspace.rootDir)
        val intent = Intent(
            purpose = purpose.take(Intent.PURPOSE_MAX),
            keyDecisions = keyDecisions.map { it.take(Intent.LINE_MAX) },
            knownLimits = knownLimits.map { it.take(Intent.LINE_MAX) },
        )
        intentStore.save(vibeDirs, intent, appName)

        return call.result(buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("message", JsonPrimitive("Intent updated"))
        })
    }
}
