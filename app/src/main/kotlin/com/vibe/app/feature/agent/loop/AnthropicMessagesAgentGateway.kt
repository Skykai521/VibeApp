package com.vibe.app.feature.agent.loop

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
    private val anthropicAPI: AnthropicAPI,
) : AgentModelGateway {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    override suspend fun streamTurn(request: AgentModelRequest): Flow<AgentModelEvent> = flow {
        anthropicAPI.setToken(request.platform.token)
        anthropicAPI.setAPIUrl(request.platform.apiUrl)

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

        // Per-block state: maps content block index → pending tool call info
        data class ToolUseBlock(val id: String, val name: String, val inputBuilder: StringBuilder)

        val activeToolBlocks = mutableMapOf<Int, ToolUseBlock>()
        var stopReason: StopReason? = null

        anthropicAPI.streamChatMessage(messageRequest).collect { chunk ->
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
                            chunk.delta.text?.let { emit(AgentModelEvent.OutputDelta(it)) }
                        }

                        ContentBlockType.THINKING_DELTA -> {
                            chunk.delta.thinking?.let { emit(AgentModelEvent.ThinkingDelta(it)) }
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
                    }
                }

                is MessageDeltaResponseChunk -> {
                    stopReason = chunk.delta.stopReason
                }

                is MessageStopResponseChunk -> {
                    emit(AgentModelEvent.Completed())
                }

                is ErrorResponseChunk -> {
                    emit(AgentModelEvent.Failed(chunk.error.message))
                }

                else -> Unit
            }
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
                        content = listOf(TextContent(item.text.orEmpty())),
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

    companion object {
        private const val DEFAULT_MAX_TOKENS = 16000
    }
}
