package com.vibe.app.feature.agent.loop.compaction

import com.vibe.app.data.database.entity.PlatformV2
import com.vibe.app.data.model.ClientType
import com.vibe.app.data.network.OpenAIAPI
import com.vibe.app.feature.agent.AgentConversationItem
import com.vibe.app.feature.agent.loop.ConversationContextManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central conversation compaction engine for the agent loop.
 *
 * Runs a chain of strategies to reduce conversation context size:
 * 1. [ToolResultTrimStrategy] — trim large tool payloads in older turns (free)
 * 2. [StructuralSummaryStrategy] — summarize older turns client-side (free)
 * 3. [ModelSummaryStrategy] — summarize via model API (costs one API call)
 *
 * Each strategy is tried in order. If context fits within budget after a strategy,
 * the chain stops. If all strategies are exhausted, the best result is returned.
 */
@Singleton
class ConversationCompactor @Inject constructor(
    private val openAIAPI: OpenAIAPI,
) {
    private val toolResultTrimStrategy = ToolResultTrimStrategy()
    private val structuralSummaryStrategy = StructuralSummaryStrategy()
    private val modelSummaryStrategy = ModelSummaryStrategy(openAIAPI)

    /**
     * Compact the conversation to fit within the provider's context budget.
     *
     * @param items The full conversation history.
     * @param clientType The provider type (determines budget and available strategies).
     * @return The compacted conversation. May be unchanged if already within budget.
     */
    suspend fun compact(
        items: List<AgentConversationItem>,
        clientType: ClientType,
        platform: PlatformV2? = null,
    ): CompactionResult {
        val budget = ProviderContextBudget.forProvider(clientType)
        val currentTokens = ConversationContextManager.estimateTokens(items)

        // Already within budget — no compaction needed
        if (currentTokens <= budget.maxTokens) {
            return CompactionResult(
                items = items,
                estimatedTokens = currentTokens,
                strategyUsed = CompactionStrategyType.NONE,
                turnsCompacted = 0,
            )
        }

        // Strategy 1: Trim tool result payloads
        val trimResult = toolResultTrimStrategy.compact(items, budget.recentTurns, budget.maxTokens)
        if (trimResult != null && trimResult.estimatedTokens <= budget.maxTokens) {
            return trimResult
        }

        // Use trimmed result as input for next strategy (cumulative)
        val afterTrim = trimResult?.items ?: items

        // Strategy 2: Structural summarization
        val structuralResult = structuralSummaryStrategy.compact(
            afterTrim, budget.recentTurns, budget.maxTokens,
        )
        if (structuralResult != null && structuralResult.estimatedTokens <= budget.maxTokens) {
            return structuralResult
        }

        // Strategy 3: Model-based summarization (only for supported providers)
        if (modelSummaryStrategy.isSupported(clientType) && platform != null) {
            modelSummaryStrategy.apiUrl = platform.apiUrl
            modelSummaryStrategy.token = platform.token
            modelSummaryStrategy.model = platform.model
            val modelResult = modelSummaryStrategy.compact(
                afterTrim, budget.recentTurns, budget.maxTokens,
            )
            if (modelResult != null) {
                return modelResult
            }
        }

        // All strategies exhausted — return best result we have
        return structuralResult ?: trimResult ?: CompactionResult(
            items = items,
            estimatedTokens = currentTokens,
            strategyUsed = CompactionStrategyType.NONE,
            turnsCompacted = 0,
        )
    }
}
