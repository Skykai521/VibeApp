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
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

@Singleton
class InspectUiTool @Inject constructor(
    private val pluginManager: PluginManager,
) : AgentTool {

    override val definition = AgentToolDefinition(
        name = "inspect_ui",
        description = "Dump the View tree of the running plugin UI. Includes dialogs, popup menus, bottom sheets, and toasts as separate windows. All parameters are optional; call with no arguments for default 'visible' filter.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                put("scope", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Node filter. 'all' = everything (except GONE), 'visible' (default) = only VISIBLE nodes, 'interactive' = only clickable/long-clickable/EditText, 'text' = only nodes with text or contentDescription. Ancestor containers of matching nodes are kept so selectors still work."))
                    put("enum", buildJsonArray {
                        add(JsonPrimitive("all"))
                        add(JsonPrimitive("visible"))
                        add(JsonPrimitive("interactive"))
                        add(JsonPrimitive("text"))
                    })
                })
                put("root", buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("description", JsonPrimitive("Optional selector to dump only a subtree. Same shape as interact_ui selectors, e.g. {\"type\":\"id\",\"value\":\"my_list\"}."))
                })
                put("max_depth", intProp("Maximum tree depth (default 15)"))
                put("include_windows", booleanProp("Include non-Activity windows (dialogs, popups, toasts). Default true."))
            })
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
            val optionsJson = call.arguments.toString()
            call.result(JsonPrimitive(inspector.dumpViewTree(optionsJson)))
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
        description = "Perform a UI action on the running plugin and return the updated View tree. " +
            "Supported actions: click, long_click, double_click, input, clear_text, scroll, scroll_to, swipe, key, wait, wait_for. " +
            "Most actions require a 'selector' object; see per-action fields below. " +
            "After each action the tool waits for the UI to settle (2 frames + 100ms) before re-dumping the tree.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                put("action", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("The action to perform."))
                    put("enum", buildJsonArray {
                        add(JsonPrimitive("click"))
                        add(JsonPrimitive("long_click"))
                        add(JsonPrimitive("double_click"))
                        add(JsonPrimitive("input"))
                        add(JsonPrimitive("clear_text"))
                        add(JsonPrimitive("scroll"))
                        add(JsonPrimitive("scroll_to"))
                        add(JsonPrimitive("swipe"))
                        add(JsonPrimitive("key"))
                        add(JsonPrimitive("wait"))
                        add(JsonPrimitive("wait_for"))
                    })
                })
                put("selector", buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put(
                        "description",
                        JsonPrimitive(
                            "View selector. Fields: " +
                                "type (id|text|text_contains|text_regex|desc|desc_contains|class), " +
                                "value (string matched according to type), " +
                                "index (optional nth match among siblings, default 0), " +
                                "clickable_only (optional boolean, only match clickable views), " +
                                "under (optional nested selector to scope the search to a subtree).",
                        ),
                    )
                })
                put("value", stringProp("Text to type for action=input."))
                put("direction", stringProp("Scroll direction for action=scroll: up|down|left|right."))
                put("amount", intProp("Scroll distance in pixels for action=scroll (default 500)."))
                put("duration_ms", intProp("Duration in ms: long_click press time (default 500), swipe total time (default 300), wait sleep time (default 500)."))
                put("clear_first", booleanProp("For action=input: clear existing text before typing (default true)."))
                put("submit", booleanProp("For action=input: dispatch the EditText's IME action after typing (default false)."))
                put("key", stringProp("Key name for action=key: back|enter|tab|search|delete."))
                put("condition", stringProp("Condition for action=wait_for: appears|disappears|clickable."))
                put("timeout_ms", intProp("Timeout for action=wait_for (default 5000, max 30000)."))
                put("from", buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("description", JsonPrimitive("Swipe start point in screen coordinates: {\"x\":int,\"y\":int}. Use with 'to'."))
                })
                put("to", buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("description", JsonPrimitive("Swipe end point in screen coordinates: {\"x\":int,\"y\":int}. Use with 'from'."))
                })
                put("from_selector", buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("description", JsonPrimitive("Swipe start as a view selector (alternative to 'from'). Use with 'to_selector'."))
                })
                put("to_selector", buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("description", JsonPrimitive("Swipe end as a view selector (alternative to 'to'). Use with 'from_selector'."))
                })
            })
            put("required", requiredFields("action"))
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
            val actionJson = call.arguments.toString()
            val actionResult = inspector.executeAction(actionJson)
            val viewTree = try {
                inspector.dumpViewTree(DEFAULT_DUMP_OPTIONS)
            } catch (_: Exception) {
                null
            }
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

    private companion object {
        const val DEFAULT_DUMP_OPTIONS = """{"scope":"visible","include_windows":true}"""
    }
}
