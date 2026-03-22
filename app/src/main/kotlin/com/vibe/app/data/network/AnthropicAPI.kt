package com.vibe.app.data.network

import com.vibe.app.data.dto.anthropic.request.MessageRequest
import com.vibe.app.data.dto.anthropic.response.MessageResponseChunk
import com.vibe.app.feature.diagnostic.ModelExecutionTrace
import com.vibe.app.feature.diagnostic.ModelRequestDiagnosticContext
import kotlinx.coroutines.flow.Flow

interface AnthropicAPI {
    fun setToken(token: String?)
    fun setAPIUrl(url: String)
    fun streamChatMessage(
        messageRequest: MessageRequest,
        diagnosticContext: ModelRequestDiagnosticContext? = null,
        trace: ModelExecutionTrace? = null,
    ): Flow<MessageResponseChunk>
}
