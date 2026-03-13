package com.vibe.app.feature.agent

import com.vibe.app.data.database.entity.PlatformV2
import kotlinx.coroutines.flow.Flow

interface AgentTool {
    val definition: AgentToolDefinition

    suspend fun execute(
        call: AgentToolCall,
        context: AgentToolContext,
    ): AgentToolResult
}

data class AgentToolContext(
    val chatId: Int,
    val platformUid: String,
    val iteration: Int,
    val projectId: String,
)

interface AgentToolRegistry {
    fun listDefinitions(): List<AgentToolDefinition>

    fun findTool(name: String): AgentTool?
}

data class AgentModelRequest(
    val platform: PlatformV2,
    // Delta items for this turn only (new messages since the last model response).
    // Stateful providers (OpenAI Responses API) use this together with previousResponseId.
    val conversation: List<AgentConversationItem>,
    // Full accumulated conversation history from the very beginning.
    // Stateless providers (Anthropic Messages API) must use this to reconstruct context.
    val fullConversation: List<AgentConversationItem>,
    val instructions: String? = null,
    val tools: List<AgentToolDefinition>,
    val policy: AgentLoopPolicy,
    val previousResponseId: String? = null,
)

sealed interface AgentModelEvent {
    data class ThinkingDelta(
        val delta: String,
    ) : AgentModelEvent

    data class OutputDelta(
        val delta: String,
    ) : AgentModelEvent

    data class ToolCallReady(
        val call: AgentToolCall,
    ) : AgentModelEvent

    data class Completed(
        val finalText: String? = null,
        val responseId: String? = null,
    ) : AgentModelEvent

    data class Failed(
        val message: String,
    ) : AgentModelEvent
}

interface AgentModelGateway {
    suspend fun streamTurn(request: AgentModelRequest): Flow<AgentModelEvent>
}

interface AgentLoopCoordinator {
    suspend fun run(request: AgentLoopRequest): Flow<AgentLoopEvent>
}
