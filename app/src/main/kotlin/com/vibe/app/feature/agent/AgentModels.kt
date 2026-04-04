package com.vibe.app.feature.agent

import com.vibe.app.data.database.entity.MessageV2
import com.vibe.app.data.database.entity.PlatformV2
import com.vibe.app.feature.diagnostic.DiagnosticContext
import kotlinx.serialization.json.JsonElement

enum class AgentMessageRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL,
}

enum class AgentToolChoiceMode {
    NONE,
    AUTO,
    REQUIRED,
}

data class AgentLoopPolicy(
    val maxIterations: Int = 30,
    val toolChoiceMode: AgentToolChoiceMode = AgentToolChoiceMode.AUTO,
    val allowParallelToolCalls: Boolean = false,
)

data class AgentConversationItem(
    val role: AgentMessageRole,
    val text: String? = null,
    val attachments: List<String> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
    val payload: JsonElement? = null,
    // Non-null only for ASSISTANT items that ended with tool calls.
    // Used by stateless providers (e.g. Anthropic) to reconstruct full conversation history.
    val toolCalls: List<AgentToolCall>? = null,
    // Reasoning/thinking content from models that support it (e.g. Kimi kimi-k2.5).
    // Must be echoed back in subsequent turns for providers that require it.
    val reasoningContent: String? = null,
)

data class AgentToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonElement,
)

data class AgentToolCall(
    val id: String,
    val name: String,
    val arguments: JsonElement,
)

data class AgentToolResult(
    val toolCallId: String,
    val toolName: String,
    val output: JsonElement,
    val isError: Boolean = false,
)

data class AgentLoopRequest(
    val chatId: Int,
    val projectId: String? = null,
    val diagnosticContext: DiagnosticContext? = null,
    val platform: PlatformV2,
    val userMessages: List<MessageV2>,
    val assistantMessages: List<List<MessageV2>>,
    val systemPrompt: String? = null,
    val tools: List<AgentToolDefinition> = emptyList(),
    val policy: AgentLoopPolicy = AgentLoopPolicy(),
)

sealed interface AgentLoopEvent {
    data class LoopStarted(
        val chatId: Int,
        val platformUid: String,
    ) : AgentLoopEvent

    data class ModelTurnStarted(
        val iteration: Int,
    ) : AgentLoopEvent

    data class ThinkingDelta(
        val iteration: Int,
        val delta: String,
    ) : AgentLoopEvent

    data class OutputDelta(
        val iteration: Int,
        val delta: String,
    ) : AgentLoopEvent

    data class ToolCallDiscovered(
        val iteration: Int,
        val call: AgentToolCall,
    ) : AgentLoopEvent

    data class ToolExecutionStarted(
        val iteration: Int,
        val call: AgentToolCall,
    ) : AgentLoopEvent

    data class ToolExecutionFinished(
        val iteration: Int,
        val result: AgentToolResult,
    ) : AgentLoopEvent

    data class LoopCompleted(
        val finalText: String,
        val toolResults: List<AgentToolResult> = emptyList(),
    ) : AgentLoopEvent

    data class LoopFailed(
        val message: String,
        val iteration: Int? = null,
    ) : AgentLoopEvent
}
