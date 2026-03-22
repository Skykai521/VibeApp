package com.vibe.app.data.network

import com.vibe.app.data.ModelConstants
import com.vibe.app.data.dto.openai.request.ChatCompletionRequest
import com.vibe.app.data.dto.openai.request.ResponsesRequest
import com.vibe.app.data.dto.openai.response.ChatCompletionChunk
import com.vibe.app.data.dto.openai.response.ErrorDetail
import com.vibe.app.data.dto.openai.response.ResponseErrorEvent
import com.vibe.app.data.dto.openai.response.ResponsesStreamEvent
import com.vibe.app.data.dto.openai.response.UnknownEvent
import com.vibe.app.data.dto.qwen.request.QwenChatCompletionRequest
import com.vibe.app.data.dto.qwen.response.QwenChatCompletionResponse
import com.vibe.app.feature.diagnostic.ChatDiagnosticLogger
import com.vibe.app.feature.diagnostic.ModelExecutionTrace
import com.vibe.app.feature.diagnostic.ModelRequestDiagnosticContext
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement

class OpenAIAPIImpl @Inject constructor(
    private val networkClient: NetworkClient,
    private val diagnosticLogger: ChatDiagnosticLogger,
) : OpenAIAPI {

    private var token: String? = null
    private var apiUrl: String = ModelConstants.OPENAI_API_URL

    override fun setToken(token: String?) {
        this.token = token
    }

    override fun setAPIUrl(url: String) {
        this.apiUrl = url
    }

    override suspend fun completeQwenChatCompletion(
        request: QwenChatCompletionRequest,
        diagnosticContext: ModelRequestDiagnosticContext?,
        trace: ModelExecutionTrace?,
    ): QwenChatCompletionResponse {
        val endpoint = if (apiUrl.endsWith("/")) "${apiUrl}v1/chat/completions" else "$apiUrl/v1/chat/completions"
        val requestBody = NetworkClient.json.encodeToJsonElement(request).toString()
        val requestStartedAt = System.currentTimeMillis()
        trace?.markRequestStarted(requestStartedAt)
        diagnosticContext?.let {
            diagnosticLogger.logModelRequest(
                context = it,
                endpointUrl = endpoint,
                requestBodyBytesApprox = requestBody.toByteArray().size,
                startedAt = requestStartedAt,
            )
        }

        NetworkLogcatLogger.logRequest(
            method = "POST",
            url = endpoint,
            commonHeaders = buildMap {
                put("Accept", ContentType.Application.Json.toString())
                if (token != null) {
                    put(HttpHeaders.Authorization, "Bearer $token")
                }
            },
            bodyContentType = ContentType.Application.Json.toString(),
            body = requestBody,
        )

        return try {
            val startTime = requestStartedAt
            networkClient().preparePost(endpoint) {
                contentType(ContentType.Application.Json)
                accept(ContentType.Application.Json)
                setBody(requestBody)
                token?.let { bearerAuth(it) }
            }.execute { response ->
                trace?.markFirstByte(response.status.value)
                val rawBody = response.body<String>()
                NetworkLogcatLogger.logResponse(
                    method = "POST",
                    url = endpoint,
                    statusCode = response.status.value,
                    statusText = response.status.description,
                    headers = response.headers.entries().associate { it.key to it.value },
                    bodyContentType = response.headers[HttpHeaders.ContentType],
                    body = rawBody,
                    durationMillis = System.currentTimeMillis() - startTime,
                )

                if (!response.status.isSuccess()) {
                    trace?.updateStatusCode(response.status.value)
                    return@execute QwenChatCompletionResponse(
                        error = com.vibe.app.data.dto.qwen.response.QwenErrorDetail(
                            message = rawBody,
                            code = response.status.value.toString(),
                        ),
                    )
                }

                return@execute try {
                    NetworkClient.json.decodeFromString<QwenChatCompletionResponse>(rawBody)
                } catch (e: Exception) {
                    QwenChatCompletionResponse(
                        error = com.vibe.app.data.dto.qwen.response.QwenErrorDetail(
                            message = "Failed to decode Qwen chat completion: ${e.message}\n$rawBody",
                            code = "decode_error",
                        ),
                    )
                }
            }
        } catch (e: io.ktor.client.plugins.ClientRequestException) {
            val errorBody = e.response.body<String>()
            trace?.updateStatusCode(e.response.status.value)
            NetworkLogcatLogger.logResponse(
                method = "POST",
                url = endpoint,
                statusCode = e.response.status.value,
                statusText = e.response.status.description,
                headers = e.response.headers.entries().associate { it.key to it.value },
                bodyContentType = e.response.headers[HttpHeaders.ContentType],
                body = errorBody,
            )
            QwenChatCompletionResponse(
                error = com.vibe.app.data.dto.qwen.response.QwenErrorDetail(
                    message = errorBody,
                    code = e.response.status.value.toString(),
                ),
            )
        } catch (e: io.ktor.client.plugins.ServerResponseException) {
            val errorBody = e.response.body<String>()
            trace?.updateStatusCode(e.response.status.value)
            NetworkLogcatLogger.logResponse(
                method = "POST",
                url = endpoint,
                statusCode = e.response.status.value,
                statusText = e.response.status.description,
                headers = e.response.headers.entries().associate { it.key to it.value },
                bodyContentType = e.response.headers[HttpHeaders.ContentType],
                body = errorBody,
            )
            QwenChatCompletionResponse(
                error = com.vibe.app.data.dto.qwen.response.QwenErrorDetail(
                    message = errorBody,
                    code = e.response.status.value.toString(),
                ),
            )
        } catch (e: Exception) {
            NetworkLogcatLogger.logNetworkError("POST", endpoint, e)
            QwenChatCompletionResponse(
                error = com.vibe.app.data.dto.qwen.response.QwenErrorDetail(
                    message = e.message ?: "Unknown network error",
                    code = "network_error",
                ),
            )
        }
    }

    override fun streamChatCompletion(
        request: ChatCompletionRequest,
        diagnosticContext: ModelRequestDiagnosticContext?,
        trace: ModelExecutionTrace?,
    ): Flow<ChatCompletionChunk> = flow {
        val endpoint = if (apiUrl.endsWith("/")) "${apiUrl}v1/chat/completions" else "$apiUrl/v1/chat/completions"
        val requestBody = NetworkClient.openAIJson.encodeToJsonElement(request).toString()
        val requestStartedAt = System.currentTimeMillis()
        trace?.markRequestStarted(requestStartedAt)
        diagnosticContext?.let {
            diagnosticLogger.logModelRequest(
                context = it,
                endpointUrl = endpoint,
                requestBodyBytesApprox = requestBody.toByteArray().size,
                startedAt = requestStartedAt,
            )
        }
        logOpenAiRequest(endpoint, requestBody)

        try {
            val startTime = requestStartedAt
            networkClient().preparePost(endpoint) {
                contentType(ContentType.Application.Json)
                setBody(requestBody)
                accept(ContentType.Text.EventStream)
                token?.let { bearerAuth(it) }
            }.execute { response ->
                trace?.markFirstByte(response.status.value)
                NetworkLogcatLogger.logResponse(
                    method = "POST",
                    url = endpoint,
                    statusCode = response.status.value,
                    statusText = response.status.description,
                    headers = response.headers.entries().associate { it.key to it.value },
                    bodyContentType = response.headers[HttpHeaders.ContentType],
                    durationMillis = System.currentTimeMillis() - startTime,
                    streamedBody = response.status.isSuccess(),
                )

                if (!response.status.isSuccess()) {
                    val errorBody = response.body<String>()
                    trace?.updateStatusCode(response.status.value)
                    NetworkLogcatLogger.logResponse(
                        method = "POST",
                        url = endpoint,
                        statusCode = response.status.value,
                        statusText = response.status.description,
                        headers = response.headers.entries().associate { it.key to it.value },
                        bodyContentType = response.headers[HttpHeaders.ContentType],
                        body = errorBody,
                    )

                    val errorMessage = try {
                        val errorResponse = NetworkClient.openAIJson.decodeFromString<OpenAIErrorResponse>(errorBody)
                        errorResponse.error.message
                    } catch (_: Exception) {
                        "HTTP ${response.status.value}: $errorBody"
                    }

                    emit(
                        ChatCompletionChunk(
                            error = ErrorDetail(
                                message = errorMessage,
                                type = "http_error",
                                code = response.status.value.toString()
                            )
                        )
                    )
                    return@execute
                }

                // Success - read SSE stream
                val channel = response.bodyAsChannel()
                val eventLines = mutableListOf<String>()
                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break
                    if (line.isBlank()) {
                        val shouldStop = handleChatCompletionSseEvent(endpoint, eventLines) { emit(it) }
                        eventLines.clear()
                        if (shouldStop) break
                        continue
                    }
                    eventLines += line
                }

                if (eventLines.isNotEmpty()) {
                    handleChatCompletionSseEvent(endpoint, eventLines) { emit(it) }
                }
            }
        } catch (e: Exception) {
            NetworkLogcatLogger.logNetworkError("POST", endpoint, e)
            val errorMessage = when (e) {
                is java.net.UnknownHostException -> "Network error: Unable to resolve host."
                is java.nio.channels.UnresolvedAddressException -> "Network error: Unable to resolve address. Check your internet connection."
                is java.net.ConnectException -> "Network error: Connection refused. Check the API URL."
                is java.net.SocketTimeoutException -> "Network error: Connection timed out."
                is javax.net.ssl.SSLException -> "Network error: SSL/TLS connection failed."
                else -> e.message ?: "Unknown network error"
            }
            emit(
                ChatCompletionChunk(
                    error = ErrorDetail(
                        message = errorMessage,
                        type = "network_error"
                    )
                )
            )
        }
    }

    override fun streamResponses(
        request: ResponsesRequest,
        diagnosticContext: ModelRequestDiagnosticContext?,
        trace: ModelExecutionTrace?,
    ): Flow<ResponsesStreamEvent> = flow {
        val endpoint = if (apiUrl.endsWith("/")) "${apiUrl}v1/responses" else "$apiUrl/v1/responses"
        val requestBody = NetworkClient.openAIJson.encodeToJsonElement(request).toString()
        val requestStartedAt = System.currentTimeMillis()
        trace?.markRequestStarted(requestStartedAt)
        diagnosticContext?.let {
            diagnosticLogger.logModelRequest(
                context = it,
                endpointUrl = endpoint,
                requestBodyBytesApprox = requestBody.toByteArray().size,
                startedAt = requestStartedAt,
            )
        }
        logOpenAiRequest(endpoint, requestBody)

        try {
            val startTime = requestStartedAt
            networkClient().preparePost(endpoint) {
                contentType(ContentType.Application.Json)
                setBody(requestBody)
                accept(ContentType.Text.EventStream)
                token?.let { bearerAuth(it) }
            }.execute { response ->
                trace?.markFirstByte(response.status.value)
                NetworkLogcatLogger.logResponse(
                    method = "POST",
                    url = endpoint,
                    statusCode = response.status.value,
                    statusText = response.status.description,
                    headers = response.headers.entries().associate { it.key to it.value },
                    bodyContentType = response.headers[HttpHeaders.ContentType],
                    durationMillis = System.currentTimeMillis() - startTime,
                    streamedBody = response.status.isSuccess(),
                )

                if (!response.status.isSuccess()) {
                    val errorBody = response.body<String>()
                    trace?.updateStatusCode(response.status.value)
                    NetworkLogcatLogger.logResponse(
                        method = "POST",
                        url = endpoint,
                        statusCode = response.status.value,
                        statusText = response.status.description,
                        headers = response.headers.entries().associate { it.key to it.value },
                        bodyContentType = response.headers[HttpHeaders.ContentType],
                        body = errorBody,
                    )

                    val errorMessage = try {
                        val errorResponse = NetworkClient.openAIJson.decodeFromString<OpenAIErrorResponse>(errorBody)
                        errorResponse.error.message
                    } catch (_: Exception) {
                        "HTTP ${response.status.value}: $errorBody"
                    }

                    emit(ResponseErrorEvent(message = errorMessage, code = response.status.value.toString()))
                    return@execute
                }

                // Success - read SSE stream
                val channel = response.bodyAsChannel()
                val eventLines = mutableListOf<String>()
                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break
                    if (line.isBlank()) {
                        val shouldStop = handleResponsesSseEvent(endpoint, eventLines) { emit(it) }
                        eventLines.clear()
                        if (shouldStop) break
                        continue
                    }
                    eventLines += line
                }

                if (eventLines.isNotEmpty()) {
                    handleResponsesSseEvent(endpoint, eventLines) { emit(it) }
                }
            }
        } catch (e: Exception) {
            NetworkLogcatLogger.logNetworkError("POST", endpoint, e)
            val errorMessage = when (e) {
                is java.net.UnknownHostException -> "Network error: Unable to resolve host."
                is java.nio.channels.UnresolvedAddressException -> "Network error: Unable to resolve address. Check your internet connection."
                is java.net.ConnectException -> "Network error: Connection refused. Check the API URL."
                is java.net.SocketTimeoutException -> "Network error: Connection timed out."
                is javax.net.ssl.SSLException -> "Network error: SSL/TLS connection failed."
                else -> e.message ?: "Unknown network error"
            }
            emit(
                ResponseErrorEvent(
                    message = errorMessage,
                    code = "network_error"
                )
            )
        }
    }

    private fun logOpenAiRequest(
        endpoint: String,
        requestBody: String,
    ) {
        NetworkLogcatLogger.logRequest(
            method = "POST",
            url = endpoint,
            commonHeaders = buildMap {
                put("Accept", ContentType.Text.EventStream.toString())
                if (token != null) {
                    put(HttpHeaders.Authorization, "Bearer $token")
                }
            },
            bodyContentType = ContentType.Application.Json.toString(),
            body = requestBody,
        )
    }

    private suspend fun handleChatCompletionSseEvent(
        endpoint: String,
        lines: List<String>,
        emitEvent: suspend (ChatCompletionChunk) -> Unit,
    ): Boolean {
        if (lines.isEmpty()) {
            return false
        }

        val block = lines.joinToString("\n")
        NetworkLogcatLogger.logSseEvent(endpoint, block)

        val data = lines
            .filter { it.startsWith("data:") }
            .joinToString("\n") { it.removePrefix("data:").trimStart() }
            .trim()

        if (data.isBlank()) {
            return false
        }

        if (data == "[DONE]") {
            return true
        }

        try {
            emitEvent(NetworkClient.openAIJson.decodeFromString(data))
        } catch (e: Exception) {
            NetworkLogcatLogger.logDecodeFailure(endpoint, data, e)
        }

        return false
    }

    private suspend fun handleResponsesSseEvent(
        endpoint: String,
        lines: List<String>,
        emitEvent: suspend (ResponsesStreamEvent) -> Unit,
    ): Boolean {
        if (lines.isEmpty()) {
            return false
        }

        val block = lines.joinToString("\n")
        NetworkLogcatLogger.logSseEvent(endpoint, block)

        val data = lines
            .filter { it.startsWith("data:") }
            .joinToString("\n") { it.removePrefix("data:").trimStart() }
            .trim()

        if (data.isBlank()) {
            return false
        }

        if (data == "[DONE]") {
            return true
        }

        try {
            emitEvent(NetworkClient.openAIJson.decodeFromString(data))
        } catch (e: Exception) {
            NetworkLogcatLogger.logDecodeFailure(endpoint, data, e)
            emitEvent(UnknownEvent)
        }

        return false
    }
}

@Serializable
private data class OpenAIErrorResponse(
    val error: OpenAIError
)

@Serializable
private data class OpenAIError(
    val message: String,
    val type: String? = null,
    val param: String? = null,
    val code: String? = null
)
