package com.vibe.app.feature.agent.loop

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.vibe.app.data.dto.anthropic.common.ImageContent
import com.vibe.app.data.dto.anthropic.common.ImageSource
import com.vibe.app.data.dto.anthropic.common.ImageSourceType
import com.vibe.app.data.dto.anthropic.common.MediaType
import com.vibe.app.data.dto.anthropic.common.MessageContent
import com.vibe.app.data.dto.anthropic.common.MessageRole
import com.vibe.app.data.dto.anthropic.common.TextContent
import com.vibe.app.data.dto.anthropic.common.ToolResultContent
import com.vibe.app.data.dto.anthropic.common.ToolUseContent
import com.vibe.app.data.dto.anthropic.request.AnthropicTool
import com.vibe.app.data.dto.anthropic.request.AnthropicToolChoice
import com.vibe.app.data.dto.anthropic.request.InputMessage
import com.vibe.app.data.dto.anthropic.request.MessageRequest
import com.vibe.app.data.dto.anthropic.response.ContentBlockType
import com.vibe.app.data.dto.anthropic.response.ContentDeltaResponseChunk
import com.vibe.app.data.dto.anthropic.response.ContentStartResponseChunk
import com.vibe.app.data.dto.anthropic.response.ContentStopResponseChunk
import com.vibe.app.data.dto.anthropic.response.ErrorResponseChunk
import com.vibe.app.data.dto.anthropic.response.MessageDeltaResponseChunk
import com.vibe.app.data.dto.anthropic.response.MessageStopResponseChunk
import com.vibe.app.data.dto.anthropic.response.StopReason
import com.vibe.app.data.network.AnthropicAPI
import com.vibe.app.feature.agent.AgentConversationItem
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * [AgentModelGateway] implementation for the Anthropic Messages API.
 *
 * Key differences vs OpenAI Responses API:
 * - Stateless: every turn must carry the full conversation history.
 * - Tool calling uses content blocks inside messages instead of a separate output array.
 * - Tool results are sent as `tool_result` content blocks in a user-role message.
 * - Input JSON for a tool call is streamed incrementally via `input_json_delta` deltas.
 *
 * This gateway reads [AgentModelRequest.fullConversation] (the entire accumulated history)
 * and builds a proper Anthropic `messages` array from it.
 */
