package com.vibe.app.feature.agent.loop.compaction

import com.vibe.app.feature.agent.AgentConversationItem

data class CompactionResult(
    val items: List<AgentConversationItem>,
    val estimatedTokens: Int,
    val strategyUsed: CompactionStrategyType,
    val turnsCompacted: Int,
)

enum class CompactionStrategyType {
    NONE,
    TOOL_RESULT_TRIM,
    STRUCTURAL_SUMMARY,
    MODEL_SUMMARY,
    TEXT_TRUNCATION,
}

interface CompactionStrategy {
    val type: CompactionStrategyType

    suspend fun compact(
        items: List<AgentConversationItem>,
        recentTurnCount: Int,
        tokenBudget: Int,
    ): CompactionResult?
}
