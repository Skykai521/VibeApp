package com.vibe.app.data.network

import com.vibe.app.data.dto.openai.request.ChatCompletionRequest
import com.vibe.app.data.dto.openai.request.ResponsesRequest
import com.vibe.app.data.dto.openai.response.ChatCompletionChunk
import com.vibe.app.data.dto.openai.response.ResponsesStreamEvent
import com.vibe.app.data.dto.qwen.request.QwenChatCompletionRequest
import com.vibe.app.data.dto.qwen.response.QwenChatCompletionResponse
import com.vibe.app.feature.diagnostic.ModelExecutionTrace
import com.vibe.app.feature.diagnostic.ModelRequestDiagnosticContext
import kotlinx.coroutines.flow.Flow

interface OpenAIAPI {
    fun streamChatCompletion(
        request: ChatCompletionRequest,
        token: String?,
        apiUrl: String,
        diagnosticContext: ModelRequestDiagnosticContext? = null,
        trace: ModelExecutionTrace? = null,
    ): Flow<ChatCompletionChunk>

    fun streamResponses(
        request: ResponsesRequest,
        token: String?,
        apiUrl: String,
        diagnosticContext: ModelRequestDiagnosticContext? = null,
        trace: ModelExecutionTrace? = null,
    ): Flow<ResponsesStreamEvent>

    fun streamQwenChatCompletion(
        request: QwenChatCompletionRequest,
        token: String?,
        apiUrl: String,
        diagnosticContext: ModelRequestDiagnosticContext? = null,
        trace: ModelExecutionTrace? = null,
    ): Flow<ChatCompletionChunk>

    suspend fun completeQwenChatCompletion(
        request: QwenChatCompletionRequest,
        token: String?,
        apiUrl: String,
        diagnosticContext: ModelRequestDiagnosticContext? = null,
        trace: ModelExecutionTrace? = null,
    ): QwenChatCompletionResponse
}
