package com.vibe.app.feature.agent.loop.compaction

import com.vibe.app.data.model.ClientType

data class ProviderContextBudget(
    val maxTokens: Int,
    val recentTurns: Int,
) {
    companion object {
        fun forProvider(clientType: ClientType): ProviderContextBudget = when (clientType) {
            ClientType.ANTHROPIC -> ProviderContextBudget(maxTokens = 80_000, recentTurns = 5)
            ClientType.QWEN -> ProviderContextBudget(maxTokens = 40_000, recentTurns = 4)
            ClientType.KIMI -> ProviderContextBudget(maxTokens = 24_000, recentTurns = 3)
            ClientType.MINIMAX -> ProviderContextBudget(maxTokens = 40_000, recentTurns = 4)
            ClientType.OPENAI -> ProviderContextBudget(maxTokens = 60_000, recentTurns = 5)
            ClientType.GROQ, ClientType.OLLAMA, ClientType.OPENROUTER, ClientType.CUSTOM ->
                ProviderContextBudget(maxTokens = 24_000, recentTurns = 3)
            ClientType.GOOGLE -> ProviderContextBudget(maxTokens = 60_000, recentTurns = 5)
        }
    }
}
