package com.vibe.app.data.network

import com.vibe.app.data.ModelConstants
import com.vibe.app.data.dto.anthropic.request.MessageRequest
import com.vibe.app.data.dto.anthropic.response.ErrorDetail
import com.vibe.app.data.dto.anthropic.response.ErrorResponseChunk
import com.vibe.app.data.dto.anthropic.response.MessageResponseChunk
import com.vibe.app.data.dto.anthropic.response.MessageStopResponseChunk
import com.vibe.app.feature.diagnostic.ChatDiagnosticLogger
import com.vibe.app.feature.diagnostic.ModelExecutionTrace
import com.vibe.app.feature.diagnostic.ModelRequestDiagnosticContext
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.headers
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

class AnthropicAPIImpl @Inject constructor(
    private val networkClient: NetworkClient,
    private val diagnosticLogger: ChatDiagnosticLogger,
) : AnthropicAPI {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
        explicitNulls = false
    }

    override fun streamChatMessage(
        messageRequest: MessageRequest,
        token: String?,
        apiUrl: String,
        diagnosticContext: ModelRequestDiagnosticContext?,
        trace: ModelExecutionTrace?,
    ): Flow<MessageResponseChunk> = flow {
        val baseUrl = apiUrl.ifBlank { ModelConstants.ANTHROPIC_API_URL }
        val endpoint = if (baseUrl.endsWith("/")) "${baseUrl}v1/messages" else "$baseUrl/v1/messages"
        val requestBody = json.encodeToJsonElement(messageRequest).toString()
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
                put("Accept", ContentType.Text.EventStream.toString())
                put(API_KEY_HEADER, token.orEmpty())
                put(VERSION_HEADER, ANTHROPIC_VERSION)
            },
            bodyContentType = ContentType.Application.Json.toString(),
            body = requestBody,
        )

        try {
            val startTime = requestStartedAt
            networkClient().preparePost(endpoint) {
                contentType(ContentType.Application.Json)
                setBody(requestBody)
                accept(ContentType.Text.EventStream)
                headers {
                    append(API_KEY_HEADER, token ?: "")
                    append(VERSION_HEADER, ANTHROPIC_VERSION)
                }
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

                    // Parse error - Anthropic format: {"type": "error", "error": {"type": "...", "message": "..."}}
                    val errorMessage = try {
                        val errorResponse = json.decodeFromString<AnthropicErrorResponse>(errorBody)
                        errorResponse.error.message
                    } catch (_: Exception) {
                        "HTTP ${response.status.value}: $errorBody"
                    }

                    emit(
                        ErrorResponseChunk(
                            error = ErrorDetail(
                                type = "api_error",
                                message = errorMessage,
                                statusCode = response.status.value,
                                retryAfterSeconds = response.headers["Retry-After"]?.toIntOrNull(),
                            ),
                        ),
                    )
                    return@execute
                }

                // Success - read SSE stream
                val channel = response.bodyAsChannel()
                val eventLines = mutableListOf<String>()
                var sawTerminal = false
                val trackingEmit: suspend (MessageResponseChunk) -> Unit = { chunk ->
                    // message_stop is the normal terminator; a server-sent error chunk (e.g.
                    // overloaded_error) also legitimately ends the stream without message_stop —
                    // treat both as terminal so a real error isn't masked by a spurious
                    // stream_interrupted emitted after it.
                    if (chunk is MessageStopResponseChunk || chunk is ErrorResponseChunk) {
                        sawTerminal = true
                    }
                    emit(chunk)
                }
                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break
                    if (line.isBlank()) {
                        handleAnthropicSseEvent(endpoint, eventLines, trackingEmit)
                        eventLines.clear()
                        continue
                    }
                    eventLines += line
                }
                if (eventLines.isNotEmpty()) {
                    handleAnthropicSseEvent(endpoint, eventLines, trackingEmit)
                }
                if (!sawTerminal) {
                    emit(
                        ErrorResponseChunk(
                            error = ErrorDetail(
                                type = "stream_interrupted",
                                message = "SSE stream ended without message_stop — response was truncated by a dropped connection.",
                            ),
                        ),
                    )
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
            emit(ErrorResponseChunk(error = ErrorDetail(type = "network_error", message = errorMessage)))
        }
    }

    private suspend fun handleAnthropicSseEvent(
        endpoint: String,
        lines: List<String>,
        emitEvent: suspend (MessageResponseChunk) -> Unit,
    ) {
        if (lines.isEmpty()) {
            return
        }

        val block = lines.joinToString("\n")
        NetworkLogcatLogger.logSseEvent(endpoint, block)

        val data = lines
            .filter { it.startsWith("data:") }
            .joinToString("\n") { it.removePrefix("data:").trimStart() }
            .trim()

        if (data.isBlank()) {
            return
        }

        try {
            emitEvent(json.decodeFromString(data))
        } catch (e: Exception) {
            NetworkLogcatLogger.logDecodeFailure(endpoint, data, e)
        }
    }

    companion object {
        private const val API_KEY_HEADER = "x-api-key"
        private const val VERSION_HEADER = "anthropic-version"
        private const val ANTHROPIC_VERSION = "2023-06-01"
    }
}

@Serializable
private data class AnthropicErrorResponse(
    val type: String,
    val error: AnthropicError
)

@Serializable
private data class AnthropicError(
    val type: String,
    val message: String
)
