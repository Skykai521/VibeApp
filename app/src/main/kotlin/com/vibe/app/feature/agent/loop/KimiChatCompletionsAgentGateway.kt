package com.vibe.app.feature.agent.loop

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.vibe.app.data.dto.qwen.request.QwenChatCompletionRequest
import com.vibe.app.data.dto.qwen.request.QwenChatMessage
import com.vibe.app.data.dto.qwen.request.QwenFunctionCall
import com.vibe.app.data.dto.qwen.request.QwenFunctionDefinition
import com.vibe.app.data.dto.qwen.request.QwenTool
import com.vibe.app.data.dto.qwen.request.QwenToolCall
import com.vibe.app.data.dto.qwen.request.qwenTextContent
import com.vibe.app.data.network.OpenAIAPI
import com.vibe.app.feature.agent.AgentMessageRole
import com.vibe.app.feature.agent.AgentModelEvent
import com.vibe.app.feature.agent.AgentModelGateway
import com.vibe.app.feature.agent.AgentModelRequest
import com.vibe.app.feature.agent.AgentToolCall
import com.vibe.app.feature.agent.AgentToolChoiceMode
import com.vibe.app.feature.diagnostic.ChatDiagnosticLogger
import com.vibe.app.feature.diagnostic.ModelExecutionTrace
import com.vibe.app.feature.diagnostic.ModelRequestDiagnosticContext
import com.vibe.app.feature.diagnostic.toDiagnosticProviderType
import com.vibe.app.util.FileUtils
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Agent gateway for Kimi (Moonshot AI).
 *
 * Kimi is OpenAI-compatible but its thinking-enabled models (e.g. kimi-k2.5)
 * do not support `tool_choice: "required"`. This gateway always uses
 * `tool_choice: "auto"` to avoid that conflict.
 */
