package com.vibe.app.feature.agent.tool

import com.vibe.app.feature.agent.AgentTool
import com.vibe.app.feature.agent.AgentToolCall
import com.vibe.app.feature.agent.AgentToolContext
import com.vibe.app.feature.agent.AgentToolDefinition
import com.vibe.app.feature.agent.AgentToolResult
import com.vibe.app.feature.agent.tool.web.WebContentFetcher
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FetchWebPageTool @Inject constructor(
    private val webContentFetcher: WebContentFetcher,
) : AgentTool {

    override val definition = AgentToolDefinition(
        name = "fetch_web_page",
        description = "Fetch and extract the main content from a web page as Markdown. Use after web_search to read full page content.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put("url", stringProp("The URL of the web page to fetch"))
                },
            )
            put("required", requiredFields("url"))
        },
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val url = call.arguments.requireString("url")

        return webContentFetcher.fetch(url).fold(
            onSuccess = { fetched ->
                call.result(buildJsonObject {
                    put("title", JsonPrimitive(fetched.title))
                    put("content", JsonPrimitive(fetched.content))
                    put("url", JsonPrimitive(fetched.url))
                })
            },
            onFailure = { e ->
                call.errorResult("Failed to fetch page: ${e.message}")
            },
        )
    }
}
