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
import com.vibe.app.feature.agent.loop.iteration.AgentMode
import com.vibe.app.feature.agent.loop.iteration.IterationModeDetector
import com.vibe.app.feature.agent.loop.iteration.PromptAssembler
import com.vibe.app.feature.diagnostic.ChatDiagnosticLogger
import com.vibe.app.feature.diagnostic.DiagnosticLevels
import com.vibe.app.feature.project.ProjectManager
import com.vibe.app.feature.project.VibeProjectDirs
import com.vibe.app.feature.project.memo.MemoLoader
import com.vibe.app.feature.project.memo.OutlineGenerator
import com.vibe.app.feature.project.memo.ProjectMemo
import com.vibe.app.feature.project.snapshot.SnapshotManager
import com.vibe.app.feature.project.snapshot.SnapshotType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import com.vibe.app.feature.agent.AgentPlan
import com.vibe.app.feature.agent.AgentPlanStep
import com.vibe.app.feature.agent.PlanStepStatus
import com.vibe.app.feature.agent.tool.requireString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Singleton
class DefaultAgentLoopCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val agentModelGateway: AgentModelGateway,
    private val agentToolRegistry: AgentToolRegistry,
    private val diagnosticLogger: ChatDiagnosticLogger,
    private val projectManager: ProjectManager,
    private val conversationCompactor: ConversationCompactor,
    private val snapshotManager: SnapshotManager,
    private val iterationModeDetector: IterationModeDetector,
    private val memoLoader: MemoLoader,
    private val outlineGenerator: OutlineGenerator,
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

        // ─── PREPARE ──────────────────────────────────────────────────────────────
        val projectId = request.projectId
        var turnContext: TurnContext? = null
        var mode: AgentMode = AgentMode.GREENFIELD
        var memo: ProjectMemo? = null

        if (!projectId.isNullOrBlank()) {
            runCatching {
                val workspace = projectManager.openWorkspace(projectId)
                val vibeDirs = VibeProjectDirs.fromWorkspaceRoot(workspace.rootDir)
                    .also { it.ensureCreated() }
                snapshotManager.recoverPendingRestore(projectId, workspace.rootDir, vibeDirs)
                mode = iterationModeDetector.detect(projectId, vibeDirs)
                if (mode == AgentMode.ITERATE) {
                    memo = memoLoader.load(vibeDirs)
                }
                val priorTurnCount = snapshotManager.list(projectId, vibeDirs)
                    .count { it.type == SnapshotType.TURN }
                val nextTurnIndex = priorTurnCount + 1
                val label = currentUserText(request).orEmpty().take(40)
                val handle = snapshotManager.prepare(
                    projectId = projectId,
                    workspaceRoot = workspace.rootDir,
                    vibeDirs = vibeDirs,
                    type = SnapshotType.TURN,
                    label = label,
                    turnIndex = nextTurnIndex,
                )
                turnContext = TurnContext(
                    projectId = projectId,
                    workspaceRoot = workspace.rootDir,
                    vibeDirs = vibeDirs,
                    mode = mode,
                    snapshotHandle = handle,
                    turnIndex = nextTurnIndex,
                )
                request.diagnosticContext?.copy(platformUid = request.platform.uid)?.let { ctx ->
                    diagnosticLogger.logAgentLoopEvent(
                        context = ctx,
                        action = "iteration_mode_detected",
                        summary = "Mode=${mode.name}, turn=$nextTurnIndex, hasMemo=${memo != null}",
                        payload = buildJsonObject {
                            put("action", "iteration_mode_detected")
                            put("mode", mode.name)
                            put("turnIndex", nextTurnIndex)
                            put("hasMemo", memo != null)
                        },
                    )
                }
            }.onFailure { e ->
                // Swallow — a prepare failure must not crash the turn. Continue
                // in GREENFIELD without memo / snapshot.
                request.diagnosticContext?.copy(platformUid = request.platform.uid)?.let { ctx ->
                    diagnosticLogger.logAgentLoopEvent(
                        context = ctx,
                        action = "iteration_prepare_failed",
                        level = DiagnosticLevels.WARN,
                        summary = "PREPARE failed: ${e.message?.take(120)}",
                        payload = buildJsonObject {
                            put("action", "iteration_prepare_failed")
                            put("error", e.message.orEmpty().take(500))
                        },
                    )
                }
            }
        }

        // ─── TOOL LOOP (existing body, wrapped in try / finally) ──────────────────
        // collectedToolResults is hoisted here so FINALIZE can inspect it.
        val collectedToolResults = mutableListOf<AgentToolResult>()

        try {
            var previousResponseId: String? = null
            val initialConversation = buildInitialConversation(request)
            // delta: new items to send for this turn (used by stateful providers like OpenAI)
            var conversationDelta: List<AgentConversationItem> = initialConversation
            // fullConversation: the entire accumulated history (used by stateless providers like Anthropic)
            val fullConversation = initialConversation.toMutableList()
            var currentPlan: AgentPlan? = null

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
                        instructions = buildInstructions(request, currentPlan, mode, memo),
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
                    // WriteInterceptor: mark that this turn mutated the workspace so FINALIZE
                    // knows to capture a post-turn snapshot of the resulting state.
                    if (turnContext != null && call.name in WRITE_TOOL_NAMES && !turnContext.firstWriteDone) {
                        turnContext.firstWriteDone = true
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
                    // WriteInterceptor: track affected/deleted file paths for snapshot metadata.
                    if (turnContext != null && !result.isError) {
                        runCatching {
                            when (call.name) {
                                "write_project_file", "edit_project_file" -> {
                                    val path = call.arguments.requireString("path")
                                    turnContext.writtenFiles += path
                                }
                                "delete_project_file" -> {
                                    val path = call.arguments.requireString("path")
                                    turnContext.deletedFiles += path
                                }
                                // icon tools: commit was triggered above; paths not tracked individually
                            }
                        }
                        // Path-extraction failures are silent — worst case, affectedFiles is empty
                        // and the UI shows a snapshot without per-file detail. Not a correctness issue.
                    }
                    request.diagnosticContext?.copy(platformUid = request.platform.uid)?.let { diagnosticContext ->
                        diagnosticLogger.logAgentToolFinished(
                            context = diagnosticContext,
                            iteration = iteration,
                            result = result,
                            startedAt = toolStartedAt,
                        )
                    }
                    emit(AgentLoopEvent.ToolExecutionFinished(iteration, result))
                    // Handle plan tool results
                    when (call.name) {
                        "create_plan" -> {
                            parsePlanFromToolResult(result, iteration)?.let { plan ->
                                currentPlan = plan
                                emit(AgentLoopEvent.PlanCreated(iteration, plan))
                            }
                        }
                        "update_plan_step" -> {
                            currentPlan?.let { plan ->
                                updatePlanFromToolResult(plan, result)?.let { updated ->
                                    currentPlan = updated
                                    emit(AgentLoopEvent.PlanUpdated(iteration, updated))
                                }
                            }
                        }
                    }
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
                    instructions = buildInstructions(request, currentPlan, mode, memo),
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
        } finally {
            // ─── FINALIZE ─────────────────────────────────────────────────────────
            if (turnContext != null) {
                runCatching {
                    // A turn "succeeded" if at least one run_build_pipeline tool call returned
                    // isError=false. If no build tool was called, buildSucceeded=false so the
                    // snapshot is still finalized (for edit-only turns) but memo is not updated.
                    val buildSucceeded = collectedToolResults.any {
                        it.toolName == "run_build_pipeline" && !it.isError
                    }
                    if (buildSucceeded) {
                        outlineGenerator.regenerate(
                            turnContext.projectId,
                            turnContext.workspaceRoot,
                            turnContext.vibeDirs,
                        )
                        request.diagnosticContext?.copy(platformUid = request.platform.uid)?.let { ctx ->
                            diagnosticLogger.logAgentLoopEvent(
                                context = ctx,
                                action = "turn_outline_regenerated",
                                summary = "Outline regenerated for project ${turnContext.projectId}",
                                payload = buildJsonObject {
                                    put("action", "turn_outline_regenerated")
                                    put("projectId", turnContext.projectId)
                                },
                            )
                        }
                    }
                    // Capture the POST-turn workspace state so the snapshot labeled "turn N"
                    // represents the result of turn N. Skip for edit-free turns — finalize()
                    // is a no-op when nothing was committed.
                    if (turnContext.firstWriteDone) {
                        runCatching { turnContext.snapshotHandle.commit() }
                            .onFailure { e ->
                                request.diagnosticContext?.copy(platformUid = request.platform.uid)?.let { ctx ->
                                    diagnosticLogger.logAgentLoopEvent(
                                        context = ctx,
                                        action = "turn_snapshot_commit_failed",
                                        level = DiagnosticLevels.WARN,
                                        summary = "Snapshot commit failed at finalize: ${e.message?.take(120)}",
                                        payload = buildJsonObject {
                                            put("action", "turn_snapshot_commit_failed")
                                            put("error", e.message.orEmpty().take(500))
                                        },
                                    )
                                }
                            }
                    }
                    turnContext.snapshotHandle.finalize(
                        buildSucceeded = buildSucceeded,
                        affectedFiles = turnContext.writtenFiles.toList(),
                        deletedFiles = turnContext.deletedFiles.toList(),
                    )
                    snapshotManager.enforceRetention(turnContext.projectId, turnContext.vibeDirs)
                    request.diagnosticContext?.copy(platformUid = request.platform.uid)?.let { ctx ->
                        diagnosticLogger.logAgentLoopEvent(
                            context = ctx,
                            action = "turn_snapshot_finalized",
                            summary = "Snapshot ${turnContext.snapshotHandle.id} finalized (build=$buildSucceeded)",
                            payload = buildJsonObject {
                                put("action", "turn_snapshot_finalized")
                                put("snapshotId", turnContext.snapshotHandle.id)
                                put("buildSucceeded", buildSucceeded)
                                put("turnIndex", turnContext.turnIndex)
                            },
                        )
                    }
                }.onFailure { e ->
                    request.diagnosticContext?.copy(platformUid = request.platform.uid)?.let { ctx ->
                        diagnosticLogger.logAgentLoopEvent(
                            context = ctx,
                            action = "iteration_finalize_failed",
                            level = DiagnosticLevels.WARN,
                            summary = "FINALIZE failed: ${e.message?.take(120)}",
                            payload = buildJsonObject {
                                put("action", "iteration_finalize_failed")
                                put("error", e.message.orEmpty().take(500))
                            },
                        )
                    }
                }
            }
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
            // Prefer an assistant message from the current platform; if the user has
            // switched models mid-chat, fall back to any platform's reply so the new
            // model still sees what was already done (avoids repeating work).
            val assistantsForTurn = request.assistantMessages.getOrNull(index).orEmpty()
            val assistantForTurn = assistantsForTurn
                .firstOrNull { it.platformType == request.platform.uid }
                ?: assistantsForTurn.firstOrNull { it.content.isNotBlank() }
            if (assistantForTurn != null && assistantForTurn.content.isNotBlank()) {
                items += assistantForTurn.toAgentConversationItem()
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

    private fun parsePlanFromToolResult(result: AgentToolResult, iteration: Int): AgentPlan? {
        if (result.isError) return null
        return try {
            val json = result.output.jsonObject
            val summary = json["summary"]?.jsonPrimitive?.content ?: return null
            val stepsArray = json["steps"]?.jsonArray ?: return null
            val steps = stepsArray.map { element ->
                val obj = element.jsonObject
                AgentPlanStep(
                    id = obj["id"]?.jsonPrimitive?.int ?: 0,
                    description = obj["description"]?.jsonPrimitive?.content ?: "",
                    status = PlanStepStatus.PENDING,
                )
            }
            AgentPlan(summary = summary, steps = steps, createdAtIteration = iteration)
        } catch (_: Exception) {
            null
        }
    }

    private fun updatePlanFromToolResult(plan: AgentPlan, result: AgentToolResult): AgentPlan? {
        if (result.isError) return null
        return try {
            val json = result.output.jsonObject
            val stepId = json["step_id"]?.jsonPrimitive?.int ?: return null
            val statusStr = json["status"]?.jsonPrimitive?.content ?: return null
            val notes = json["notes"]?.jsonPrimitive?.content
            val newStatus = when (statusStr) {
                "COMPLETED" -> PlanStepStatus.COMPLETED
                "FAILED" -> PlanStepStatus.FAILED
                "SKIPPED" -> PlanStepStatus.SKIPPED
                else -> return null
            }
            val updatedSteps = plan.steps.map { step ->
                if (step.id == stepId) {
                    step.copy(status = newStatus, notes = notes)
                } else if (step.id == stepId + 1 && newStatus == PlanStepStatus.COMPLETED) {
                    step.copy(status = PlanStepStatus.IN_PROGRESS)
                } else {
                    step
                }
            }
            plan.copy(steps = updatedSteps)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /** Write-type tool names that trigger the lazy snapshot commit (WriteInterceptor). */
        private val WRITE_TOOL_NAMES: Set<String> = setOf(
            "write_project_file",
            "edit_project_file",
            "delete_project_file",
            "update_project_icon",
            "update_project_icon_custom",
        )

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
                if (isAssistant) {
                    // Project the prior turn's tool-call log (from `thoughts`) into the
                    // assistant text so the next iteration — especially after a model
                    // switch — can see what happened previously without suppressing
                    // legitimate fresh tool calls in the current turn.
                    buildTurnWorkSummary(thoughts)?.let { summary ->
                        append(summary)
                        append("\n\n")
                    }
                }
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

    private val iterationAppendix: String by lazy {
        context.assets.open("iteration-mode-appendix.md").bufferedReader().use { it.readText() }
    }

    /** Returns the text of the current (last) user message in the request, or null if none. */
    private fun currentUserText(request: AgentLoopRequest): String? =
        request.userMessages.lastOrNull()?.content

    private suspend fun buildInstructions(
        request: AgentLoopRequest,
        activePlan: AgentPlan? = null,
        mode: AgentMode = AgentMode.GREENFIELD,
        memo: ProjectMemo? = null,
    ): String {
        val packageName = request.projectId
            ?.let { "com.vibe.generated.p$it" }
            ?: "com.vibe.generated.emptyactivity"
        val packagePath = packageName.replace('.', '/')
        val custom = request.systemPrompt
            ?.takeIf { it.isNotBlank() }
            ?: request.platform.systemPrompt?.takeIf { it.isNotBlank() }

        val basePrompt = promptTemplate
            .replace("{{PACKAGE_NAME}}", packageName)
            .replace("{{PACKAGE_PATH}}", packagePath)

        val assembled = PromptAssembler.assemble(
            basePrompt = basePrompt,
            iterationAppendix = iterationAppendix,
            mode = mode,
            memo = memo,
        )

        return buildString {
            append(assembled)
            if (custom != null) {
                append("\n\n[Additional System Prompt]\n")
                append(custom)
            }
            if (activePlan != null) {
                append("\n\n[Active Plan]\n")
                append("Goal: ${activePlan.summary}\n")
                activePlan.steps.forEach { step ->
                    val icon = when (step.status) {
                        PlanStepStatus.COMPLETED -> "done"
                        PlanStepStatus.IN_PROGRESS -> "current"
                        PlanStepStatus.FAILED -> "failed"
                        PlanStepStatus.SKIPPED -> "skipped"
                        PlanStepStatus.PENDING -> "pending"
                    }
                    append("  [$icon] ${step.id}. ${step.description}")
                    step.notes?.let { append(" ($it)") }
                    append("\n")
                }
                append("Continue with the next pending step. Call update_plan_step after completing each step.\n")
            }
        }
    }
}
