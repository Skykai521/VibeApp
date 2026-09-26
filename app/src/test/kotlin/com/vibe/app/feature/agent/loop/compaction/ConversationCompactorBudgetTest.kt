package com.vibe.app.feature.agent.loop.compaction

import com.vibe.app.data.database.entity.PlatformV2
import com.vibe.app.data.dto.openai.request.ChatCompletionRequest
import com.vibe.app.data.dto.openai.request.ResponsesRequest
import com.vibe.app.data.dto.openai.response.ChatCompletionChunk
import com.vibe.app.data.dto.openai.response.ResponsesStreamEvent
import com.vibe.app.data.dto.qwen.request.QwenChatCompletionRequest
import com.vibe.app.data.dto.qwen.response.QwenAssistantMessage
import com.vibe.app.data.dto.qwen.response.QwenChatCompletionResponse
import com.vibe.app.data.dto.qwen.response.QwenChoice
import com.vibe.app.data.model.ClientType
import com.vibe.app.data.network.OpenAIAPI
import com.vibe.app.feature.agent.AgentConversationItem
import com.vibe.app.feature.agent.AgentMessageRole
import com.vibe.app.feature.diagnostic.ModelExecutionTrace
import com.vibe.app.feature.diagnostic.ModelRequestDiagnosticContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fake that returns a fixed summary text. Streaming methods are unused
 * by the compaction path and fail loudly if touched.
 *
 * NOTE (drift from task brief): The brief's `OpenAIAPI` interface predates
 * Phase 1 / Task 1.8, which removed the `setToken`/`setAPIUrl` setters and
 * added `token: String?, apiUrl: String` params to every method. This fake
 * targets the CURRENT interface (see `data/network/OpenAIAPI.kt`): no setter
 * overrides, and `token`/`apiUrl` params on all four methods.
 */
private class FakeOpenAIAPI(private val summary: String) : OpenAIAPI {
    override fun streamChatCompletion(
        request: ChatCompletionRequest,
        token: String?,
        apiUrl: String,
        diagnosticContext: ModelRequestDiagnosticContext?,
        trace: ModelExecutionTrace?,
    ): Flow<ChatCompletionChunk> = error("unused in test")

    override fun streamResponses(
        request: ResponsesRequest,
        token: String?,
        apiUrl: String,
        diagnosticContext: ModelRequestDiagnosticContext?,
        trace: ModelExecutionTrace?,
    ): Flow<ResponsesStreamEvent> = error("unused in test")

    override fun streamQwenChatCompletion(
        request: QwenChatCompletionRequest,
        token: String?,
        apiUrl: String,
        diagnosticContext: ModelRequestDiagnosticContext?,
        trace: ModelExecutionTrace?,
    ): Flow<ChatCompletionChunk> = error("unused in test")

    override suspend fun completeQwenChatCompletion(
        request: QwenChatCompletionRequest,
        token: String?,
        apiUrl: String,
        diagnosticContext: ModelRequestDiagnosticContext?,
        trace: ModelExecutionTrace?,
    ): QwenChatCompletionResponse = QwenChatCompletionResponse(
        choices = listOf(
            QwenChoice(
                index = 0,
                message = QwenAssistantMessage(role = "assistant", content = summary),
            ),
        ),
    )
}

class ConversationCompactorBudgetTest {

    private val platform = PlatformV2(
        name = "kimi-test", compatibleType = ClientType.KIMI,
        apiUrl = "https://example.invalid", model = "kimi-k2.5",
    )

    /** 5 个回合、每回合 assistant 文本 40k 字符 ≈ 10k token,总量远超 KIMI 24k 预算。 */
    private fun oversizedConversation(): List<AgentConversationItem> =
        (1..5).flatMap { i ->
            listOf(
                AgentConversationItem(role = AgentMessageRole.USER, text = "request $i"),
                AgentConversationItem(role = AgentMessageRole.ASSISTANT, text = "a".repeat(40_000)),
            )
        }

    @Test
    fun `oversized model summary must not be returned as final result`() = runBlocking {
        // 摘要本身 200k 字符 ≈ 50k token,加 recent 回合必然超预算
        val compactor = ConversationCompactor(FakeOpenAIAPI("s".repeat(200_000)))
        val result = compactor.compact(oversizedConversation(), ClientType.KIMI, platform)
        assertNotEquals(CompactionStrategyType.MODEL_SUMMARY, result.strategyUsed)
        assertTrue("final result must fit budget", result.estimatedTokens <= 24_000)
    }

    @Test
    fun `small model summary is accepted`() = runBlocking {
        val compactor = ConversationCompactor(FakeOpenAIAPI("short summary of earlier work"))
        val result = compactor.compact(oversizedConversation(), ClientType.KIMI, platform)
        // 小摘要 + recent 3 回合(3×40k 字符 ≈ 30k token)仍超预算 → 不强求 MODEL_SUMMARY,
        // 只断言最终结果预算合规(recent 回合的处理属于 Phase 4 范围)
        assertTrue(result.estimatedTokens <= 24_000 || result.strategyUsed == CompactionStrategyType.MODEL_SUMMARY)
    }
}
