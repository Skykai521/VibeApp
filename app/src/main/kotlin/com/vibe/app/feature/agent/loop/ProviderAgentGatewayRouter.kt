package com.vibe.app.feature.agent.loop

import com.vibe.app.data.model.ClientType
import com.vibe.app.feature.agent.AgentModelEvent
import com.vibe.app.feature.agent.AgentModelGateway
import com.vibe.app.feature.agent.AgentModelRequest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * Routes agent model requests to the appropriate [AgentModelGateway] implementation based on
 * the platform's [ClientType].
 *
 * Routing table:
 * - [ClientType.ANTHROPIC] → [AnthropicMessagesAgentGateway]
 * - [ClientType.QWEN]      → [QwenResponsesAgentGateway]
 * - everything else        → [OpenAiResponsesAgentGateway] (OpenAI-compatible APIs)
 *
 * New providers can be added here without touching the coordinator or DI graph.
 */
@Singleton
class ProviderAgentGatewayRouter @Inject constructor(
    private val openAiGateway: OpenAiResponsesAgentGateway,
    private val qwenGateway: QwenChatCompletionsAgentGateway,
    private val anthropicGateway: AnthropicMessagesAgentGateway,
) : AgentModelGateway {

    override suspend fun streamTurn(request: AgentModelRequest): Flow<AgentModelEvent> {
        return when (request.platform.compatibleType) {
            ClientType.ANTHROPIC -> anthropicGateway.streamTurn(request)
            ClientType.QWEN -> qwenGateway.streamTurn(request)
            else -> openAiGateway.streamTurn(request)
        }
    }
}
