package com.vibe.app.data.network

import com.vibe.app.data.ModelConstants
import com.vibe.app.data.dto.anthropic.request.MessageRequest
import com.vibe.app.data.dto.anthropic.response.ErrorDetail
import com.vibe.app.data.dto.anthropic.response.ErrorResponseChunk
import com.vibe.app.data.dto.anthropic.response.MessageResponseChunk
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
    private val networkClient: NetworkClient
) : AnthropicAPI {

    private var token: String? = null
    private var apiUrl: String = ModelConstants.ANTHROPIC_API_URL

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
        explicitNulls = false
    }

    override fun setToken(token: String?) {
        this.token = token
    }

    override fun setAPIUrl(url: String) {
        this.apiUrl = url
    }

    override fun streamChatMessage(messageRequest: MessageRequest): Flow<MessageResponseChunk> = flow {
        val endpoint = if (apiUrl.endsWith("/")) "${apiUrl}v1/messages" else "$apiUrl/v1/messages"
        val requestBody = json.encodeToJsonElement(messageRequest).toString()
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
            val startTime = System.currentTimeMillis()
            networkClient().preparePost(endpoint) {
                contentType(ContentType.Application.Json)
                setBody(requestBody)
                accept(ContentType.Text.EventStream)
                headers {
                    append(API_KEY_HEADER, token ?: "")
                    append(VERSION_HEADER, ANTHROPIC_VERSION)
                }
            }.execute { response ->
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

                    emit(ErrorResponseChunk(error = ErrorDetail(type = "api_error", message = errorMessage)))
                    return@execute
                }

                // Success - read SSE stream
                val channel = response.bodyAsChannel()
                val eventLines = mutableListOf<String>()
                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break
                    if (line.isBlank()) {
                        handleAnthropicSseEvent(endpoint, eventLines) { emit(it) }
                        eventLines.clear()
                        continue
                    }
                    eventLines += line
                }

                if (eventLines.isNotEmpty()) {
                    handleAnthropicSseEvent(endpoint, eventLines) { emit(it) }
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
