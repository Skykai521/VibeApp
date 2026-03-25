package com.vibe.app.feature.agent.loop

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.vibe.app.data.dto.openai.request.ResponseContentPart
import com.vibe.app.data.dto.openai.request.ResponseInputContent
import com.vibe.app.data.dto.openai.request.ResponseInputItem
import com.vibe.app.data.dto.openai.request.ResponseTool
import com.vibe.app.data.dto.openai.request.ResponsesRequest
import com.vibe.app.data.dto.openai.response.OutputItemDoneEvent
import com.vibe.app.data.dto.openai.response.OutputTextDeltaEvent
import com.vibe.app.data.dto.openai.response.ReasoningSummaryTextDeltaEvent
import com.vibe.app.data.dto.openai.response.ResponseCompletedEvent
import com.vibe.app.data.dto.openai.response.ResponseCreatedEvent
import com.vibe.app.data.dto.openai.response.ResponseErrorEvent
import com.vibe.app.data.dto.openai.response.ResponseFailedEvent
import com.vibe.app.data.network.OpenAIAPI
import com.vibe.app.feature.agent.AgentConversationItem
import com.vibe.app.feature.agent.AgentMessageRole
import com.vibe.app.feature.agent.AgentModelEvent
import com.vibe.app.feature.agent.AgentModelGateway
import com.vibe.app.feature.agent.AgentModelRequest
import com.vibe.app.feature.agent.AgentToolCall
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

@Singleton
class OpenAiResponsesAgentGateway @Inject constructor(
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
        openAIAPI.setAPIUrl(request.platform.apiUrl)
        val trace = ModelExecutionTrace()

        val responseRequest = ResponsesRequest(
            model = request.platform.model,
            input = request.conversation.map(::toResponseInputItem),
            previousResponseId = request.previousResponseId,
            stream = true,
            instructions = request.instructions,
            tools = request.tools.takeIf { it.isNotEmpty() }?.map { tool ->
                ResponseTool(
                    name = tool.name,
                    description = tool.description,
                    parameters = tool.inputSchema.takeUnless { it.isEmptyJsonObject() },
                    strict = null,
                )
            },
            toolChoice = request.policy.toolChoiceMode.name.lowercase(),
        )
        trace.markRequestPrepared()
        val requestContext = request.diagnosticContext?.copy(platformUid = request.platform.uid)?.let { diagnosticContext ->
            ModelRequestDiagnosticContext(
                diagnosticContext = diagnosticContext,
                providerType = request.platform.compatibleType.toDiagnosticProviderType(),
                apiFamily = "responses",
                model = request.platform.model,
                stream = true,
                reasoningEnabled = request.platform.reasoning,
                messageCount = request.conversation.size,
                toolCount = request.tools.size.takeIf { it > 0 },
                toolChoiceMode = request.policy.toolChoiceMode.name.lowercase(),
                systemPromptPresent = !request.instructions.isNullOrBlank(),
                systemPromptChars = request.instructions?.length?.takeIf { it > 0 },
            )
        }

        var lastResponseId: String? = request.previousResponseId

        openAIAPI.streamResponses(responseRequest, requestContext, trace).collect { event ->
            when (event) {
                is ReasoningSummaryTextDeltaEvent -> {
                    trace.markThinking(event.delta)
                    emit(AgentModelEvent.ThinkingDelta(event.delta))
                }
                is OutputTextDeltaEvent -> {
                    trace.markOutput(event.delta)
                    emit(AgentModelEvent.OutputDelta(event.delta))
                }
                is ResponseCreatedEvent -> lastResponseId = event.response.id
                is ResponseCompletedEvent -> {
                    lastResponseId = event.response.id
                    trace.markCompleted()
                    emit(AgentModelEvent.Completed(responseId = lastResponseId))
                }

                is OutputItemDoneEvent -> {
                    event.toToolCallOrNull(json)?.let {
                        trace.markToolCall()
                        emit(AgentModelEvent.ToolCallReady(it))
                    }
                }

                is ResponseFailedEvent -> {
                    trace.markFailed("provider_error", event.response.error?.message)
                    emit(
                        AgentModelEvent.Failed(
                            event.response.error?.message ?: "OpenAI Responses request failed",
                        ),
                    )
                }

                is ResponseErrorEvent -> {
                    trace.markFailed(
                        errorKind = if (event.code == "network_error") "network_error" else "provider_error",
                        errorMessage = event.message,
                    )
                    emit(AgentModelEvent.Failed(event.message))
                }
                else -> Unit
            }
        }
        if (requestContext != null) {
            diagnosticLogger.logModelResponse(requestContext, trace, trace.errorKind == null)
            diagnosticLogger.logLatencyBreakdown(requestContext, trace)
        }
    }

    private fun toResponseInputItem(item: AgentConversationItem): ResponseInputItem {
        return when (item.role) {
            AgentMessageRole.USER -> ResponseInputItem.message(
                role = "user",
                content = buildUserContent(item),
            )

            AgentMessageRole.ASSISTANT -> ResponseInputItem.message(
                role = "assistant",
                content = ResponseInputContent.text(item.text.orEmpty()),
            )

            AgentMessageRole.TOOL -> ResponseInputItem.functionCallOutput(
                callId = requireNotNull(item.toolCallId) { "Tool call id is required for tool outputs" },
                output = item.payload?.toString() ?: JsonPrimitive(item.text.orEmpty()).toString(),
            )

            AgentMessageRole.SYSTEM -> ResponseInputItem.message(
                role = "user",
                content = ResponseInputContent.text(item.text.orEmpty()),
            )
        }
    }

    private fun buildUserContent(item: AgentConversationItem): ResponseInputContent {
        val imageAttachments = item.attachments.filter { path ->
            FileUtils.isVisionSupportedImage(FileUtils.getMimeType(context, path))
        }
        if (imageAttachments.isEmpty()) {
            return ResponseInputContent.text(item.text.orEmpty())
        }
        val parts = buildList {
            imageAttachments.forEach { path ->
                val mimeType = FileUtils.getMimeType(context, path)
                val base64 = FileUtils.readAndEncodeFile(context, path) ?: return@forEach
                add(ResponseContentPart.image("data:$mimeType;base64,$base64"))
            }
            add(ResponseContentPart.text(item.text.orEmpty()))
        }
        return ResponseInputContent.parts(parts)
    }
}

private fun OutputItemDoneEvent.toToolCallOrNull(json: Json): AgentToolCall? {
    if (item.type != "function_call") {
        return null
    }

    val arguments = item.arguments
        ?.takeIf { it.isNotBlank() }
        ?.let {
            runCatching { json.parseToJsonElement(it) }.getOrElse {
                buildJsonObject {
                    put("raw", JsonPrimitive(item.arguments))
                }
            }
        }
        ?: buildJsonObject {}

    return AgentToolCall(
        id = item.callId ?: item.id,
        name = requireNotNull(item.name) { "Function call name is missing" },
        arguments = arguments,
    )
}
