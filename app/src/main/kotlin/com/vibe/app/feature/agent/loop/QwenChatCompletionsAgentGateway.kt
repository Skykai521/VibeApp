package com.vibe.app.feature.agent.loop

import com.vibe.app.data.dto.qwen.request.QwenChatCompletionRequest
import com.vibe.app.data.dto.qwen.request.QwenChatMessage
import com.vibe.app.data.dto.qwen.request.QwenFunctionCall
import com.vibe.app.data.dto.qwen.request.QwenFunctionDefinition
import com.vibe.app.data.dto.qwen.request.QwenTool
import com.vibe.app.data.dto.qwen.request.QwenToolCall
import com.vibe.app.data.network.OpenAIAPI
import com.vibe.app.feature.agent.AgentConversationItem
import com.vibe.app.feature.agent.AgentMessageRole
import com.vibe.app.feature.agent.AgentModelEvent
import com.vibe.app.feature.agent.AgentModelGateway
import com.vibe.app.feature.agent.AgentModelRequest
import com.vibe.app.feature.agent.AgentToolCall
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

@Singleton
class QwenChatCompletionsAgentGateway @Inject constructor(
    private val openAIAPI: OpenAIAPI,
) : AgentModelGateway {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    override suspend fun streamTurn(request: AgentModelRequest): Flow<AgentModelEvent> = flow {
        openAIAPI.setToken(request.platform.token)
        openAIAPI.setAPIUrl(request.platform.apiUrl.toQwenChatCompletionsBaseUrl())

        val messages = buildMessages(request)
        val response = openAIAPI.completeQwenChatCompletion(
            QwenChatCompletionRequest(
                model = request.platform.model,
                messages = messages,
                tools = request.tools.takeIf { it.isNotEmpty() }?.map { tool ->
                    QwenTool(
                        function = QwenFunctionDefinition(
                            name = tool.name,
                            description = tool.description,
                            parameters = tool.inputSchema.toQwenChatToolSchema(),
                        ),
                    )
                },
                toolChoice = request.policy.toolChoiceMode.name.lowercase(),
                stream = false,
            ),
        )

        if (response.error != null) {
            emit(AgentModelEvent.Failed(response.error.message))
            return@flow
        }

        val choice = response.choices?.firstOrNull()
        if (choice == null) {
            emit(AgentModelEvent.Failed("Qwen chat completion returned no choices"))
            return@flow
        }

        choice.message.content
            ?.takeIf { it.isNotBlank() }
            ?.let { emit(AgentModelEvent.OutputDelta(it)) }

        choice.message.toolCalls.orEmpty().forEach { toolCall ->
            emit(AgentModelEvent.ToolCallReady(toolCall.toAgentToolCall(json)))
        }

        emit(AgentModelEvent.Completed())
    }

    private fun buildMessages(request: AgentModelRequest): List<QwenChatMessage> {
        val messages = mutableListOf<QwenChatMessage>()

        request.instructions
            ?.takeIf { it.isNotBlank() }
            ?.let { instructions ->
                messages += QwenChatMessage(
                    role = "system",
                    content = instructions,
                )
            }

        request.fullConversation.forEach { item ->
            when (item.role) {
                AgentMessageRole.USER -> messages += QwenChatMessage(
                    role = "user",
                    content = item.text.orEmpty(),
                )

                AgentMessageRole.ASSISTANT -> messages += QwenChatMessage(
                    role = "assistant",
                    content = item.text,
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
                    content = item.payload?.toString() ?: item.text.orEmpty(),
                    toolCallId = item.toolCallId,
                )

                AgentMessageRole.SYSTEM -> Unit
            }
        }

        return messages
    }
}

private fun String.toQwenChatCompletionsBaseUrl(): String {
    val trimmed = trimEnd('/')
    return when {
        "/api/v2/apps/protocols/compatible-mode" in trimmed ->
            trimmed.replace("/api/v2/apps/protocols/compatible-mode", "/compatible-mode/v1")

        trimmed.endsWith("/compatible-mode/v1") -> trimmed
        else -> trimmed
    }
}

private fun kotlinx.serialization.json.JsonElement.toQwenChatToolSchema(): kotlinx.serialization.json.JsonElement {
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
