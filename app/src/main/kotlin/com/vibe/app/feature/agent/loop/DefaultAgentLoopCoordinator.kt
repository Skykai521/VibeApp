package com.vibe.app.feature.agent.loop

import com.vibe.app.data.database.entity.MessageV2
import com.vibe.app.feature.agent.AgentConversationItem
import com.vibe.app.feature.agent.AgentLoopCoordinator
import com.vibe.app.feature.agent.AgentLoopEvent
import com.vibe.app.feature.agent.AgentLoopRequest
import com.vibe.app.feature.agent.AgentMessageRole
import com.vibe.app.feature.agent.AgentModelEvent
import com.vibe.app.feature.agent.AgentModelGateway
import com.vibe.app.feature.agent.AgentModelRequest
import com.vibe.app.feature.agent.AgentToolRegistry
import com.vibe.app.feature.agent.AgentToolResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

@Singleton
class DefaultAgentLoopCoordinator @Inject constructor(
    private val agentModelGateway: AgentModelGateway,
    private val agentToolRegistry: AgentToolRegistry,
) : AgentLoopCoordinator {

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

            agentModelGateway.streamTurn(
                AgentModelRequest(
                    platform = request.platform,
                    conversation = conversationDelta,
                    fullConversation = fullConversation.toList(),
                    instructions = buildInstructions(request),
                    tools = request.tools,
                    policy = request.policy,
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

        emit(
            AgentLoopEvent.LoopFailed(
                message = "Agent loop exceeded max iterations: ${request.policy.maxIterations}",
                iteration = request.policy.maxIterations,
            ),
        )
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

        return items
    }

    private fun MessageV2.toAgentConversationItem(): AgentConversationItem {
        return AgentConversationItem(
            role = if (platformType == null) AgentMessageRole.USER else AgentMessageRole.ASSISTANT,
            text = buildString {
                if (thoughts.isNotBlank()) {
                    append("[Thoughts]\n")
                    append(thoughts.trim())
                    append("\n\n")
                }
                append(content)
                if (files.isNotEmpty()) {
                    append("\n\n[Files]\n")
                    append(files.joinToString(separator = "\n"))
                }
            }.trim(),
        )
    }

    private fun defaultAgentInstructions(): String {
        return """
            You are VibeApp's on-device Android build agent.
            Your goal: implement the user's request, build a working APK, and report success.

            ## CRITICAL CONSTRAINTS — Read these first!

            This project uses an on-device build pipeline (ECJ compiler + D8 + AAPT2), NOT Gradle.
            Only the standard Android SDK (android.jar) is available. There are NO third-party libraries.

            ### NEVER do these:
            - NEVER use AppCompatActivity, FragmentActivity, or any androidx.* class
            - NEVER use com.google.android.material.* classes
            - NEVER modify build.gradle — it is not used by the build pipeline
            - NEVER change the package name — it MUST stay as com.vibe.generated.emptyactivity everywhere
            - NEVER change the package in AndroidManifest.xml
            - NEVER use Java lambdas (->), method references (::), or try-with-resources
            - NEVER use Theme.AppCompat.* or Theme.MaterialComponents.* — they are not available

            ### ALWAYS do these:
            - ALWAYS extend android.app.Activity (the base Activity class)
            - ALWAYS keep package com.vibe.generated.emptyactivity in all Java files
            - ALWAYS import com.vibe.generated.emptyactivity.R when referencing XML resources
            - ALWAYS use platform themes: @android:style/Theme.Material.Light.NoActionBar or similar
            - ALWAYS use View.OnClickListener with anonymous inner classes (new View.OnClickListener() { ... })

            ### Available Android SDK APIs (from android.jar):
            - android.app.Activity, android.app.AlertDialog, android.app.Service
            - android.os.Bundle, android.os.Handler, android.os.Looper, android.os.CountDownTimer
            - android.widget.* (TextView, Button, EditText, ImageView, LinearLayout, RelativeLayout, FrameLayout, GridLayout, ScrollView, Toast, SeekBar, ProgressBar, CheckBox, Switch, Spinner, ListView, etc.)
            - android.view.* (View, ViewGroup, LayoutInflater, Gravity, ViewGroup.LayoutParams, etc.)
            - android.graphics.* (Color, Canvas, Paint, Path, drawable.GradientDrawable, etc.)
            - android.content.* (Intent, Context, SharedPreferences, etc.)
            - android.animation.* (ValueAnimator, ObjectAnimator, AnimatorSet, ArgbEvaluator, etc.)
            - android.media.* (MediaPlayer, SoundPool, etc.)
            - android.net.Uri
            - java.lang.*, java.util.*, java.io.*, java.text.*

            ## Template Project Structure

            Use list_project_files to see the current state of the project at any time.
            Default files:
            - src/main/java/com/vibe/generated/emptyactivity/MainActivity.java
            - src/main/res/layout/activity_main.xml
            - src/main/res/values/strings.xml
            - src/main/AndroidManifest.xml

            ## Phased Workflow

            Phase 1 — Rename (1 iteration)
              - Call rename_project with a short descriptive name.

            Phase 2 — Write All Files (1–3 iterations)
              - Write all changed files with COMPLETE content. You may create new files (drawables, layouts, etc.).
              - Do not read files you plan to fully replace.

            Phase 3 — Build (1 iteration, MANDATORY)
              - Call run_build_pipeline. Never finish without building.

            Phase 4 — Fix Loop (repeat as needed)
              - Analyze error logs carefully. Fix only the affected files, then build again.
              - Use list_project_files if you suspect duplicate or misplaced files.
              - Use delete_project_file to remove files at wrong paths.
              - Stop when the build succeeds.

            ## Hard Rules
            1. Always send complete file content in every write call — never partial diffs.
            2. If running low on remaining iterations, call run_build_pipeline immediately.
            3. Stop only when the build succeeds or you have a clear blocking error.
            4. Keep the final answer concise: summarize what was built.
        """.trimIndent()
    }

    private fun buildInstructions(request: AgentLoopRequest): String {
        val custom = request.systemPrompt
            ?.takeIf { it.isNotBlank() }
            ?: request.platform.systemPrompt?.takeIf { it.isNotBlank() }

        return buildString {
            append(defaultAgentInstructions())
            if (custom != null) {
                append("\n\n[Additional System Prompt]\n")
                append(custom)
            }
        }
    }
}
