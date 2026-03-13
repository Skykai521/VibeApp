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
)

interface AgentToolRegistry {
    fun listDefinitions(): List<AgentToolDefinition>

    fun findTool(name: String): AgentTool?
}

data class AgentModelRequest(
    val platform: PlatformV2,
    val conversation: List<AgentConversationItem>,
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
