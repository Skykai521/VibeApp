package com.vibe.app.feature.diagnostic

import com.vibe.app.data.model.ClientType
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class DiagnosticEvent(
    val id: String,
    val timestamp: Long,
    val chatId: Int,
    val projectId: String? = null,
    val platformUid: String? = null,
    val turnId: String? = null,
    val category: String,
    val level: String,
    val summary: String,
    val payload: JsonObject,
)

@Serializable
data class DiagnosticIndex(
    val chatId: Int,
    val schemaVersion: Int,
    val currentFile: String,
    val eventCount: Long,
    val lastUpdatedAt: Long,
    val byteSizeApprox: Long,
)

data class DiagnosticContext(
    val chatId: Int,
    val projectId: String? = null,
    val platformUid: String? = null,
    val turnId: String? = null,
)

data class ChatTurnDiagnosticContext(
    val diagnosticContext: DiagnosticContext,
    val turnIndex: Int,
    val isAgentMode: Boolean,
    val platformUids: List<String>,
    val userTextChars: Int,
    val attachmentCount: Int,
    val attachmentKinds: List<String>,
    val startedAt: Long,
)

data class ModelRequestDiagnosticContext(
    val diagnosticContext: DiagnosticContext,
    val providerType: String,
    val apiFamily: String,
    val model: String,
    val stream: Boolean,
    val reasoningEnabled: Boolean,
    val estimatedContextTokens: Int,
    val messageCount: Int,
    val userMessageCount: Int? = null,
    val assistantMessageCount: Int? = null,
    val toolCount: Int? = null,
    val toolChoiceMode: String? = null,
    val systemPromptPresent: Boolean = false,
    val systemPromptChars: Int? = null,
    val hasImages: Boolean = false,
    val imageCount: Int? = null,
)

data class DiagnosticLogSnapshot(
    val content: String,
    val eventCount: Long,
)

object DiagnosticCategories {
    const val CHAT_TURN = "CHAT_TURN"
    const val MODEL_REQUEST = "MODEL_REQUEST"
    const val MODEL_RESPONSE = "MODEL_RESPONSE"
    const val LATENCY_BREAKDOWN = "LATENCY_BREAKDOWN"
    const val BUILD_RESULT = "BUILD_RESULT"
    const val AGENT_TOOL = "AGENT_TOOL"
    const val AGENT_LOOP = "AGENT_LOOP"
}

object DiagnosticLevels {
    const val INFO = "INFO"
    const val WARN = "WARN"
    const val ERROR = "ERROR"
}

object BuildTriggerSource {
    const val CHAT_BUTTON = "chat_button"
    const val AGENT_TOOL = "agent_tool"
    const val TEMPLATE_INIT = "template_init"
}

class ModelExecutionTrace(
    val requestCreatedAt: Long = System.currentTimeMillis(),
) {
    var requestPreparedAt: Long? = null
        private set
    var requestStartedAt: Long? = null
        private set
    var firstByteAt: Long? = null
        private set
    var firstSemanticAt: Long? = null
        private set
    var firstToolCallAt: Long? = null
        private set
    var firstOutputAt: Long? = null
        private set
    var completedAt: Long? = null
        private set
    var statusCode: Int? = null
        private set
    var inputTokens: Int? = null
        private set
    var finishReason: String? = null
    var errorKind: String? = null
        private set
    var errorMessagePreview: String? = null
        private set
    var outputChars: Int = 0
        private set
    var thinkingChars: Int = 0
        private set
    var toolCallCount: Int = 0
        private set
    var maxSilentGapMs: Long? = null
        private set
    var lastObservedStage: String? = null
        private set

    private var lastObservedEventAt: Long? = null

    fun markRequestPrepared(now: Long = System.currentTimeMillis()) {
        if (requestPreparedAt == null) {
            requestPreparedAt = now
        }
        lastObservedStage = "request_prepared"
    }

    fun markRequestStarted(now: Long = System.currentTimeMillis()) {
        if (requestStartedAt == null) {
            requestStartedAt = now
        }
        markObserved(now, "request_started")
    }

    fun markFirstByte(statusCode: Int? = null, now: Long = System.currentTimeMillis()) {
        if (firstByteAt == null) {
            firstByteAt = now
        }
        if (statusCode != null) {
            this.statusCode = statusCode
        }
        markObserved(now, "first_byte")
    }

    fun markThinking(delta: String, now: Long = System.currentTimeMillis()) {
        if (delta.isEmpty()) return
        thinkingChars += delta.length
        markSemantic(now, "thinking")
    }

    fun markOutput(delta: String, now: Long = System.currentTimeMillis()) {
        if (delta.isEmpty()) return
        outputChars += delta.length
        if (firstOutputAt == null) {
            firstOutputAt = now
        }
        markSemantic(now, "output")
    }

    fun markToolCall(now: Long = System.currentTimeMillis()) {
        toolCallCount += 1
        if (firstToolCallAt == null) {
            firstToolCallAt = now
        }
        markSemantic(now, "tool_call")
    }

    fun markCompleted(
        finishReason: String? = null,
        now: Long = System.currentTimeMillis(),
    ) {
        if (completedAt == null) {
            completedAt = now
        }
        if (finishReason != null) {
            this.finishReason = finishReason
        }
        markObserved(now, "completed")
    }

    fun markFailed(
        errorKind: String,
        errorMessage: String?,
        now: Long = System.currentTimeMillis(),
    ) {
        if (completedAt == null) {
            completedAt = now
        }
        this.errorKind = errorKind
        this.errorMessagePreview = errorMessage?.clipPreview()
        markObserved(now, "failed")
    }

    fun updateStatusCode(statusCode: Int?) {
        if (statusCode != null) {
            this.statusCode = statusCode
        }
    }

    fun markInputTokens(tokens: Int?) {
        if (tokens != null && tokens >= 0) {
            inputTokens = tokens
        }
    }

    private fun markSemantic(now: Long, stage: String) {
        if (firstSemanticAt == null) {
            firstSemanticAt = now
        }
        markObserved(now, stage)
    }

    private fun markObserved(now: Long, stage: String) {
        val previous = lastObservedEventAt
        if (previous != null) {
            val gap = now - previous
            if (gap >= 0) {
                maxSilentGapMs = maxOf(maxSilentGapMs ?: 0L, gap)
            }
        }
        lastObservedEventAt = now
        lastObservedStage = stage
    }
}

fun createDiagnosticEventId(timestamp: Long = System.currentTimeMillis()): String {
    return "$timestamp-${UUID.randomUUID().toString().takeLast(12)}"
}

fun ClientType.toDiagnosticProviderType(): String = when (this) {
    ClientType.OPENAI -> "openai"
    ClientType.ANTHROPIC -> "anthropic"
    ClientType.QWEN -> "qwen"
    ClientType.KIMI -> "kimi"
    ClientType.MINIMAX -> "minimax"
    ClientType.DEEPSEEK -> "deepseek"
}

fun String.clipPreview(maxLength: Int = 240): String {
    val normalized = replace('\n', ' ').trim()
    return if (normalized.length <= maxLength) normalized else normalized.take(maxLength) + "..."
}
