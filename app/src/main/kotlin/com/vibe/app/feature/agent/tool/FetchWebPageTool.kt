package com.vibe.app.feature.agent.tool

import com.vibe.app.feature.agent.AgentTool
import com.vibe.app.feature.agent.AgentToolCall
import com.vibe.app.feature.agent.AgentToolContext
import com.vibe.app.feature.agent.AgentToolDefinition
import com.vibe.app.feature.agent.AgentToolResult
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import com.vibe.app.feature.agent.tool.web.WebConstants
import org.jsoup.Jsoup
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class FetchWebPageTool @Inject constructor(
    @Named("web") private val httpClient: HttpClient,
) : AgentTool {

    override val definition = AgentToolDefinition(
        name = "fetch_web_page",
        description = "Fetch and extract the main text content from a web page. Use after web_search to read full page content.",
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

        return try {
            val (title, content) = withTimeout(TIMEOUT_MS) {
                val response = httpClient.get(url) {
                    header(HttpHeaders.UserAgent, WebConstants.USER_AGENT)
                    header(HttpHeaders.AcceptLanguage, WebConstants.ACCEPT_LANGUAGE)
                }
                if (!response.status.isSuccess()) {
                    throw RuntimeException("HTTP ${response.status.value}")
                }
                val responseContentType = response.contentType()
                if (responseContentType != null &&
                    !responseContentType.match(ContentType.Text.Html) &&
                    !responseContentType.match(ContentType.Text.Plain) &&
                    !responseContentType.match(ContentType.Application.Xml)
                ) {
                    throw RuntimeException("URL does not point to an HTML page (content-type: $responseContentType)")
                }
                val html = response.bodyAsText()
                extractContent(html)
            }

            call.result(buildJsonObject {
                put("title", JsonPrimitive(title))
                put("content", JsonPrimitive(content))
                put("url", JsonPrimitive(url))
            })
        } catch (e: Exception) {
            call.errorResult("Failed to fetch page: ${e.message}")
        }
    }

    private fun extractContent(html: String): Pair<String, String> {
        val doc = Jsoup.parse(html)
        val title = doc.title()

        // Remove non-content elements
        doc.select("script, style, nav, header, footer, aside, iframe, noscript, svg").remove()

        val body = doc.body() ?: return Pair(title, "")
        var text = body.text()

        // Truncate to max length
        if (text.length > MAX_CONTENT_LENGTH) {
            text = text.take(MAX_CONTENT_LENGTH) + "\n... [truncated]"
        }

        return Pair(title, text)
    }

    companion object {
        private const val TIMEOUT_MS = 10_000L
        private const val MAX_CONTENT_LENGTH = 8_000
    }
}
