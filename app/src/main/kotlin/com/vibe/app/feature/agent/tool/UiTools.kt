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

@Singleton
class InspectUiTool @Inject constructor(
    private val pluginManager: PluginManager,
) : AgentTool {

    override val definition = AgentToolDefinition(
        name = "inspect_ui",
        description = "Get the View tree of the currently running plugin UI.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {})
            put("required", buildJsonArray {})
        },
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val inspector = pluginManager.getInspector(context.projectId)
            ?: return call.result(
                buildJsonObject {
                    put("error", JsonPrimitive("plugin not running"))
                    put("hint", JsonPrimitive("Please build and run the app first using run_build_pipeline."))
                },
            )
        return try {
            call.result(JsonPrimitive(inspector.dumpViewTree()))
        } catch (e: Exception) {
            call.errorResult(e.message ?: "inspection failed")
        }
    }
}

@Singleton
class CloseAppTool @Inject constructor(
    private val pluginManager: PluginManager,
) : AgentTool {

    override val definition = AgentToolDefinition(
        name = "close_app",
        description = "Close the running plugin app and return to VibeApp. " +
            "Call this after you finish inspecting or testing the UI.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {})
            put("required", buildJsonArray {})
        },
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        return try {
            pluginManager.finishPluginAndReturn(context.projectId)
            call.result(
                buildJsonObject {
                    put("status", JsonPrimitive("closed"))
                },
            )
        } catch (e: Exception) {
            call.errorResult(e.message ?: "failed to close app")
        }
    }
}

@Singleton
class InteractUiTool @Inject constructor(
    private val pluginManager: PluginManager,
) : AgentTool {

    override val definition = AgentToolDefinition(
        name = "interact_ui",
        description = "Perform a UI action (click, input, scroll) on the running plugin and return the updated View tree.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                put("action", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Action type: click, input, scroll"))
                    put("enum", buildJsonArray {
                        add(JsonPrimitive("click"))
                        add(JsonPrimitive("input"))
                        add(JsonPrimitive("scroll"))
                    })
                })
                put("selector", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("""JSON selector: {"type":"id","value":"btn_submit"} or {"type":"text","value":"Submit"}"""))
                })
                put("value", stringProp("Text to input (for action=input) or scroll direction up/down/left/right (for action=scroll)"))
                put("amount", intProp("Scroll distance in pixels (for action=scroll, default 500)"))
            })
            put("required", requiredFields("action", "selector"))
        },
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val inspector = pluginManager.getInspector(context.projectId)
            ?: return call.result(
                buildJsonObject {
                    put("error", JsonPrimitive("plugin not running"))
                    put("hint", JsonPrimitive("Please build and run the app first using run_build_pipeline."))
                },
            )

        val action = call.arguments.optionalString("action") ?: ""
        val selector = call.arguments.optionalString("selector") ?: ""
        val value = call.arguments.optionalString("value") ?: ""
        val amount = call.arguments.optionalInt("amount", default = 500)

        return try {
            val actionResult = when (action) {
                "click" -> inspector.performClick(selector)
                "input" -> inspector.inputText(selector, value)
                "scroll" -> inspector.scroll(selector, value, amount)
                else -> """{"success":false,"error":"unknown action: $action"}"""
            }

            val viewTree = try { inspector.dumpViewTree() } catch (_: Exception) { null }

            call.result(
                buildJsonObject {
                    put("result", JsonPrimitive(actionResult))
                    if (viewTree != null) {
                        put("view_tree", JsonPrimitive(viewTree))
                    }
                },
            )
        } catch (e: Exception) {
            call.errorResult(e.message ?: "interaction failed")
        }
    }
}
