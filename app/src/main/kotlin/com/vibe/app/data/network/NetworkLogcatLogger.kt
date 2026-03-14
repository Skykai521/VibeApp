package com.vibe.app.data.network

import android.util.Log

object NetworkLogcatLogger {

    private const val TAG = "VibeNetwork"
    private const val MAX_LOGCAT_CHUNK_LENGTH = 3_000
    private const val MAX_BODY_LENGTH = 4_000
    private const val MAX_MESSAGE_LENGTH = 12_000
    private const val TRUNCATED_SUFFIX = "\n...<truncated>"

    private val sensitiveHeaders = setOf(
        "authorization",
        "x-api-key",
        "api-key",
    )

    fun logRequest(
        method: String,
        url: String,
        commonHeaders: Map<String, String> = emptyMap(),
        contentHeaders: Map<String, String> = emptyMap(),
        bodyContentType: String? = null,
        body: String? = null,
    ) {
        val message = buildString {
            appendLine("REQUEST: $url")
            appendLine("METHOD: $method")
            appendHeaders("COMMON HEADERS", commonHeaders)
            appendHeaders("CONTENT HEADERS", contentHeaders)

            if (bodyContentType != null || body != null) {
                appendLine("BODY Content-Type: ${bodyContentType ?: "unknown"}")
                appendLine("BODY START")
                appendLine((body ?: "").clip(MAX_BODY_LENGTH))
                append("BODY END")
            }
        }

        debug(message)
    }

    fun logResponse(
        method: String,
        url: String,
        statusCode: Int,
        statusText: String,
        headers: Map<String, List<String>> = emptyMap(),
        bodyContentType: String? = null,
        body: String? = null,
        durationMillis: Long? = null,
        streamedBody: Boolean = false,
    ) {
        val message = buildString {
            appendLine("RESPONSE: $statusCode $statusText")
            appendLine("METHOD: $method")
            appendLine("FROM: $url")
            if (durationMillis != null) {
                appendLine("DURATION: ${durationMillis}ms")
            }
            appendHeaders(
                title = "COMMON HEADERS",
                headers = headers.mapValues { (_, values) -> values.joinToString(", ") },
            )

            when {
                body != null -> {
                    appendLine("BODY Content-Type: ${bodyContentType ?: "unknown"}")
                    appendLine("BODY START")
                    appendLine(body.clip(MAX_BODY_LENGTH))
                    append("BODY END")
                }

                streamedBody -> {
                    append("BODY: [streaming content omitted here; SSE events are logged separately]")
                }
            }
        }

        debug(message)
    }

    fun logSseEvent(
        url: String,
        block: String,
    ) {
        debug(
            buildString {
                appendLine("SSE EVENT FROM: $url")
                appendLine("BODY START")
                appendLine(block.clip(MAX_BODY_LENGTH))
                append("BODY END")
            },
        )
    }

    fun logDecodeFailure(
        url: String,
        rawData: String,
        throwable: Throwable,
    ) {
        warn(
            buildString {
                appendLine("SSE DECODE FAILURE: $url")
                appendLine("ERROR: ${throwable.message ?: throwable::class.java.simpleName}")
                appendLine("BODY START")
                appendLine(rawData.clip(MAX_BODY_LENGTH))
                append("BODY END")
            },
        )
    }

    fun logNetworkError(
        method: String,
        url: String,
        throwable: Throwable,
    ) {
        error(
            buildString {
                appendLine("NETWORK ERROR")
                appendLine("METHOD: $method")
                appendLine("URL: $url")
                append("ERROR: ${throwable.message ?: throwable::class.java.simpleName}")
            },
            throwable,
        )
    }

    private fun StringBuilder.appendHeaders(
        title: String,
        headers: Map<String, String>,
    ) {
        appendLine(title)
        if (headers.isEmpty()) {
            appendLine("-> <none>")
            return
        }

        headers.forEach { (name, value) ->
            appendLine("-> $name: ${sanitizeHeaderValue(name, value)}")
        }
    }

    private fun sanitizeHeaderValue(
        name: String,
        value: String,
    ): String {
        return if (name.lowercase() in sensitiveHeaders) "***" else value
    }

    private fun debug(message: String) {
        logChunks(message.clip(MAX_MESSAGE_LENGTH)) { chunk -> Log.d(TAG, chunk) }
    }

    private fun warn(message: String) {
        logChunks(message.clip(MAX_MESSAGE_LENGTH)) { chunk -> Log.w(TAG, chunk) }
    }

    private fun error(
        message: String,
        throwable: Throwable? = null,
    ) {
        logChunks(message.clip(MAX_MESSAGE_LENGTH)) { chunk ->
            if (throwable == null) {
                Log.e(TAG, chunk)
            } else {
                Log.e(TAG, chunk, throwable)
            }
        }
    }

    private fun logChunks(
        message: String,
        printer: (String) -> Unit,
    ) {
        if (message.isBlank()) {
            return
        }

        var startIndex = 0
        while (startIndex < message.length) {
            val endIndex = minOf(startIndex + MAX_LOGCAT_CHUNK_LENGTH, message.length)
            printer(message.substring(startIndex, endIndex))
            startIndex = endIndex
        }
    }

    private fun String.clip(limit: Int): String {
        if (length <= limit) {
            return this
        }

        return substring(0, limit) + TRUNCATED_SUFFIX
    }
}
