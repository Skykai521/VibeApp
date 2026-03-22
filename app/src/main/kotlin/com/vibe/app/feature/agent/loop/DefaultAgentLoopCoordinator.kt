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
import com.vibe.app.feature.agent.AgentToolChoiceMode
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

        return contextManager.trimConversation(items)
    }

    private fun MessageV2.toAgentConversationItem(): AgentConversationItem {
        val isAssistant = platformType != null
        return AgentConversationItem(
            role = if (isAssistant) AgentMessageRole.ASSISTANT else AgentMessageRole.USER,
            text = buildString {
                append(content)
                if (files.isNotEmpty()) {
                    append("\n\n[Files]\n")
                    append(files.joinToString(separator = "\n"))
                }
                // For assistant messages, extract tool usage lines from thoughts
                // so the model can see what tools were used in prior turns.
                if (isAssistant && thoughts.isNotBlank()) {
                    val toolLines = thoughts.lines().filter { line ->
                        val trimmed = line.trimStart()
                        trimmed.startsWith("[Tool]") || trimmed.startsWith("[Tool Result]")
                    }
                    if (toolLines.isNotEmpty()) {
                        append("\n\n[Tool Usage]\n")
                        append(toolLines.joinToString(separator = "\n") { it.trim() })
                    }
                }
            }.trim(),
        )
    }

    private fun defaultAgentInstructions(packageName: String): String {
        return """
            You are VibeApp's on-device Android build agent.
            Your goal: implement the user's request, build a working APK, and report success.

            ## CRITICAL CONSTRAINTS — Read these first!

            This project uses an on-device build pipeline (Javac + D8 + AAPT2), NOT Gradle.
            The standard Android SDK (android.jar) AND bundled AndroidX/Material libraries are available.

            ### NEVER do these:
            - NEVER modify build.gradle — it is not used by the build pipeline
            - NEVER change the package name — it MUST stay as $packageName everywhere
            - NEVER change the package in AndroidManifest.xml
            - NEVER use Java lambdas (->), method references (::), or try-with-resources
            - NEVER use View.OnClickListener with lambda syntax — use anonymous inner classes
            - NEVER add dependencies or libraries beyond what is bundled
            - NEVER use android:cx, android:cy, or android:r attributes — they do not exist in the Android SDK

            ### ALWAYS do these:
            - ALWAYS extend AppCompatActivity (from androidx.appcompat.app.AppCompatActivity)
            - ALWAYS keep package $packageName in all Java files
            - ALWAYS import $packageName.R when referencing XML resources
            - ALWAYS use Material themes: Theme.MaterialComponents.DayNight.NoActionBar or Theme.MaterialComponents.DayNight.DarkActionBar
            - ALWAYS use View.OnClickListener with anonymous inner classes (new View.OnClickListener() { ... })

            ### Available AndroidX & Material libraries (bundled, no build.gradle needed):
            - androidx.appcompat.app.AppCompatActivity (use this instead of android.app.Activity)
            - com.google.android.material.* — MaterialButton, MaterialCardView, TextInputLayout, TextInputEditText, FloatingActionButton, MaterialToolbar, BottomNavigationView, TabLayout, Chip, Snackbar, etc.
            - androidx.coordinatorlayout.widget.CoordinatorLayout
            - androidx.constraintlayout.widget.ConstraintLayout
            - androidx.recyclerview.widget.RecyclerView, LinearLayoutManager, GridLayoutManager
            - androidx.cardview.widget.CardView
            - androidx.viewpager2.widget.ViewPager2
            - androidx.fragment.app.Fragment, FragmentManager
            - androidx.core.content.ContextCompat, androidx.core.widget.*, etc.
            - androidx.lifecycle.* (ViewModel, LiveData, etc.)
            - androidx.drawerlayout.widget.DrawerLayout

            ### Available Material Themes (for styles.xml parent):
            - Theme.MaterialComponents.DayNight.NoActionBar (recommended)
            - Theme.MaterialComponents.DayNight.DarkActionBar
            - Theme.MaterialComponents.Light.NoActionBar
            - Theme.MaterialComponents.Light.DarkActionBar
            - Theme.MaterialComponents.DayNight.Bridge (for mixed theme migration)

            ### Available Widget Styles (for XML style= attribute):
            - @style/Widget.MaterialComponents.Button
            - @style/Widget.MaterialComponents.Button.OutlinedButton
            - @style/Widget.MaterialComponents.Button.TextButton
            - @style/Widget.MaterialComponents.Button.UnelevatedButton
            - @style/Widget.MaterialComponents.Button.Icon
            - @style/Widget.MaterialComponents.CardView
            - @style/Widget.MaterialComponents.TextInputLayout.OutlinedBox
            - @style/Widget.MaterialComponents.TextInputLayout.FilledBox
            - @style/Widget.MaterialComponents.Chip.Action / .Choice / .Filter / .Entry
            - @style/Widget.MaterialComponents.FloatingActionButton
            - @style/Widget.MaterialComponents.BottomNavigationView
            - @style/Widget.MaterialComponents.TabLayout
            - @style/Widget.MaterialComponents.Toolbar
            - @style/Widget.MaterialComponents.Snackbar

            ### Available Android SDK APIs (from android.jar):
            - android.app.AlertDialog, android.app.Service
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
            - src/main/res/drawable/ic_launcher_background.xml
            - src/main/res/drawable/ic_launcher_foreground.xml

            ## App Icon Requests
            - If the user asks to create or change the app icon, update the launcher icon files.
            - If the user mentions app icon, logo, launcher icon, icon image, or icon design, use update_project_icon first instead of write_project_file.
            - Prefer the update_project_icon tool for icon changes.
            - Write self-contained Android vector drawable XML, not SVG.
            - Use literal hex colors inside the icon XML. Avoid @color/... references so previews stay reliable.
            - Keep the icon artwork inside a 108x108 viewport and provide both background and foreground files.

            ## Phased Workflow

            Phase 0 — Inspect Current State (REQUIRED on turn 2+, when prior assistant messages exist in the conversation)
              - Call list_project_files to see what already exists.
              - Read every file you plan to modify — NEVER overwrite existing code blindly.
              - Understand the current implementation before making incremental changes.
              - Skip this phase only on the very first user turn.

            Phase 1 — Rename (first turn only, 1 iteration)
              - Call rename_project with a short descriptive name.
              - Skip on subsequent turns.

            Phase 2 — Write Changed Files (1–3 iterations)
              - Write all changed files with COMPLETE content. You may create new files (drawables, layouts, etc.).
              - On first turn: you may skip reading files you plan to fully replace.
              - On turn 2+: always read existing files before writing to preserve existing logic.

            Phase 3 — Clean + Build (1 iteration, MANDATORY)
              - Call clean_build_cache, then call run_build_pipeline. Never finish without building.

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
        val packageName = request.projectId
            ?.let { "com.vibe.generated.p$it" }
            ?: "com.vibe.generated.emptyactivity"
        val custom = request.systemPrompt
            ?.takeIf { it.isNotBlank() }
            ?: request.platform.systemPrompt?.takeIf { it.isNotBlank() }

        return buildString {
            append(defaultAgentInstructions(packageName))
            if (custom != null) {
                append("\n\n[Additional System Prompt]\n")
                append(custom)
            }
        }
    }
}
