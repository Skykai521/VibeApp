package com.vibe.app.feature.agent.loop.compaction

import com.vibe.app.data.dto.qwen.request.QwenChatCompletionRequest
import com.vibe.app.data.dto.qwen.request.QwenChatMessage
import com.vibe.app.data.dto.qwen.request.qwenTextContent
import com.vibe.app.data.model.ClientType
import com.vibe.app.data.network.OpenAIAPI
import com.vibe.app.feature.agent.AgentConversationItem
import com.vibe.app.feature.agent.AgentMessageRole

/**
 * Strategy 3: Model-based summarization of older conversation turns.
 *
 * Makes a non-streaming API call to the same provider, asking it to produce
 * a concise summary of the conversation history. The summary replaces all
 * older turns with a single USER-role item.
 *
 * Only available for OpenAI-compatible providers (Qwen, Kimi, MiniMax, etc.)
 * via the existing completeQwenChatCompletion endpoint.
 */
class ModelSummaryStrategy(
    private val openAIAPI: OpenAIAPI,
) : CompactionStrategy {
    override val type = CompactionStrategyType.MODEL_SUMMARY

    private val supportedProviders = setOf(
        ClientType.QWEN,
        ClientType.KIMI,
        ClientType.MINIMAX,
        ClientType.GROQ,
        ClientType.OLLAMA,
        ClientType.OPENROUTER,
        ClientType.CUSTOM,
    )

    override suspend fun compact(
        items: List<AgentConversationItem>,
        recentTurnCount: Int,
        tokenBudget: Int,
    ): CompactionResult? {
        val turns = ToolResultTrimStrategy.splitIntoTurns(items)
        if (turns.size <= recentTurnCount) return null

        val olderTurns = turns.dropLast(recentTurnCount)
        val recentTurns = turns.takeLast(recentTurnCount)

        val textToSummarize = buildSummarizationInput(olderTurns.flatten())
        if (textToSummarize.isBlank()) return null

        val summary = try {
            callSummarizationAPI(textToSummarize)
        } catch (_: Exception) {
            return null
        }

        if (summary.isNullOrBlank()) return null

        val summaryItem = AgentConversationItem(
            role = AgentMessageRole.USER,
            text = "[Conversation Summary]\n$summary",
        )

        val result = listOf(summaryItem) + recentTurns.flatten()
        val tokens = ToolResultTrimStrategy.estimateTokens(result)
        return CompactionResult(
            items = result,
            estimatedTokens = tokens,
            strategyUsed = type,
            turnsCompacted = olderTurns.size,
        )
    }

    fun isSupported(clientType: ClientType): Boolean = clientType in supportedProviders

    private fun buildSummarizationInput(items: List<AgentConversationItem>): String {
        return buildString {
            items.forEach { item ->
                when (item.role) {
                    AgentMessageRole.USER -> append("User: ${item.text?.take(1000) ?: ""}\n")
                    AgentMessageRole.ASSISTANT -> {
                        val tools = item.toolCalls?.joinToString(", ") { it.name } ?: ""
                        if (tools.isNotEmpty()) append("Assistant used tools: $tools\n")
                        item.text?.take(500)?.let { append("Assistant: $it\n") }
                    }
                    AgentMessageRole.TOOL -> {
                        val result = item.payload?.toString()?.take(200) ?: item.text?.take(200) ?: ""
                        append("Tool ${item.toolName} result: $result\n")
                    }
                    AgentMessageRole.SYSTEM -> Unit
                }
            }
        }.take(MAX_SUMMARIZATION_INPUT)
    }

    private suspend fun callSummarizationAPI(text: String): String? {
        val response = openAIAPI.completeQwenChatCompletion(
            QwenChatCompletionRequest(
                model = "",
                messages = listOf(
                    QwenChatMessage(
                        role = "system",
                        content = qwenTextContent(SUMMARIZATION_SYSTEM_PROMPT),
                    ),
                    QwenChatMessage(
                        role = "user",
                        content = qwenTextContent(text),
                    ),
                ),
                stream = false,
            ),
        )
        return response.choices?.firstOrNull()?.message?.content?.trim()
    }

    companion object {
        private const val MAX_SUMMARIZATION_INPUT = 8_000

        private const val SUMMARIZATION_SYSTEM_PROMPT =
            """You are a conversation summarizer for an AI coding assistant. Summarize the following conversation history into a concise narrative that preserves:
1. What the user asked for
2. Which files were created, read, or modified (include file paths)
3. What tools were used and their outcomes
4. Any errors encountered and how they were resolved
5. The current state of the project

Be concise but preserve all file paths and technical details. Output plain text, no markdown headers. Max 500 words."""
    }
}
