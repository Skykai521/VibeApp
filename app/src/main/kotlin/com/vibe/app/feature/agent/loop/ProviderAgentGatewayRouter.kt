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
 * - [ClientType.MINIMAX]   → [AnthropicMessagesAgentGateway] (Anthropic-compatible API)
 * - [ClientType.QWEN]      → [QwenChatCompletionsAgentGateway]
 * - [ClientType.KIMI]      → [KimiChatCompletionsAgentGateway]
 * - [ClientType.OPENAI]    → [OpenAiResponsesAgentGateway]
 *
 * New providers can be added here without touching the coordinator or DI graph.
 */
@Singleton
class ProviderAgentGatewayRouter @Inject constructor(
    private val openAiGateway: OpenAiResponsesAgentGateway,
    private val qwenGateway: QwenChatCompletionsAgentGateway,
    private val kimiGateway: KimiChatCompletionsAgentGateway,
    private val anthropicGateway: AnthropicMessagesAgentGateway,
) : AgentModelGateway {

    override suspend fun streamTurn(request: AgentModelRequest): Flow<AgentModelEvent> {
        return when (request.platform.compatibleType) {
            ClientType.ANTHROPIC -> anthropicGateway.streamTurn(request)
            ClientType.MINIMAX -> anthropicGateway.streamTurn(request.withMiniMaxAnthropicUrl())
            ClientType.QWEN -> qwenGateway.streamTurn(request)
            ClientType.KIMI -> kimiGateway.streamTurn(request)
            ClientType.OPENAI -> openAiGateway.streamTurn(request)
        }
    }
}

/**
 * Ensures the MiniMax platform URL points to the Anthropic-compatible endpoint.
 * Migrates old OpenAI-style URLs (e.g. `https://api.minimaxi.com/`) to the
 * Anthropic path (`https://api.minimaxi.com/anthropic/`).
 */
private fun AgentModelRequest.withMiniMaxAnthropicUrl(): AgentModelRequest {
    val url = platform.apiUrl.trimEnd('/')
    if (url.endsWith("/anthropic")) return this
    return copy(platform = platform.copy(apiUrl = "$url/anthropic/"))
}