@Singleton
class KimiChatCompletionsAgentGateway @Inject constructor(
    @ApplicationContext private val context: Context,
    private val openAIAPI: OpenAIAPI,
    private val diagnosticLogger: ChatDiagnosticLogger,
) : AgentModelGateway {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    override suspend fun streamTurn(request: AgentModelRequest): Flow<AgentModelEvent> = flow {
        openAIAPI.setToken(request.platform.token)
        openAIAPI.setAPIUrl(request.platform.apiUrl.toKimiBaseUrl())
        val trace = ModelExecutionTrace()

        val messages = buildMessages(request)
        trace.markRequestPrepared()
        val requestContext = request.diagnosticContext?.copy(platformUid = request.platform.uid)?.let { diagnosticContext ->
            ModelRequestDiagnosticContext(
                diagnosticContext = diagnosticContext,
                providerType = request.platform.compatibleType.toDiagnosticProviderType(),
                apiFamily = "chat_completions",
                model = request.platform.model,
                stream = true,
                reasoningEnabled = request.platform.reasoning,
                messageCount = messages.size,
                toolCount = request.tools.size.takeIf { it > 0 },
                toolChoiceMode = "auto".takeIf { request.tools.isNotEmpty() },
                systemPromptPresent = true,
                systemPromptChars = request.instructions?.length?.takeIf { it > 0 },
                hasImages = request.fullConversation.any { it.attachments.isNotEmpty() },
                imageCount = request.fullConversation.sumOf { it.attachments.size }.takeIf { it > 0 },
            )
        }

        // Accumulators for streaming tool call deltas
        data class ToolCallAccumulator(
            var id: String = "",
            var name: String = "",
            val arguments: StringBuilder = StringBuilder(),
        )
        val toolCallAccumulators = mutableMapOf<Int, ToolCallAccumulator>()
        var finishReason: String? = null
        val reasoningBuilder = StringBuilder()
        var streamError: String? = null

        openAIAPI.streamQwenChatCompletion(
            QwenChatCompletionRequest(
                model = request.platform.model,
                messages = messages,
                tools = request.tools.takeIf { it.isNotEmpty() }?.map { tool ->
                    QwenTool(
                        function = QwenFunctionDefinition(
                            name = tool.name,
                            description = tool.description,
                            parameters = tool.inputSchema.toKimiToolSchema(),
                        ),
                    )
                },
                toolChoice = if (request.tools.isNotEmpty()) "auto" else null,
                stream = true,
            ),
            diagnosticContext = requestContext,
            trace = trace,
        ).collect { chunk ->
            if (chunk.error != null) {
                streamError = chunk.error.message
                trace.markFailed(chunk.error.type ?: "provider_error", chunk.error.message)
                return@collect
            }

            val choice = chunk.choices?.firstOrNull() ?: return@collect
            finishReason = choice.finishReason ?: finishReason

            // Stream text content
            choice.delta.content?.takeIf { it.isNotEmpty() }?.let { delta ->
                trace.markOutput(delta)
                emit(AgentModelEvent.OutputDelta(delta))
            }

            // Stream reasoning/thinking content
            choice.delta.reasoningContent?.takeIf { it.isNotEmpty() }?.let { delta ->
                reasoningBuilder.append(delta)
                emit(AgentModelEvent.ThinkingDelta(delta))
            }

            // Accumulate tool call deltas
            choice.delta.toolCalls?.forEach { deltaToolCall ->
                val acc = toolCallAccumulators.getOrPut(deltaToolCall.index) { ToolCallAccumulator() }
                deltaToolCall.id?.let { acc.id = it }
                deltaToolCall.function?.name?.let { acc.name = it }
                deltaToolCall.function?.arguments?.let { acc.arguments.append(it) }
            }
        }

        streamError?.let { error ->
            if (requestContext != null) {
                diagnosticLogger.logModelResponse(requestContext, trace, success = false)
                diagnosticLogger.logLatencyBreakdown(requestContext, trace)
            }
            emit(AgentModelEvent.Failed(error))
            return@flow
        }

        // Emit accumulated tool calls
        toolCallAccumulators.entries.sortedBy { it.key }.forEach { (_, acc) ->
            trace.markToolCall()
            val arguments = runCatching {
                json.parseToJsonElement(acc.arguments.toString())
            }.getOrElse {
                buildJsonObject { put("raw", JsonPrimitive(acc.arguments.toString())) }
            }
            emit(
                AgentModelEvent.ToolCallReady(
                    AgentToolCall(id = acc.id, name = acc.name, arguments = arguments),
                ),
            )
        }

        val reasoningContent = reasoningBuilder.toString().takeIf { it.isNotBlank() }
        reasoningContent?.let { trace.markThinking(it) }
        trace.finishReason = finishReason
        trace.markCompleted(finishReason)
        if (requestContext != null) {
            diagnosticLogger.logModelResponse(requestContext, trace, success = true)
            diagnosticLogger.logLatencyBreakdown(requestContext, trace)
        }
        emit(AgentModelEvent.Completed(reasoningContent = reasoningContent))
    }

    private fun buildMessages(request: AgentModelRequest): List<QwenChatMessage> {
        val messages = mutableListOf<QwenChatMessage>()
        val toolRequired = request.policy.toolChoiceMode == AgentToolChoiceMode.REQUIRED
        val hasTools = request.tools.isNotEmpty()

        val systemContent = buildString {
            request.instructions?.takeIf { it.isNotBlank() }?.let { append(it) }
            if (toolRequired && hasTools) {
                append("\n\n")
                append(TOOL_REQUIRED_INSTRUCTION)
            } else if (hasTools) {
                append("\n\n")
                append(TOOL_ENCOURAGE_INSTRUCTION)
            }
        }.trim()

        if (systemContent.isNotBlank()) {
            messages += QwenChatMessage(
                role = "system",
                content = qwenTextContent(systemContent),
            )
        }

        request.fullConversation.forEach { item ->
            when (item.role) {
                AgentMessageRole.USER -> messages += QwenChatMessage(
                    role = "user",
                    content = buildKimiUserContent(item),
                )

                AgentMessageRole.ASSISTANT -> messages += QwenChatMessage(
                    role = "assistant",
                    content = qwenTextContent(item.text),
                    reasoningContent = item.reasoningContent,
                    toolCalls = item.toolCalls
                        ?.map { toolCall ->
                            QwenToolCall(
                                id = toolCall.id,
                                function = QwenFunctionCall(
                                    name = toolCall.name,
                                    arguments = toolCall.arguments.toString(),
                                ),
                            )
                        }
                        ?.takeIf { it.isNotEmpty() },
                )

                AgentMessageRole.TOOL -> messages += QwenChatMessage(
                    role = "tool",
                    content = qwenTextContent(item.payload?.toString() ?: item.text.orEmpty()),
                    toolCallId = item.toolCallId,
                )

                AgentMessageRole.SYSTEM -> Unit
            }
        }

        return messages
    }

    private fun buildKimiUserContent(item: com.vibe.app.feature.agent.AgentConversationItem) =
        buildJsonArray {
            item.attachments.forEach { attachmentPath ->
                val mimeType = FileUtils.getMimeType(context, attachmentPath)
                if (!FileUtils.isKimiSupportedImage(mimeType)) return@forEach
                val base64 = FileUtils.readAndEncodeFile(context, attachmentPath) ?: return@forEach
                add(
                    buildJsonObject {
                        put("type", JsonPrimitive("image_url"))
                        put(
                            "image_url",
                            buildJsonObject {
                                put("url", JsonPrimitive("data:$mimeType;base64,$base64"))
                            },
                        )
                    },
                )
            }

            item.kimiTextContent()?.let { text ->
                add(
                    buildJsonObject {
                        put("type", JsonPrimitive("text"))
                        put("text", JsonPrimitive(text))
                    },
                )
            }
        }.let { content ->
            if (content.isEmpty()) {
                qwenTextContent(item.kimiTextContent().orEmpty())
            } else {
                content
            }
        }

    companion object {
        private const val TOOL_REQUIRED_INSTRUCTION =
            """## MANDATORY TOOL USE
You MUST call at least one tool in your response. Do NOT reply with only text.
Analyze the user's request and use the appropriate tools to fulfill it.
Every response MUST include one or more tool calls — a text-only answer is NOT acceptable."""

        private const val TOOL_ENCOURAGE_INSTRUCTION =
            """## IMPORTANT: Continue Using Tools
You have tools available. When the user's request requires reading, writing, or modifying project files, or building the project, you MUST use the appropriate tools instead of describing what to do in text.
Do NOT assume you already know the file contents — always use tools to read and write files."""
    }
}

