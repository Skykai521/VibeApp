package com.vibe.app.data.network

import com.vibe.app.data.dto.google.request.GenerateContentRequest
import com.vibe.app.data.dto.google.response.GenerateContentResponse
import com.vibe.app.feature.diagnostic.ModelExecutionTrace
import com.vibe.app.feature.diagnostic.ModelRequestDiagnosticContext
import kotlinx.coroutines.flow.Flow

interface GoogleAPI {
    fun setToken(token: String?)
    fun setAPIUrl(url: String)
    fun streamGenerateContent(
        request: GenerateContentRequest,
        model: String,
        diagnosticContext: ModelRequestDiagnosticContext? = null,
        trace: ModelExecutionTrace? = null,
    ): Flow<GenerateContentResponse>
}
