package com.vibe.app.feature.agent.loop.compaction

import com.vibe.app.data.database.entity.PlatformV2
import com.vibe.app.data.model.ClientType
import com.vibe.app.data.network.OpenAIAPI
import com.vibe.app.feature.agent.AgentConversationItem
import com.vibe.app.feature.agent.AgentMessageRole
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

        // All strategies exhausted or insufficient — enforce budget by truncating text
        val bestResult = structuralResult ?: trimResult
        val bestItems = bestResult?.items ?: items
        val bestTokens = bestResult?.estimatedTokens
            ?: ConversationContextManager.estimateTokens(bestItems)

        if (bestTokens > budget.maxTokens) {
            val truncated = truncateToFitBudget(bestItems, budget.maxTokens)
            val truncatedTokens = ConversationContextManager.estimateTokens(truncated)
            return CompactionResult(
                items = truncated,
                estimatedTokens = truncatedTokens,
                strategyUsed = CompactionStrategyType.TEXT_TRUNCATION,
                turnsCompacted = 0,
            )
        }

        return bestResult ?: CompactionResult(
            items = items,
            estimatedTokens = currentTokens,
            strategyUsed = CompactionStrategyType.NONE,
            turnsCompacted = 0,
        )
    }

    /**
     * Last-resort budget enforcement: progressively truncate assistant text
     * from oldest to newest until the conversation fits within the token budget.
     *
     * Only truncates flat assistant text (items without toolCalls), which are
     * the cross-turn messages loaded from Room. Items with toolCalls are from
     * the current agent loop and must be preserved for tool result pairing.
     */
    private fun truncateToFitBudget(
        items: List<AgentConversationItem>,
        tokenBudget: Int,
    ): List<AgentConversationItem> {
        val result = items.toMutableList()

        // Phase 1: Truncate older assistant text (no toolCalls) to MAX_TRUNCATED_TEXT chars
        for (i in result.indices) {
            if (ConversationContextManager.estimateTokens(result) <= tokenBudget) break
            val item = result[i]
            if (item.role == AgentMessageRole.ASSISTANT &&
                item.toolCalls.isNullOrEmpty() &&
                (item.text?.length ?: 0) > MAX_TRUNCATED_TEXT
            ) {
                result[i] = item.copy(
                    text = item.text!!.take(MAX_TRUNCATED_TEXT) + TRUNCATION_MARKER,
                    reasoningContent = null,
                )
            }
        }

        // Phase 2: If still over budget, truncate more aggressively
        for (i in result.indices) {
            if (ConversationContextManager.estimateTokens(result) <= tokenBudget) break
            val item = result[i]
            if (item.role == AgentMessageRole.ASSISTANT &&
                item.toolCalls.isNullOrEmpty() &&
                (item.text?.length ?: 0) > MIN_TRUNCATED_TEXT
            ) {
                result[i] = item.copy(
                    text = item.text!!.take(MIN_TRUNCATED_TEXT) + TRUNCATION_MARKER,
                    reasoningContent = null,
                )
            }
        }

        // Phase 3: Drop oldest non-recent user-assistant pairs if still over budget
        while (result.size > 2 && ConversationContextManager.estimateTokens(result) > tokenBudget) {
            result.removeAt(0)
        }

        return result
    }

    companion object {
        private const val MAX_TRUNCATED_TEXT = 1500
        private const val MIN_TRUNCATED_TEXT = 300
        private const val TRUNCATION_MARKER = "\n\n[...truncated for context budget]"
    }
}