private fun com.vibe.app.feature.agent.AgentConversationItem.kimiTextContent(): String? {
    val rawText = text.orEmpty()
    if (attachments.isEmpty()) return rawText.takeIf { it.isNotBlank() }
    return rawText
        .substringBefore("\n\n[Files]\n")
        .trim()
        .takeIf { it.isNotBlank() }
}

private fun String.toKimiBaseUrl(): String {
    return trimEnd('/')
}

private fun kotlinx.serialization.json.JsonElement.toKimiToolSchema(): kotlinx.serialization.json.JsonElement {
    val schemaObject = if (this is kotlinx.serialization.json.JsonObject) this else buildJsonObject {}
    val properties = schemaObject["properties"]?.jsonObject ?: buildJsonObject {}
    val required = schemaObject["required"]

    return buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", properties)
        if (required != null) {
            put("required", required)
        }
        put("additionalProperties", JsonPrimitive(false))
    }
}

private fun QwenToolCall.toAgentToolCall(json: Json): AgentToolCall {
    val arguments = runCatching {
        json.parseToJsonElement(function.arguments)
    }.getOrElse {
        buildJsonObject {
            put("raw", JsonPrimitive(function.arguments))
        }
    }

    return AgentToolCall(
        id = id,
        name = function.name,
        arguments = arguments,
    )
}
