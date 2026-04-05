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
import com.vibe.app.feature.agent.loop.compaction.CompactionStrategyType
import com.vibe.app.feature.agent.loop.compaction.ConversationCompactor
import com.vibe.app.feature.agent.loop.compaction.ProviderContextBudget
import com.vibe.app.feature.diagnostic.ChatDiagnosticLogger
import com.vibe.app.feature.diagnostic.DiagnosticLevels
import com.vibe.app.feature.project.ProjectManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Singleton
class DefaultAgentLoopCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val agentModelGateway: AgentModelGateway,
    private val agentToolRegistry: AgentToolRegistry,
    private val diagnosticLogger: ChatDiagnosticLogger,
    private val projectManager: ProjectManager,
    private val conversationCompactor: ConversationCompactor,
) : AgentLoopCoordinator {

    override suspend fun run(request: AgentLoopRequest): Flow<AgentLoopEvent> = flow {
        val loopStartedAt = System.currentTimeMillis()
        emit(
            AgentLoopEvent.LoopStarted(
                chatId = request.chatId,
                platformUid = request.platform.uid,
            ),
        )
        request.diagnosticContext?.copy(platformUid = request.platform.uid)?.let { ctx ->
            diagnosticLogger.logAgentLoopEvent(
                context = ctx,
                action = "loop_started",
                summary = "Agent loop started (max=${request.policy.maxIterations}, tools=${request.tools.size})",
                payload = buildJsonObject {
                    put("action", "loop_started")
                    put("maxIterations", request.policy.maxIterations)
                    put("toolCount", request.tools.size)
                    put("conversationItemCount", request.userMessages.size + request.assistantMessages.size)
                    put("startedAt", loopStartedAt)
                },
            )
        }

        var previousResponseId: String? = null
        val initialConversation = buildInitialConversation(request)
        // delta: new items to send for this turn (used by stateful providers like OpenAI)
        var conversationDelta: List<AgentConversationItem> = initialConversation
        // fullConversation: the entire accumulated history (used by stateless providers like Anthropic)
        val fullConversation = initialConversation.toMutableList()
        val collectedToolResults = mutableListOf<AgentToolResult>()

        for (iteration in 1..request.policy.maxIterations) {
            emit(AgentLoopEvent.ModelTurnStarted(iteration))
            request.diagnosticContext?.copy(platformUid = request.platform.uid)?.let { ctx ->
                diagnosticLogger.logAgentLoopEvent(
                    context = ctx,
                    action = "iteration_started",
                    summary = "Iteration $iteration started (${fullConversation.size} items)",
                    payload = buildJsonObject {
                        put("action", "iteration_started")
                        put("iteration", iteration)
                        put("conversationItemCount", fullConversation.size)
                    },
                )
            }
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

            val compactionResult = conversationCompactor.compact(
                items = fullConversation.toList(),
                clientType = request.platform.compatibleType,
                platform = request.platform,
            )

            if (compactionResult.strategyUsed != CompactionStrategyType.NONE) {
                request.diagnosticContext?.copy(platformUid = request.platform.uid)?.let { ctx ->
                    diagnosticLogger.logConversationCompaction(
                        context = ctx,
                        iteration = iteration,
                        strategy = compactionResult.strategyUsed.name,
                        turnsCompacted = compactionResult.turnsCompacted,
                        estimatedTokens = compactionResult.estimatedTokens,
                        itemsBefore = fullConversation.size,
                        itemsAfter = compactionResult.items.size,
                    )
                }
            }

            agentModelGateway.streamTurn(
                AgentModelRequest(
                    platform = request.platform,
                    diagnosticContext = request.diagnosticContext?.copy(platformUid = request.platform.uid),
                    conversation = conversationDelta,
                    fullConversation = compactionResult.items,
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
                request.diagnosticContext?.copy(platformUid = request.platform.uid)?.let { ctx ->
                    diagnosticLogger.logAgentLoopEvent(
                        context = ctx,
                        action = "loop_failed",
                        level = DiagnosticLevels.ERROR,
                        summary = "Agent loop failed at iteration $iteration: ${failureMessage.take(120)}",
                        payload = buildJsonObject {
                            put("action", "loop_failed")
                            put("reason", "model_error")
                            put("iteration", iteration)
                            put("totalToolCalls", collectedToolResults.size)
                            put("durationMs", System.currentTimeMillis() - loopStartedAt)
                            put("errorMessage", failureMessage.take(500))
                        },
                    )
                }
                emit(AgentLoopEvent.LoopFailed(message = failureMessage, iteration = iteration))
                return@flow
            }

            if (pendingCalls.isEmpty()) {
                request.diagnosticContext?.copy(platformUid = request.platform.uid)?.let { ctx ->
                    diagnosticLogger.logAgentLoopEvent(
                        context = ctx,
                        action = "loop_completed",
                        summary = "Agent loop completed at iteration $iteration (${collectedToolResults.size} tool calls)",
                        payload = buildJsonObject {
                            put("action", "loop_completed")
                            put("reason", "natural")
                            put("iterationsUsed", iteration)
                            put("totalToolCalls", collectedToolResults.size)
                            put("durationMs", System.currentTimeMillis() - loopStartedAt)
                            put("finalTextChars", outputBuilder.toString().trim().length)
                        },
                    )
                }
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
        request.diagnosticContext?.copy(platformUid = request.platform.uid)?.let { ctx ->
            diagnosticLogger.logAgentLoopEvent(
                context = ctx,
                action = "wind_down_started",
                level = DiagnosticLevels.WARN,
                summary = "Max iterations (${request.policy.maxIterations}) exhausted, entering wind-down",
                payload = buildJsonObject {
                    put("action", "wind_down_started")
                    put("maxIterations", request.policy.maxIterations)
                    put("totalToolCalls", collectedToolResults.size)
                    put("conversationItemCount", fullConversation.size)
                    put("durationMs", System.currentTimeMillis() - loopStartedAt)
                },
            )
        }
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
        val windDownCompaction = conversationCompactor.compact(
            items = fullConversation.toList(),
            clientType = request.platform.compatibleType,
            platform = request.platform,
        )
        agentModelGateway.streamTurn(
            AgentModelRequest(
                platform = request.platform,
                diagnosticContext = request.diagnosticContext?.copy(platformUid = request.platform.uid),
                conversation = conversationDelta,
                fullConversation = windDownCompaction.items,
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
            request.diagnosticContext?.copy(platformUid = request.platform.uid)?.let { ctx ->
                diagnosticLogger.logAgentLoopEvent(
                    context = ctx,
                    action = "loop_completed",
                    level = DiagnosticLevels.WARN,
                    summary = "Agent loop completed via wind-down (${collectedToolResults.size} tool calls)",
                    payload = buildJsonObject {
                        put("action", "loop_completed")
                        put("reason", "wind_down")
                        put("iterationsUsed", request.policy.maxIterations)
                        put("totalToolCalls", collectedToolResults.size)
                        put("durationMs", System.currentTimeMillis() - loopStartedAt)
                        put("finalTextChars", summary.length)
                    },
                )
            }
            emit(AgentLoopEvent.LoopCompleted(finalText = summary, toolResults = collectedToolResults.toList()))
        } else {
            request.diagnosticContext?.copy(platformUid = request.platform.uid)?.let { ctx ->
                diagnosticLogger.logAgentLoopEvent(
                    context = ctx,
                    action = "loop_failed",
                    level = DiagnosticLevels.ERROR,
                    summary = "Agent loop failed: exceeded ${request.policy.maxIterations} iterations with no summary",
                    payload = buildJsonObject {
                        put("action", "loop_failed")
                        put("reason", "max_iterations_exhausted")
                        put("maxIterations", request.policy.maxIterations)
                        put("totalToolCalls", collectedToolResults.size)
                        put("durationMs", System.currentTimeMillis() - loopStartedAt)
                    },
                )
            }
            emit(
                AgentLoopEvent.LoopFailed(
                    message = "Agent loop exceeded max iterations: ${request.policy.maxIterations}",
                    iteration = request.policy.maxIterations,
                ),
            )
        }
    }

    /**
     * Build the initial conversation from Room-persisted messages, applying
     * cross-turn compaction (Phase A) to keep the context within budget.
     *
     * Room messages are flat USER/ASSISTANT text pairs. A single assistant
     * message can be hundreds of KB (full agent loop output from 30 iterations).
     * Phase A applies recency-weighted truncation so older turns are summarized
     * while the most recent turn retains more detail.
     */
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

        return compactCrossTurnHistory(items, request)
    }

    /**
     * Phase A: Cross-turn compaction.
     *
     * Applies recency-weighted truncation to assistant messages loaded from Room
     * (identified by having no toolCalls). Budget allocation:
     * - 60% of initial conversation budget for system prompt + tools headroom
     * - Most recent assistant: up to [MAX_RECENT_ASSISTANT_CHARS]
     * - Second most recent: up to [MAX_OLDER_ASSISTANT_CHARS]
     * - Older assistants: structural summary of ~[MAX_SUMMARY_CHARS]
     */
    private fun compactCrossTurnHistory(
        items: List<AgentConversationItem>,
        request: AgentLoopRequest,
    ): List<AgentConversationItem> {
        val budget = ProviderContextBudget.forProvider(request.platform.compatibleType)
        // Reserve 40% of budget for system prompt, tools, and within-loop growth
        val historyBudget = (budget.maxTokens * 0.6).toInt()
        val currentTokens = ConversationContextManager.estimateTokens(items)
        if (currentTokens <= historyBudget) return items

        // Find assistant items (flat text from Room, no toolCalls) in reverse order (newest first)
        val assistantIndices = items.indices
            .filter { items[it].role == AgentMessageRole.ASSISTANT && items[it].toolCalls.isNullOrEmpty() }
            .reversed()

        if (assistantIndices.isEmpty()) return items

        val result = items.toMutableList()
        assistantIndices.forEachIndexed { rank, itemIndex ->
            val item = result[itemIndex]
            val text = item.text ?: return@forEachIndexed
            val maxChars = when (rank) {
                0 -> MAX_RECENT_ASSISTANT_CHARS   // most recent: keep more detail
                1 -> MAX_OLDER_ASSISTANT_CHARS     // second recent: moderate
                else -> MAX_SUMMARY_CHARS          // older: summary only
            }
            if (text.length > maxChars) {
                result[itemIndex] = item.copy(
                    text = text.take(maxChars) + "\n\n[... earlier content truncated for context budget]",
                    reasoningContent = null,
                )
            }
        }

        return result
    }

    companion object {
        /** Most recent assistant message from Room: preserve enough for continuity. */
        private const val MAX_RECENT_ASSISTANT_CHARS = 4000
        /** Second most recent: moderate detail. */
        private const val MAX_OLDER_ASSISTANT_CHARS = 1500
        /** Older turns: summary-level only. */
        private const val MAX_SUMMARY_CHARS = 500
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
