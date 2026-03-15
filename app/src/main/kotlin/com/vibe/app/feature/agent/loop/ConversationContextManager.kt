package com.vibe.app.feature.agent.loop

import com.vibe.app.feature.agent.AgentConversationItem
import com.vibe.app.feature.agent.AgentMessageRole

/**
 * Manages conversation context to fit within token budgets for long multi-turn conversations.
 *
 * Strategy:
 * 1. Split conversation history into logical "turns" (user msg + assistant response)
 * 2. Keep the most recent [recentTurnsToKeepFull] turns in full detail
 * 3. Summarize older turns into compact form (user request + tools used + outcome)
 * 4. If still over budget, progressively drop oldest summaries
 */
class ConversationContextManager(
    private val maxContextTokens: Int = DEFAULT_MAX_CONTEXT_TOKENS,
    private val recentTurnsToKeepFull: Int = DEFAULT_RECENT_TURNS,
) {

    /**
     * Trims a conversation item list to fit within the token budget while preserving
     * the most recent turns in full and summarizing older turns.
     */
    fun trimConversation(
        items: List<AgentConversationItem>,
    ): List<AgentConversationItem> {
        val turns = splitIntoTurns(items)
        if (turns.size <= recentTurnsToKeepFull) return items

        val recentTurns = turns.takeLast(recentTurnsToKeepFull)
        val olderTurns = turns.dropLast(recentTurnsToKeepFull)

        val summaryItems = olderTurns.mapNotNull { turn -> summarizeTurn(turn) }
        val recentItems = recentTurns.flatten()

        val result = summaryItems.toMutableList<AgentConversationItem>()
        result.addAll(recentItems)

        // If still over budget, progressively drop oldest summaries
        while (result.size > recentItems.size && estimateTokens(result) > maxContextTokens) {
            result.removeAt(0)
        }

        return result
    }

    /**
     * Splits a flat list of conversation items into logical turns.
     * A turn starts with a USER message and includes everything until the next USER message.
     */
    private fun splitIntoTurns(
        items: List<AgentConversationItem>,
    ): List<List<AgentConversationItem>> {
        val turns = mutableListOf<MutableList<AgentConversationItem>>()
        for (item in items) {
            if (item.role == AgentMessageRole.USER) {
                turns.add(mutableListOf(item))
            } else if (turns.isNotEmpty()) {
                turns.last().add(item)
            }
        }
        return turns
    }

    /**
     * Compresses a full turn into a single summary item.
     * Extracts: what the user asked, which tools were called, what the outcome was.
     * Drops: full file contents, tool arguments, tool results.
     */
    private fun summarizeTurn(
        turn: List<AgentConversationItem>,
    ): AgentConversationItem? {
        val userText = turn.firstOrNull { it.role == AgentMessageRole.USER }
            ?.text?.take(MAX_SUMMARY_USER_TEXT) ?: return null

        val toolNames = turn
            .filter { it.role == AgentMessageRole.ASSISTANT }
            .flatMap { it.toolCalls.orEmpty() }
            .map { it.name }
            .distinct()

        val assistantText = turn
            .filter { it.role == AgentMessageRole.ASSISTANT }
            .mapNotNull { it.text?.take(MAX_SUMMARY_ASSISTANT_TEXT) }
            .joinToString(" ")
            .take(MAX_SUMMARY_ASSISTANT_TEXT)

        // Extract tool names mentioned in text for historical messages
        // (which don't have structured toolCalls)
        val textToolNames = if (toolNames.isEmpty()) {
            turn.filter { it.role == AgentMessageRole.ASSISTANT }
                .flatMap { item ->
                    TOOL_USAGE_PATTERN.findAll(item.text.orEmpty()).map { it.groupValues[1] }.toList()
                }
                .distinct()
        } else {
            emptyList()
        }

        val allToolNames = (toolNames + textToolNames).distinct()

        val summary = buildString {
            append("[Previous Turn Summary]\n")
            append("User: $userText\n")
            if (allToolNames.isNotEmpty()) {
                append("Tools used: ${allToolNames.joinToString(", ")}\n")
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
        const val DEFAULT_MAX_CONTEXT_TOKENS = 24_000
        const val DEFAULT_RECENT_TURNS = 4
        private const val MAX_SUMMARY_USER_TEXT = 200
        private const val MAX_SUMMARY_ASSISTANT_TEXT = 200

        private val TOOL_USAGE_PATTERN = Regex("""\[Tool]\s+(\S+)""")

        /**
         * Estimates token count using a character-based heuristic.
         * ~4 chars/token for Latin text, ~2 chars/token for CJK characters.
         */
        fun estimateTokens(text: String): Int {
            if (text.isEmpty()) return 0
            var cjkChars = 0
            var otherChars = 0
            for (c in text) {
                if (c.code in 0x4E00..0x9FFF ||
                    c.code in 0x3000..0x303F ||
                    c.code in 0xFF00..0xFFEF
                ) {
                    cjkChars++
                } else {
                    otherChars++
                }
            }
            return (cjkChars / 2) + (otherChars / 4) + 1
        }

        fun estimateTokens(items: List<AgentConversationItem>): Int {
            return items.sumOf { item ->
                val textTokens = estimateTokens(item.text.orEmpty())
                val toolCallTokens = item.toolCalls?.sumOf { call ->
                    estimateTokens(call.name) + estimateTokens(call.arguments.toString())
                } ?: 0
                val payloadTokens = item.payload?.let { estimateTokens(it.toString()) } ?: 0
                textTokens + toolCallTokens + payloadTokens
            }
        }
    }
}
