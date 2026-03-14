package com.vibe.app.data.network

import com.vibe.app.data.ModelConstants
import com.vibe.app.data.dto.openai.request.ChatCompletionRequest
import com.vibe.app.data.dto.openai.request.ResponsesRequest
import com.vibe.app.data.dto.openai.response.ChatCompletionChunk
import com.vibe.app.data.dto.openai.response.ErrorDetail
import com.vibe.app.data.dto.openai.response.ResponseErrorEvent
import com.vibe.app.data.dto.openai.response.ResponsesStreamEvent
import com.vibe.app.data.dto.openai.response.UnknownEvent
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
    private val networkClient: NetworkClient
) : OpenAIAPI {

    private var token: String? = null
    private var apiUrl: String = ModelConstants.OPENAI_API_URL

    override fun setToken(token: String?) {
        this.token = token
    }

    override fun setAPIUrl(url: String) {
        this.apiUrl = url
    }

    override fun streamChatCompletion(request: ChatCompletionRequest): Flow<ChatCompletionChunk> = flow {
        val endpoint = if (apiUrl.endsWith("/")) "${apiUrl}v1/chat/completions" else "$apiUrl/v1/chat/completions"
        val requestBody = NetworkClient.openAIJson.encodeToJsonElement(request).toString()
        logOpenAiRequest(endpoint, requestBody)

        try {
            val startTime = System.currentTimeMillis()
            networkClient().preparePost(endpoint) {
                contentType(ContentType.Application.Json)
                setBody(requestBody)
                accept(ContentType.Text.EventStream)
                token?.let { bearerAuth(it) }
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

    override fun streamResponses(request: ResponsesRequest): Flow<ResponsesStreamEvent> = flow {
        val endpoint = if (apiUrl.endsWith("/")) "${apiUrl}v1/responses" else "$apiUrl/v1/responses"
        val requestBody = NetworkClient.openAIJson.encodeToJsonElement(request).toString()
        logOpenAiRequest(endpoint, requestBody)

        try {
            val startTime = System.currentTimeMillis()
            networkClient().preparePost(endpoint) {
                contentType(ContentType.Application.Json)
                setBody(requestBody)
                accept(ContentType.Text.EventStream)
                token?.let { bearerAuth(it) }
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
