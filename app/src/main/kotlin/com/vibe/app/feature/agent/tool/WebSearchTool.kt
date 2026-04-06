package com.vibe.app.feature.agent.tool

import com.vibe.app.feature.agent.AgentTool
import com.vibe.app.feature.agent.AgentToolCall
import com.vibe.app.feature.agent.AgentToolContext
import com.vibe.app.feature.agent.AgentToolDefinition
import com.vibe.app.feature.agent.AgentToolResult
import com.vibe.app.feature.agent.tool.web.WebSearchExecutor
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebSearchTool @Inject constructor(
    private val webSearchExecutor: WebSearchExecutor,
) : AgentTool {

    override val definition = AgentToolDefinition(
        name = "web_search",
        description = "Search the web for real-time information using search engines. Returns up to 5 results with title, snippet, and URL. Use fetch_web_page to read full page content.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put("query", stringProp("Search keywords"))
                },
            )
            put("required", requiredFields("query"))
        },
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val query = call.arguments.requireString("query")

        val result = webSearchExecutor.search(query)

        return result.fold(
            onSuccess = { results ->
                val output = buildJsonArray {
                    for (item in results) {
                        add(buildJsonObject {
                            put("title", JsonPrimitive(item.title))
                            put("snippet", JsonPrimitive(item.snippet))
                            put("url", JsonPrimitive(item.url))
                        })
                    }
                }
                call.result(output)
            },
            onFailure = { e ->
                call.errorResult(e.message ?: "Web search failed")
            },
        )
    }
}
