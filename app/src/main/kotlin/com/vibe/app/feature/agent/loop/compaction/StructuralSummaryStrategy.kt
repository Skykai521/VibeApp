package com.vibe.app.feature.agent.loop.compaction

import com.vibe.app.feature.agent.AgentConversationItem
import com.vibe.app.feature.agent.AgentMessageRole
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Strategy 2: Structurally summarize older turns into compact single-item representations.
 *
 * Each older turn is compressed into a single USER-role item containing:
 * - User request (up to 500 chars)
 * - Tool names and file paths operated on
 * - Assistant response summary (up to 500 chars)
 * - Error/success indicators
 *
 * This is a client-side operation with zero API cost.
 */
class StructuralSummaryStrategy : CompactionStrategy {
    override val type = CompactionStrategyType.STRUCTURAL_SUMMARY

    override suspend fun compact(
        items: List<AgentConversationItem>,
        recentTurnCount: Int,
        tokenBudget: Int,
    ): CompactionResult? {
        val turns = ToolResultTrimStrategy.splitIntoTurns(items)
        if (turns.size <= recentTurnCount) return null

        val olderTurns = turns.dropLast(recentTurnCount)
        val recentTurns = turns.takeLast(recentTurnCount)

        val summaryItems = olderTurns.mapNotNull { turn -> summarizeTurn(turn) }
        val recentItems = recentTurns.flatten()
        val result = summaryItems + recentItems

        val mutableResult = result.toMutableList()
        while (mutableResult.size > recentItems.size &&
            ToolResultTrimStrategy.estimateTokens(mutableResult) > tokenBudget
        ) {
            mutableResult.removeAt(0)
        }

        val tokens = ToolResultTrimStrategy.estimateTokens(mutableResult)
        return CompactionResult(
            items = mutableResult,
            estimatedTokens = tokens,
            strategyUsed = type,
            turnsCompacted = olderTurns.size,
        )
    }

    private fun summarizeTurn(turn: List<AgentConversationItem>): AgentConversationItem? {
        val userItem = turn.firstOrNull { it.role == AgentMessageRole.USER } ?: return null
        val userText = userItem.text?.take(MAX_USER_TEXT) ?: return null

        val toolCalls = turn
            .filter { it.role == AgentMessageRole.ASSISTANT }
            .flatMap { it.toolCalls.orEmpty() }

        val toolSummaries = toolCalls.map { call ->
            val pathArg = runCatching {
                val args = call.arguments
                if (args is JsonObject) {
                    (args["path"] as? JsonPrimitive)?.content
                } else null
            }.getOrNull()
            if (pathArg != null) "${call.name}($pathArg)" else call.name
        }.distinct()

        val toolErrors = turn
            .filter { it.role == AgentMessageRole.TOOL }
            .mapNotNull { item ->
                val payload = item.payload
                if (payload is JsonObject && payload.containsKey("error")) {
                    "${item.toolName}: ${payload["error"]}"
                } else null
            }

        val assistantText = turn
            .filter { it.role == AgentMessageRole.ASSISTANT }
            .mapNotNull { it.text?.take(MAX_ASSISTANT_TEXT) }
            .joinToString(" ")
            .take(MAX_ASSISTANT_TEXT)

        val summary = buildString {
            append("[Compacted Turn]\n")
            append("User: $userText\n")
            if (toolSummaries.isNotEmpty()) {
                append("Tools: ${toolSummaries.joinToString(", ")}\n")
            }
            if (toolErrors.isNotEmpty()) {
                append("Errors: ${toolErrors.joinToString("; ")}\n")
            }
            if (assistantText.isNotBlank()) {
                append("Result: $assistantText")
            }
        }

        return AgentConversationItem(
            role = AgentMessageRole.USER,
            text = summary,
        )
    }

    companion object {
        private const val MAX_USER_TEXT = 500
        private const val MAX_ASSISTANT_TEXT = 500
    }
}