@Singleton
class AnthropicMessagesAgentGateway @Inject constructor(
    @ApplicationContext private val context: Context,
    private val anthropicAPI: AnthropicAPI,
    private val diagnosticLogger: ChatDiagnosticLogger,
) : AgentModelGateway {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    override suspend fun streamTurn(request: AgentModelRequest): Flow<AgentModelEvent> = flow {
        anthropicAPI.setToken(request.platform.token)
        anthropicAPI.setAPIUrl(request.platform.apiUrl)
        val trace = ModelExecutionTrace()

        val messages = buildMessages(request.fullConversation)
        val tools = request.tools
            .takeIf { it.isNotEmpty() }
            ?.map { AnthropicTool(name = it.name, description = it.description, inputSchema = it.inputSchema) }

        val messageRequest = MessageRequest(
            model = request.platform.model,
            messages = messages,
            maxTokens = DEFAULT_MAX_TOKENS,
            stream = true,
            systemPrompt = request.instructions,
            temperature = request.platform.temperature,
            topP = request.platform.topP,
            tools = tools,
            toolChoice = tools?.let { buildToolChoice(request.policy.toolChoiceMode) },
        )
        trace.markRequestPrepared()
        val requestContext = request.diagnosticContext?.copy(platformUid = request.platform.uid)?.let { diagnosticContext ->
            ModelRequestDiagnosticContext(
                diagnosticContext = diagnosticContext,
                providerType = request.platform.compatibleType.toDiagnosticProviderType(),
                apiFamily = "messages",
                model = request.platform.model,
                stream = true,
                reasoningEnabled = request.platform.reasoning,
                messageCount = messages.size,
                toolCount = request.tools.size.takeIf { it > 0 },
                toolChoiceMode = request.policy.toolChoiceMode.name.lowercase(),
                systemPromptPresent = !request.instructions.isNullOrBlank(),
                systemPromptChars = request.instructions?.length?.takeIf { it > 0 },
            )
        }

        // Per-block state: maps content block index → pending tool call info
        data class ToolUseBlock(val id: String, val name: String, val inputBuilder: StringBuilder)

        val activeToolBlocks = mutableMapOf<Int, ToolUseBlock>()
        var stopReason: StopReason? = null

        anthropicAPI.streamChatMessage(messageRequest, requestContext, trace).collect { chunk ->
            when (chunk) {
                is ContentStartResponseChunk -> {
                    if (chunk.contentBlock.type == ContentBlockType.TOOL_USE) {
                        val id = requireNotNull(chunk.contentBlock.id) {
                            "tool_use content_block_start missing id"
                        }
                        val name = requireNotNull(chunk.contentBlock.name) {
                            "tool_use content_block_start missing name"
                        }
                        activeToolBlocks[chunk.index] = ToolUseBlock(id, name, StringBuilder())
                    }
                }

                is ContentDeltaResponseChunk -> {
                    when (chunk.delta.type) {
                        ContentBlockType.DELTA -> {
                            chunk.delta.text?.let {
                                trace.markOutput(it)
                                emit(AgentModelEvent.OutputDelta(it))
                            }
                        }

                        ContentBlockType.THINKING_DELTA -> {
                            chunk.delta.thinking?.let {
                                trace.markThinking(it)
                                emit(AgentModelEvent.ThinkingDelta(it))
                            }
                        }

                        ContentBlockType.INPUT_JSON_DELTA -> {
                            activeToolBlocks[chunk.index]?.inputBuilder
                                ?.append(chunk.delta.partialJson.orEmpty())
                        }

                        else -> Unit
                    }
                }

                is ContentStopResponseChunk -> {
                    activeToolBlocks.remove(chunk.index)?.let { block ->
                        val arguments = block.inputBuilder.toString()
                            .takeIf { it.isNotBlank() }
                            ?.let { runCatching { json.parseToJsonElement(it) }.getOrElse { buildJsonObject {} } }
                            ?: buildJsonObject {}
                        emit(
                            AgentModelEvent.ToolCallReady(
                                AgentToolCall(
                                    id = block.id,
                                    name = block.name,
                                    arguments = arguments,
                                ),
                            ),
                        )
                        trace.markToolCall()
                    }
                }

                is MessageDeltaResponseChunk -> {
                    stopReason = chunk.delta.stopReason
                }

                is MessageStopResponseChunk -> {
                    trace.markCompleted(stopReason?.name?.lowercase())
                    emit(AgentModelEvent.Completed())
                }

                is ErrorResponseChunk -> {
                    trace.markFailed("provider_error", chunk.error.message)
                    emit(AgentModelEvent.Failed(chunk.error.message))
                }

                else -> Unit
            }
        }
        if (requestContext != null) {
            diagnosticLogger.logModelResponse(requestContext, trace, trace.errorKind == null)
            diagnosticLogger.logLatencyBreakdown(requestContext, trace)
        }
    }

    /**
     * Converts the full accumulated conversation into Anthropic [InputMessage] list.
     *
     * Mapping rules:
     * - USER items → user message with [TextContent]
     * - ASSISTANT items → assistant message with [TextContent] and/or [ToolUseContent] blocks
     * - TOOL items → must be grouped into a single user message with [ToolResultContent] blocks
     *   (Anthropic requires all tool results for a turn in one user message)
     * - SYSTEM items are excluded (system prompt is passed via [MessageRequest.systemPrompt])
     */
    private fun buildMessages(conversation: List<AgentConversationItem>): List<InputMessage> {
        val messages = mutableListOf<InputMessage>()
        var i = 0
        while (i < conversation.size) {
            val item = conversation[i]
            when (item.role) {
                AgentMessageRole.SYSTEM -> {
                    // System prompt is handled separately via MessageRequest.systemPrompt.
                    i++
                }

                AgentMessageRole.USER -> {
                    messages += InputMessage(
                        role = MessageRole.USER,
                        content = buildUserContent(item),
                    )
                    i++
                }

                AgentMessageRole.ASSISTANT -> {
                    val content = buildList {
                        item.text?.takeIf { it.isNotBlank() }?.let { add(TextContent(it)) }
                        item.toolCalls?.forEach { call ->
                            add(ToolUseContent(id = call.id, name = call.name, input = call.arguments))
                        }
                    }
                    if (content.isNotEmpty()) {
                        messages += InputMessage(role = MessageRole.ASSISTANT, content = content)
                    }
                    i++
                }

                AgentMessageRole.TOOL -> {
                    // Consume all consecutive TOOL items into a single user message.
                    val toolResultBlocks = buildList {
                        while (i < conversation.size && conversation[i].role == AgentMessageRole.TOOL) {
                            val t = conversation[i]
                            add(
                                ToolResultContent(
                                    toolUseId = requireNotNull(t.toolCallId) {
                                        "TOOL item missing toolCallId"
                                    },
                                    content = t.payload?.toString() ?: t.text.orEmpty(),
                                    isError = null,
                                ),
                            )
                            i++
                        }
                    }
                    messages += InputMessage(role = MessageRole.USER, content = toolResultBlocks)
                }
            }
        }
        return messages
    }

    private fun buildToolChoice(mode: AgentToolChoiceMode): AnthropicToolChoice {
        return when (mode) {
            AgentToolChoiceMode.AUTO -> AnthropicToolChoice(type = "auto")
            AgentToolChoiceMode.REQUIRED -> AnthropicToolChoice(type = "any")
            AgentToolChoiceMode.NONE -> AnthropicToolChoice(type = "none")
        }
    }

    private fun buildUserContent(item: AgentConversationItem): List<MessageContent> = buildList {
        item.attachments.forEach { path ->
            val mimeType = FileUtils.getMimeType(context, path)
            val mediaType = mimeTypeToMediaType(mimeType) ?: return@forEach
            val base64 = FileUtils.readAndEncodeFile(context, path) ?: return@forEach
            add(ImageContent(source = ImageSource(type = ImageSourceType.BASE64, mediaType = mediaType, data = base64)))
        }
        add(TextContent(item.text.orEmpty()))
    }

    private fun mimeTypeToMediaType(mimeType: String): MediaType? = when (mimeType.lowercase()) {
        "image/jpeg" -> MediaType.JPEG
        "image/png" -> MediaType.PNG
        "image/gif" -> MediaType.GIF
        "image/webp" -> MediaType.WEBP
        else -> null
    }

    companion object {
        private const val DEFAULT_MAX_TOKENS = 16000
    }
}
