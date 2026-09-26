package com.vibe.app.feature.agent.tool

import com.vibe.app.feature.agent.AgentTool
import com.vibe.app.feature.agent.AgentToolCall
import com.vibe.app.feature.agent.AgentToolContext
import com.vibe.app.feature.agent.AgentToolDefinition
import com.vibe.app.feature.agent.AgentToolResult
import com.vibe.app.plugin.PluginManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.json.JSONObject

/**
 * Captures a screenshot of the running plugin app (launched via launch_app) so
 * vision-capable models can see actual rendering that the view tree can't convey.
 *
 * The screenshot is written to disk by the inspector; the tool result only carries
 * the path in `attachment_paths`. The coordinator lifts that into the TOOL message's
 * attachments, and the Anthropic gateway inlines the image into the tool_result.
 * Non-vision providers ignore attachments and just see the textual note.
 */
@Singleton
class CaptureScreenshotTool @Inject constructor(
    private val pluginManager: PluginManager,
) : AgentTool {

    override val definition = AgentToolDefinition(
        name = "capture_screenshot",
        description = "Capture a screenshot of the running app (launched via launch_app). " +
            "Use it to visually verify layout, colors, and rendering that the view tree cannot show. " +
            "Models with vision receive the image; otherwise rely on inspect_ui.",
        inputSchema = buildJsonObject {},
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val inspector = pluginManager.getInspector(context.projectId)
            ?: return call.errorResult("App is not running. Call launch_app first.")
        val raw = try {
            inspector.captureScreenshot("""{"project_id":"${context.projectId}"}""")
        } catch (e: Exception) {
            return call.errorResult("Screenshot IPC failed: ${e.message}")
        }
        val json = JSONObject(raw)
        if (json.has("error")) return call.errorResult(json.getString("error"))
        return call.result(
            buildJsonObject {
                put("status", JsonPrimitive("captured"))
                put("width", JsonPrimitive(json.optInt("width")))
                put("height", JsonPrimitive(json.optInt("height")))
                put("note", JsonPrimitive("Screenshot attached for vision-capable models; otherwise use inspect_ui."))
                put("attachment_paths", buildJsonArray { add(JsonPrimitive(json.getString("path"))) })
            },
        )
    }
}
