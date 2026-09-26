package com.vibe.app.feature.agent.loop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelFailureClassifierTest {

    @Test
    fun `429 and 5xx are retryable`() {
        assertTrue(ModelFailureClassifier.isRetryable(statusCode = 429, errorType = "http_error"))
        assertTrue(ModelFailureClassifier.isRetryable(statusCode = 500, errorType = "api_error"))
        assertTrue(ModelFailureClassifier.isRetryable(statusCode = 529, errorType = "api_error"))
        assertTrue(ModelFailureClassifier.isRetryable(statusCode = 408, errorType = "http_error"))
    }

    @Test
    fun `4xx client errors are not retryable`() {
        assertFalse(ModelFailureClassifier.isRetryable(statusCode = 400, errorType = "http_error"))
        assertFalse(ModelFailureClassifier.isRetryable(statusCode = 401, errorType = "api_error"))
        assertFalse(ModelFailureClassifier.isRetryable(statusCode = 404, errorType = "http_error"))
    }

    @Test
    fun `network and stream interruption errors are retryable regardless of status`() {
        assertTrue(ModelFailureClassifier.isRetryable(statusCode = null, errorType = "network_error"))
        assertTrue(ModelFailureClassifier.isRetryable(statusCode = null, errorType = "stream_interrupted"))
    }

    @Test
    fun `unknown error without status is not retryable`() {
        assertFalse(ModelFailureClassifier.isRetryable(statusCode = null, errorType = "provider_error"))
        assertFalse(ModelFailureClassifier.isRetryable(statusCode = null, errorType = null))
    }
}
