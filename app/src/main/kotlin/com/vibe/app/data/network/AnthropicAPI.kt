package com.vibe.app.data.network

import com.vibe.app.data.dto.anthropic.request.MessageRequest
import com.vibe.app.data.dto.anthropic.response.MessageResponseChunk
import com.vibe.app.feature.diagnostic.ModelExecutionTrace
import com.vibe.app.feature.diagnostic.ModelRequestDiagnosticContext
import kotlinx.coroutines.flow.Flow

interface AnthropicAPI {
    fun streamChatMessage(
        messageRequest: MessageRequest,
        token: String?,
        apiUrl: String,
        diagnosticContext: ModelRequestDiagnosticContext? = null,
        trace: ModelExecutionTrace? = null,
    ): Flow<MessageResponseChunk>
}
