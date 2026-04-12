package com.vibe.app.feature.agent.tool

import com.vibe.app.feature.agent.AgentTool
import com.vibe.app.feature.agent.AgentToolCall
import com.vibe.app.feature.agent.AgentToolContext
import com.vibe.app.feature.agent.AgentToolDefinition
import com.vibe.app.feature.agent.AgentToolResult
import com.vibe.app.feature.uipattern.DesignGuideLoader
import com.vibe.app.feature.uipattern.PatternKind
import com.vibe.app.feature.uipattern.PatternLibrary
import com.vibe.app.feature.uipattern.PatternSearch
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

@Singleton
class SearchUiPatternTool @Inject constructor(
    private val library: PatternLibrary,
) : AgentTool {

    override val definition = AgentToolDefinition(
        name = "search_ui_pattern",
        description = "Search the bundled UI pattern library by keyword. " +
            "Returns a list of component blocks and screen templates you can " +
            "pass to get_ui_pattern. Use this before writing new XML from scratch.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put("keyword", stringProp("Keyword such as 'list', 'form', 'settings', 'empty'."))
                    put("kind", stringProp("Optional: 'block' | 'screen' | 'any' (default any)."))
                    put("limit", intProp("Max hits, default 10, max 30."))
                },
            )
            put("required", requiredFields("keyword"))
        },
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val keyword = call.arguments.optionalString("keyword")
            ?: return call.errorResult("keyword required")
        val kindStr = call.arguments.optionalString("kind")?.lowercase()
        val kindFilter = when (kindStr) {
            null, "", "any" -> null
            "block" -> PatternKind.BLOCK
            "screen" -> PatternKind.SCREEN
            else -> return call.errorResult("kind must be 'block', 'screen', or 'any'")
        }
        val limit = call.arguments.optionalInt("limit", 10).coerceIn(1, 30)

        val hits = PatternSearch.search(library.allHits(), keyword, kindFilter, limit)
        val output = buildJsonObject {
            put("total", JsonPrimitive(hits.size))
            put(
                "hits",
                buildJsonArray {
                    for (hit in hits) {
                        add(
                            buildJsonObject {
                                put("id", JsonPrimitive(hit.id))
                                put("kind", JsonPrimitive(hit.kind.name.lowercase()))
                                put("description", JsonPrimitive(hit.description))
                                put(
                                    "keywords",
                                    buildJsonArray { hit.keywords.forEach { add(JsonPrimitive(it)) } },
                                )
                                put(
                                    "slotNames",
                                    buildJsonArray { hit.slotNames.forEach { add(JsonPrimitive(it)) } },
                                )
                                put(
                                    "dependencies",
                                    buildJsonArray { hit.dependencies.forEach { add(JsonPrimitive(it)) } },
                                )
                            },
                        )
                    }
                },
            )
        }
        return call.result(output)
    }
}

@Singleton
class GetUiPatternTool @Inject constructor(
    private val library: PatternLibrary,
) : AgentTool {

    override val definition = AgentToolDefinition(
        name = "get_ui_pattern",
        description = "Fetch a full UI pattern by id. Returns layoutXml (with " +
            "{{slot}} placeholders), slots, usage notes, and dependencies. " +
            "ALWAYS adapt the returned XML to the task — never paste verbatim.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put("id", stringProp("Pattern id returned by search_ui_pattern."))
                },
            )
            put("required", requiredFields("id"))
        },
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val id = call.arguments.optionalString("id")
            ?: return call.errorResult("id required")
        val record = library.get(id)
            ?: return call.errorResult("Unknown pattern id '$id'. Call search_ui_pattern first.")

        val output = buildJsonObject {
            put("id", JsonPrimitive(record.id))
            put("kind", JsonPrimitive(record.kind.name.lowercase()))
            put("description", JsonPrimitive(record.description))
            put("layoutXml", JsonPrimitive(record.layoutXml))
            put(
                "slots",
                buildJsonArray {
                    for (slot in record.slots) {
                        add(
                            buildJsonObject {
                                put("name", JsonPrimitive(slot.name))
                                put("description", JsonPrimitive(slot.description))
                                put("default", JsonPrimitive(slot.default))
                            },
                        )
                    }
                },
            )
            put("usageNotes", JsonPrimitive(record.notes))
            put(
                "dependencies",
                buildJsonArray { record.dependencies.forEach { add(JsonPrimitive(it)) } },
            )
        }
        return call.result(output)
    }
}

@Singleton
class GetDesignGuideTool @Inject constructor(
    private val loader: DesignGuideLoader,
) : AgentTool {

    override val definition = AgentToolDefinition(
        name = "get_design_guide",
        description = "Fetch the full VibeApp Android design guide or a " +
            "specific section. Sections: tokens | components | layout | creative | all.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put("section", stringProp("'tokens' | 'components' | 'layout' | 'creative' | 'all'. Default 'all'."))
                },
            )
        },
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val section = call.arguments.optionalString("section") ?: "all"
        val content = try {
            loader.load(section)
        } catch (e: IllegalArgumentException) {
            return call.errorResult(e.message ?: "Invalid section")
        }
        val output = buildJsonObject {
            put("section", JsonPrimitive(section.lowercase()))
            put("content", JsonPrimitive(content))
        }
        return call.result(output)
    }
}
