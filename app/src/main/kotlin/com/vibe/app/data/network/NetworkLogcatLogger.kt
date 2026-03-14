package com.vibe.app.data.network

import android.util.Log
import io.ktor.client.plugins.logging.Logger

/**
 * Formats Ktor HTTP logs for logcat and clips oversized payloads so streaming
 * or large JSON bodies remain readable without flooding the buffer.
 */
object NetworkLogcatLogger : Logger {

    private const val TAG = "VibeNetwork"
    private const val MAX_LOGCAT_CHUNK_LENGTH = 3_000
    private const val MAX_BODY_LENGTH = 4_000
    private const val MAX_MESSAGE_LENGTH = 12_000
    private const val TRUNCATED_SUFFIX = "\n...<truncated>"

    private val bodyBlockRegex = Regex(
        pattern = "(BODY START\\n)(.*?)(\\nBODY END)",
        options = setOf(RegexOption.DOT_MATCHES_ALL)
    )

    override fun log(message: String) {
        val formattedMessage = formatMessage(message)

        if (formattedMessage.isBlank()) {
            return
        }

        formattedMessage.forEachChunk(MAX_LOGCAT_CHUNK_LENGTH) { chunk ->
            Log.d(TAG, chunk)
        }
    }

    private fun formatMessage(message: String): String {
        val trimmedBodyMessage = trimBodyBlocks(message)
        return trimmedBodyMessage.clip(MAX_MESSAGE_LENGTH)
    }

    private fun trimBodyBlocks(message: String): String {
        return bodyBlockRegex.replace(message) { match ->
            val body = match.groupValues[2]
            val clippedBody = body.clip(MAX_BODY_LENGTH)
            "${match.groupValues[1]}$clippedBody${match.groupValues[3]}"
        }
    }

    private fun String.clip(limit: Int): String {
        if (length <= limit) {
            return this
        }

        return substring(0, limit) + TRUNCATED_SUFFIX
    }

    private inline fun String.forEachChunk(
        chunkSize: Int,
        action: (String) -> Unit
    ) {
        if (length <= chunkSize) {
            action(this)
            return
        }

        var startIndex = 0
        while (startIndex < length) {
            val endIndex = minOf(startIndex + chunkSize, length)
            action(substring(startIndex, endIndex))
            startIndex = endIndex
        }
    }
}
