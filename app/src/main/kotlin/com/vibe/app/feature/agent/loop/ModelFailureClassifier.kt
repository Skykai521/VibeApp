package com.vibe.app.feature.agent.loop

/**
 * Classifies model-request failures into retryable (transient) vs fatal.
 * Retryable: rate limits, server errors, network hiccups, interrupted SSE streams.
 * Fatal: auth errors, bad requests, anything we cannot fix by waiting.
 */
object ModelFailureClassifier {

    const val TYPE_STREAM_INTERRUPTED = "stream_interrupted"

    private val retryableTypes = setOf("network_error", TYPE_STREAM_INTERRUPTED)

    fun isRetryable(statusCode: Int?, errorType: String?): Boolean {
        if (errorType in retryableTypes) return true
        val code = statusCode ?: return false
        return code == 408 || code == 429 || code >= 500
    }
}
