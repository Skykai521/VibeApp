package com.vibe.app.feature.agent.loop

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.vibe.app.data.database.entity.MessageV2
import com.vibe.app.feature.agent.AgentConversationItem
import com.vibe.app.feature.agent.AgentLoopCoordinator
import com.vibe.app.feature.agent.AgentLoopEvent
import com.vibe.app.feature.agent.AgentLoopRequest
import com.vibe.app.feature.agent.AgentMessageRole
import com.vibe.app.feature.agent.AgentModelEvent
import com.vibe.app.feature.agent.AgentModelGateway
import com.vibe.app.feature.agent.AgentModelRequest
import com.vibe.app.feature.agent.AgentToolChoiceMode
import com.vibe.app.feature.agent.AgentToolRegistry
import com.vibe.app.feature.agent.AgentToolResult
import com.vibe.app.feature.diagnostic.ChatDiagnosticLogger
import com.vibe.app.feature.project.ProjectManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

@Singleton
class DefaultAgentLoopCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val agentModelGateway: AgentModelGateway,
    private val agentToolRegistry: AgentToolRegistry,
    private val diagnosticLogger: ChatDiagnosticLogger,
    private val projectManager: ProjectManager,
) : AgentLoopCoordinator {

    private val contextManager = ConversationContextManager()

    override suspend fun run(request: AgentLoopRequest): Flow<AgentLoopEvent> = flow {
        emit(
            AgentLoopEvent.LoopStarted(
                chatId = request.chatId,
                platformUid = request.platform.uid,
            ),
        )

        var previousResponseId: String? = null
        val initialConversation = buildInitialConversation(request)
        // delta: new items to send for this turn (used by stateful providers like OpenAI)
        var conversationDelta: List<AgentConversationItem> = initialConversation
        // fullConversation: the entire accumulated history (used by stateless providers like Anthropic)
        val fullConversation = initialConversation.toMutableList()
        val collectedToolResults = mutableListOf<AgentToolResult>()

        for (iteration in 1..request.policy.maxIterations) {
            emit(AgentLoopEvent.ModelTurnStarted(iteration))
            val pendingToolResults = mutableListOf<AgentToolResult>()
            val pendingCalls = mutableListOf<com.vibe.app.feature.agent.AgentToolCall>()
            val outputBuilder = StringBuilder()
            var failureMessage: String? = null
            var turnReasoningContent: String? = null

            // Force tool use on the first iteration so the model cannot skip directly to
            // a text-only answer (which happens on turn 3+ when it has seen prior exchanges).
            val effectivePolicy = if (iteration == 1 && request.tools.isNotEmpty()) {
                request.policy.copy(toolChoiceMode = AgentToolChoiceMode.REQUIRED)
            } else {
                request.policy
            }

            agentModelGateway.streamTurn(
                AgentModelRequest(
                    platform = request.platform,
                    diagnosticContext = request.diagnosticContext?.copy(platformUid = request.platform.uid),
                    conversation = conversationDelta,
                    fullConversation = fullConversation.toList(),
                    instructions = buildInstructions(request),
                    tools = request.tools,
                    policy = effectivePolicy,
                    previousResponseId = previousResponseId,
                ),
            ).collect { event ->
                when (event) {
                    is AgentModelEvent.ThinkingDelta -> {
                        emit(AgentLoopEvent.ThinkingDelta(iteration, event.delta))
                    }

                    is AgentModelEvent.OutputDelta -> {
                        outputBuilder.append(event.delta)
                        emit(AgentLoopEvent.OutputDelta(iteration, event.delta))
                    }

                    is AgentModelEvent.ToolCallReady -> {
                        pendingCalls += event.call
                        emit(AgentLoopEvent.ToolCallDiscovered(iteration, event.call))
                    }

                    is AgentModelEvent.Completed -> {
                        previousResponseId = event.responseId ?: previousResponseId
                        if (event.reasoningContent != null) {
                            turnReasoningContent = event.reasoningContent
                        }
                    }

                    is AgentModelEvent.Failed -> {
                        failureMessage = event.message
                    }
                }
            }

            if (failureMessage != null) {
                emit(AgentLoopEvent.LoopFailed(message = failureMessage, iteration = iteration))
                return@flow
            }

            if (pendingCalls.isEmpty()) {
                emit(
                    AgentLoopEvent.LoopCompleted(
                        finalText = outputBuilder.toString().trim(),
                        toolResults = collectedToolResults.toList(),
                    ),
                )
                return@flow
            }

            // Append the assistant's turn (text + tool calls) to the full history so stateless
            // providers can reconstruct the complete conversation on the next turn.
            fullConversation += AgentConversationItem(
                role = AgentMessageRole.ASSISTANT,
                text = outputBuilder.toString().trim().takeIf { it.isNotEmpty() },
                toolCalls = pendingCalls.toList(),
                reasoningContent = turnReasoningContent,
            )

            pendingCalls.forEach { call ->
                val tool = agentToolRegistry.findTool(call.name)
                if (tool == null) {
                    val result = AgentToolResult(
                        toolCallId = call.id,
                        toolName = call.name,
                        output = buildJsonObject {
                            put("error", JsonPrimitive("Tool not found: ${call.name}"))
                        },
                        isError = true,
                    )
                    pendingToolResults += result
                    collectedToolResults += result
                    emit(AgentLoopEvent.ToolExecutionFinished(iteration, result))
                    return@forEach
                }

                emit(AgentLoopEvent.ToolExecutionStarted(iteration, call))
                val toolStartedAt = System.currentTimeMillis()
                request.diagnosticContext?.copy(platformUid = request.platform.uid)?.let { diagnosticContext ->
                    diagnosticLogger.logAgentToolStarted(
                        context = diagnosticContext,
                        iteration = iteration,
                        call = call,
                        startedAt = toolStartedAt,
                    )
                }
                val result = runCatching {
                    tool.execute(
                        call = call,
                        context = com.vibe.app.feature.agent.AgentToolContext(
                            chatId = request.chatId,
                            platformUid = request.platform.uid,
                            iteration = iteration,
                            projectId = request.projectId ?: "",
                        ),
                    )
                }.getOrElse { error ->
                    AgentToolResult(
                        toolCallId = call.id,
                        toolName = call.name,
                        output = buildJsonObject {
                            put("error", JsonPrimitive(error.message ?: "Tool execution failed"))
                        },
                        isError = true,
                    )
                }
                pendingToolResults += result
                collectedToolResults += result
                request.diagnosticContext?.copy(platformUid = request.platform.uid)?.let { diagnosticContext ->
                    diagnosticLogger.logAgentToolFinished(
                        context = diagnosticContext,
                        iteration = iteration,
                        result = result,
                        startedAt = toolStartedAt,
                    )
                }
                emit(AgentLoopEvent.ToolExecutionFinished(iteration, result))
            }

            // Build tool result items and append to full history.
            val toolResultItems = pendingToolResults.map { result ->
                AgentConversationItem(
                    role = AgentMessageRole.TOOL,
                    toolCallId = result.toolCallId,
                    toolName = result.toolName,
                    payload = result.output,
                )
            }
            fullConversation += toolResultItems

            // For stateful providers (OpenAI), only the tool results are needed as the delta.
            conversationDelta = toolResultItems
        }

        // All iterations exhausted — give the model one final chance to produce a summary
        // instead of hard-failing. Inject a wind-down message and force text-only response.
        val windDownMessage = AgentConversationItem(
            role = AgentMessageRole.USER,
            text = "[System] You have used all available iterations. Do NOT call any more tools. " +
                "Summarize what you have accomplished so far and what still needs to be done. " +
                "The user can continue in the next message.",
        )
        fullConversation += windDownMessage
        conversationDelta = listOf(windDownMessage)

        emit(AgentLoopEvent.ModelTurnStarted(request.policy.maxIterations + 1))
        val finalOutput = StringBuilder()
        agentModelGateway.streamTurn(
            AgentModelRequest(
                platform = request.platform,
                diagnosticContext = request.diagnosticContext?.copy(platformUid = request.platform.uid),
                conversation = conversationDelta,
                fullConversation = fullConversation.toList(),
                instructions = buildInstructions(request),
                tools = emptyList(),
                policy = request.policy.copy(toolChoiceMode = AgentToolChoiceMode.NONE),
                previousResponseId = previousResponseId,
            ),
        ).collect { event ->
            when (event) {
                is AgentModelEvent.OutputDelta -> {
                    finalOutput.append(event.delta)
                    emit(AgentLoopEvent.OutputDelta(request.policy.maxIterations + 1, event.delta))
                }
                is AgentModelEvent.ThinkingDelta -> {
                    emit(AgentLoopEvent.ThinkingDelta(request.policy.maxIterations + 1, event.delta))
                }
                else -> Unit
            }
        }

        val summary = finalOutput.toString().trim()
        if (summary.isNotEmpty()) {
            emit(AgentLoopEvent.LoopCompleted(finalText = summary, toolResults = collectedToolResults.toList()))
        } else {
            emit(
                AgentLoopEvent.LoopFailed(
                    message = "Agent loop exceeded max iterations: ${request.policy.maxIterations}",
                    iteration = request.policy.maxIterations,
                ),
            )
        }
    }

    private fun buildInitialConversation(request: AgentLoopRequest): List<AgentConversationItem> {
        val items = mutableListOf<AgentConversationItem>()

        request.userMessages.forEachIndexed { index, userMessage ->
            items += userMessage.toAgentConversationItem()
            val assistantForPlatform = request.assistantMessages.getOrNull(index)
                ?.firstOrNull { it.platformType == request.platform.uid }
                ?.takeIf { it.content.isNotBlank() }
            if (assistantForPlatform != null) {
                items += assistantForPlatform.toAgentConversationItem()
            }
        }

        return contextManager.trimConversation(items)
    }

    private fun MessageV2.toAgentConversationItem(): AgentConversationItem {
        val isAssistant = platformType != null
        return AgentConversationItem(
            role = if (isAssistant) AgentMessageRole.ASSISTANT else AgentMessageRole.USER,
            attachments = if (isAssistant) emptyList() else files,
            text = buildString {
                append(content)
                if (files.isNotEmpty()) {
                    append("\n\n[Files]\n")
                    append(files.joinToString(separator = "\n"))
                }
            }.trim(),
        )
    }

    private val promptTemplate: String by lazy {
        context.assets.open("agent-system-prompt.md").bufferedReader().use { it.readText() }
    }

    private suspend fun buildInstructions(request: AgentLoopRequest): String {
        val packageName = request.projectId
            ?.let { "com.vibe.generated.p$it" }
            ?: "com.vibe.generated.emptyactivity"
        val packagePath = packageName.replace('.', '/')
        val custom = request.systemPrompt
            ?.takeIf { it.isNotBlank() }
            ?: request.platform.systemPrompt?.takeIf { it.isNotBlank() }

        val isTurn2Plus = request.assistantMessages.any { msgs ->
            msgs.any { it.content.isNotBlank() }
        }

        return buildString {
            append(
                promptTemplate
                    .replace("{{PACKAGE_NAME}}", packageName)
                    .replace("{{PACKAGE_PATH}}", packagePath)
            )
            if (custom != null) {
                append("\n\n[Additional System Prompt]\n")
                append(custom)
            }
            // Auto-inject file listing on turn 2+ so the AI doesn't need to call list_project_files
            if (isTurn2Plus && request.projectId != null) {
                val files = runCatching {
                    projectManager.openWorkspace(request.projectId).listFiles()
                }.getOrNull()
                if (!files.isNullOrEmpty()) {
                    append("\n\n[Current Project Files]\n")
                    files.forEach { append("- $it\n") }
                }
            }
        }
    }
}
