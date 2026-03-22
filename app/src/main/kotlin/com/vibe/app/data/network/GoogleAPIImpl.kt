package com.vibe.app.data.network

import com.vibe.app.data.dto.google.request.GenerateContentRequest
import com.vibe.app.data.dto.google.response.ErrorDetail
import com.vibe.app.data.dto.google.response.GenerateContentResponse
import com.vibe.app.feature.diagnostic.ChatDiagnosticLogger
import com.vibe.app.feature.diagnostic.ModelExecutionTrace
import com.vibe.app.feature.diagnostic.ModelRequestDiagnosticContext
import io.ktor.client.call.body
import io.ktor.client.request.parameter
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

class GoogleAPIImpl @Inject constructor(
    private val networkClient: NetworkClient,
    private val diagnosticLogger: ChatDiagnosticLogger,
) : GoogleAPI {

    private var token: String? = null
    private var apiUrl: String = "https://generativelanguage.googleapis.com"

    override fun setToken(token: String?) {
        this.token = token
    }

    override fun setAPIUrl(url: String) {
        this.apiUrl = url
    }

    override fun streamGenerateContent(
        request: GenerateContentRequest,
        model: String,
        diagnosticContext: ModelRequestDiagnosticContext?,
        trace: ModelExecutionTrace?,
    ): Flow<GenerateContentResponse> = flow {
        val endpoint = if (apiUrl.endsWith("/")) {
            "${apiUrl}v1beta/models/$model:streamGenerateContent"
        } else {
            "$apiUrl/v1beta/models/$model:streamGenerateContent"
        }
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
            commonHeaders = mapOf(
                "alt" to "sse",
                "key" to token.orEmpty(),
            ),
            bodyContentType = ContentType.Application.Json.toString(),
            body = requestBody,
        )

        try {
            val startTime = requestStartedAt
            networkClient().preparePost(endpoint) {
                parameter("key", token ?: "")
                parameter("alt", "sse")
                contentType(ContentType.Application.Json)
                setBody(requestBody)
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

                    // Parse error - Google returns array format: [{"error": {...}}]
                    val errorMessage = try {
                        val errorList = NetworkClient.json.decodeFromString<List<GoogleErrorResponse>>(errorBody)
                        errorList.firstOrNull()?.error?.message ?: "Unknown error"
                    } catch (_: Exception) {
                        // Try single object format as fallback
                        try {
                            val errorResponse = NetworkClient.json.decodeFromString<GoogleErrorResponse>(errorBody)
                            errorResponse.error.message
                        } catch (_: Exception) {
                            "HTTP ${response.status.value}: $errorBody"
                        }
                    }

                    emit(
                        GenerateContentResponse(
                            error = ErrorDetail(
                                message = errorMessage,
                                code = response.status.value,
                                status = "ERROR"
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
                        handleGoogleSseEvent(endpoint, eventLines) { emit(it) }
                        eventLines.clear()
                        continue
                    }
                    eventLines += line
                }

                if (eventLines.isNotEmpty()) {
                    handleGoogleSseEvent(endpoint, eventLines) { emit(it) }
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
                GenerateContentResponse(
                    error = ErrorDetail(
                        message = errorMessage,
                        code = -1,
                        status = "NETWORK_ERROR"
                    )
                )
            )
        }
    }

    private suspend fun handleGoogleSseEvent(
        endpoint: String,
        lines: List<String>,
        emitEvent: suspend (GenerateContentResponse) -> Unit,
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
            emitEvent(NetworkClient.json.decodeFromString(data))
        } catch (e: Exception) {
            NetworkLogcatLogger.logDecodeFailure(endpoint, data, e)
        }
    }
}

@Serializable
private data class GoogleErrorResponse(
    val error: GoogleError
)

@Serializable
private data class GoogleError(
    val code: Int? = null,
    val message: String,
    val status: String? = null
)
