package com.vibe.app.feature.diagnostic

import android.content.Context
import android.util.Log
import com.vibe.app.feature.agent.AgentToolCall
import com.vibe.app.feature.agent.AgentToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

interface ChatDiagnosticLogger {
    suspend fun logChatTurnStarted(context: ChatTurnDiagnosticContext)
    suspend fun logChatTurnFinished(
        context: ChatTurnDiagnosticContext,
        success: Boolean,
        finishReason: String? = null,
        outputChars: Int = 0,
        thinkingChars: Int = 0,
        completedAt: Long = System.currentTimeMillis(),
    )

    suspend fun logModelRequest(
        context: ModelRequestDiagnosticContext,
        endpointUrl: String,
        requestBodyBytesApprox: Int,
        startedAt: Long,
    )

    suspend fun logModelResponse(
        context: ModelRequestDiagnosticContext,
        trace: ModelExecutionTrace,
        success: Boolean,
    )

    suspend fun logLatencyBreakdown(
        context: ModelRequestDiagnosticContext,
        trace: ModelExecutionTrace,
    )

    suspend fun logAgentToolStarted(
        context: DiagnosticContext,
        iteration: Int,
        call: AgentToolCall,
        startedAt: Long,
    )

    suspend fun logAgentToolFinished(
        context: DiagnosticContext,
        iteration: Int,
        result: AgentToolResult,
        startedAt: Long,
        completedAt: Long = System.currentTimeMillis(),
    )

    suspend fun logConversationCompaction(
        context: DiagnosticContext,
        iteration: Int,
        strategy: String,
        turnsCompacted: Int,
        estimatedTokens: Int,
        itemsBefore: Int,
        itemsAfter: Int,
    )

    /**
     * Generic entry point for any [DiagnosticCategories.AGENT_LOOP] event.
     *
     * Use this for new agent-loop lifecycle events (loop start/end, iteration start,
     * wind-down, etc.) instead of adding a bespoke interface method for each one.
     * The [payload] **must** include an `"action"` key so consumers can distinguish
     * event subtypes within the AGENT_LOOP category.
     */
    suspend fun logAgentLoopEvent(
        context: DiagnosticContext,
        action: String,
        level: String = DiagnosticLevels.INFO,
        summary: String,
        payload: JsonObject,
    )

    suspend fun readChatLog(chatId: Int): DiagnosticLogSnapshot?

    suspend fun migrateChatLogs(fromChatId: Int, toChatId: Int)

    suspend fun deleteChatLog(chatId: Int)
}

@Singleton
class ChatDiagnosticLoggerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : ChatDiagnosticLogger {

    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    override suspend fun logChatTurnStarted(context: ChatTurnDiagnosticContext) {
        val timestamp = context.startedAt
        writeEvent(
            DiagnosticEvent(
                id = createDiagnosticEventId(timestamp),
                timestamp = timestamp,
                chatId = context.diagnosticContext.chatId,
                projectId = context.diagnosticContext.projectId,
                turnId = context.diagnosticContext.turnId,
                category = DiagnosticCategories.CHAT_TURN,
                level = DiagnosticLevels.INFO,
                summary = "Chat turn ${context.turnIndex} started",
                payload = buildJsonObject {
                    put("action", "turn_started")
                    put("turnIndex", context.turnIndex)
                    put("isAgentMode", context.isAgentMode)
                    put("enabledPlatformCount", context.platformUids.size)
                    putJsonArray("platformUids") {
                        context.platformUids.forEach { add(JsonPrimitive(it)) }
                    }
                    put("userTextChars", context.userTextChars)
                    put("hasAttachments", context.attachmentCount > 0)
                    put("attachmentCount", context.attachmentCount)
                    if (context.attachmentKinds.isNotEmpty()) {
                        putJsonArray("attachmentKinds") {
                            context.attachmentKinds.forEach { add(JsonPrimitive(it)) }
                        }
                    }
                    put("startedAt", context.startedAt)
                },
            ),
        )
    }

    override suspend fun logChatTurnFinished(
        context: ChatTurnDiagnosticContext,
        success: Boolean,
        finishReason: String?,
        outputChars: Int,
        thinkingChars: Int,
        completedAt: Long,
    ) {
        val durationMs = (completedAt - context.startedAt).coerceAtLeast(0L)
        writeEvent(
            DiagnosticEvent(
                id = createDiagnosticEventId(completedAt),
                timestamp = completedAt,
                chatId = context.diagnosticContext.chatId,
                projectId = context.diagnosticContext.projectId,
                turnId = context.diagnosticContext.turnId,
                category = DiagnosticCategories.CHAT_TURN,
                level = if (success) DiagnosticLevels.INFO else DiagnosticLevels.WARN,
                summary = "Chat turn ${context.turnIndex} ${if (success) "completed" else "failed"}",
                payload = buildJsonObject {
                    put("action", if (success) "turn_completed" else "turn_failed")
                    put("turnIndex", context.turnIndex)
                    put("isAgentMode", context.isAgentMode)
                    put("enabledPlatformCount", context.platformUids.size)
                    putJsonArray("platformUids") {
                        context.platformUids.forEach { add(JsonPrimitive(it)) }
                    }
                    put("userTextChars", context.userTextChars)
                    put("hasAttachments", context.attachmentCount > 0)
                    put("attachmentCount", context.attachmentCount)
                    if (context.attachmentKinds.isNotEmpty()) {
                        putJsonArray("attachmentKinds") {
                            context.attachmentKinds.forEach { add(JsonPrimitive(it)) }
                        }
                    }
                    put("startedAt", context.startedAt)
                    put("completedAt", completedAt)
                    put("durationMs", durationMs)
                    put("outputChars", outputChars)
                    put("thinkingChars", thinkingChars)
                    putIfNotNull("finishReason", finishReason)
                },
            ),
        )
    }

    override suspend fun logModelRequest(
        context: ModelRequestDiagnosticContext,
        endpointUrl: String,
        requestBodyBytesApprox: Int,
        startedAt: Long,
    ) {
        writeEvent(
            DiagnosticEvent(
                id = createDiagnosticEventId(startedAt),
                timestamp = startedAt,
                chatId = context.diagnosticContext.chatId,
                projectId = context.diagnosticContext.projectId,
                platformUid = context.diagnosticContext.platformUid,
                turnId = context.diagnosticContext.turnId,
                category = DiagnosticCategories.MODEL_REQUEST,
                level = DiagnosticLevels.INFO,
                summary = "${context.providerType} ${context.apiFamily} request started",
                payload = buildJsonObject {
                    put("providerType", context.providerType)
                    put("apiFamily", context.apiFamily)
                    put("requestMethod", "POST")
                    put("endpointPath", endpointUrl.toEndpointPathSummary())
                    put("model", context.model)
                    put("stream", context.stream)
                    put("reasoningEnabled", context.reasoningEnabled)
                    put("estimatedTokens", context.estimatedContextTokens)
                    put("messageCount", context.messageCount)
                    putIfNotNull("userMessageCount", context.userMessageCount)
                    putIfNotNull("assistantMessageCount", context.assistantMessageCount)
                    putIfNotNull("toolCount", context.toolCount)
                    putIfNotNull("toolChoiceMode", context.toolChoiceMode)
                    put("systemPromptPresent", context.systemPromptPresent)
                    putIfNotNull("systemPromptChars", context.systemPromptChars)
                    put("hasImages", context.hasImages)
                    putIfNotNull("imageCount", context.imageCount)
                    put("requestBodyBytesApprox", requestBodyBytesApprox)
                    put("startedAt", startedAt)
                },
            ),
        )
    }

    override suspend fun logModelResponse(
        context: ModelRequestDiagnosticContext,
        trace: ModelExecutionTrace,
        success: Boolean,
    ) {
        val completedAt = trace.completedAt ?: System.currentTimeMillis()
        val durationMs = trace.requestStartedAt?.let { completedAt - it }?.coerceAtLeast(0L)
        writeEvent(
            DiagnosticEvent(
                id = createDiagnosticEventId(completedAt),
                timestamp = completedAt,
                chatId = context.diagnosticContext.chatId,
                projectId = context.diagnosticContext.projectId,
                platformUid = context.diagnosticContext.platformUid,
                turnId = context.diagnosticContext.turnId,
                category = DiagnosticCategories.MODEL_RESPONSE,
                level = if (success) DiagnosticLevels.INFO else DiagnosticLevels.WARN,
                summary = "${context.providerType} ${if (success) "response completed" else "response failed"}",
                payload = buildJsonObject {
                    put("action", if (success) "response_completed" else "response_failed")
                    put("providerType", context.providerType)
                    put("model", context.model)
                    putIfNotNull("statusCode", trace.statusCode)
                    putIfNotNull("durationMs", durationMs)
                    putIfNotNull("firstByteLatencyMs", trace.requestStartedAt?.let { trace.firstByteAt?.minus(it) })
                    putIfNotNull("firstSemanticLatencyMs", trace.requestStartedAt?.let { trace.firstSemanticAt?.minus(it) })
                    put("contextTokens", trace.inputTokens ?: context.estimatedContextTokens)
                    putIfNotNull("inputTokens", trace.inputTokens)
                    put("outputChars", trace.outputChars)
                    put("thinkingChars", trace.thinkingChars)
                    put("toolCallCount", trace.toolCallCount)
                    putIfNotNull("finishReason", trace.finishReason)
                    putIfNotNull("errorKind", trace.errorKind)
                    putIfNotNull("errorMessagePreview", trace.errorMessagePreview)
                },
            ),
        )
    }

    override suspend fun logLatencyBreakdown(
        context: ModelRequestDiagnosticContext,
        trace: ModelExecutionTrace,
    ) {
        val completedAt = trace.completedAt ?: System.currentTimeMillis()
        val totalDurationMs = trace.requestStartedAt?.let { completedAt - it } ?: 0L
        writeEvent(
            DiagnosticEvent(
                id = createDiagnosticEventId(completedAt),
                timestamp = completedAt,
                chatId = context.diagnosticContext.chatId,
                projectId = context.diagnosticContext.projectId,
                platformUid = context.diagnosticContext.platformUid,
                turnId = context.diagnosticContext.turnId,
                category = DiagnosticCategories.LATENCY_BREAKDOWN,
                level = DiagnosticLevels.INFO,
                summary = "${context.providerType} latency breakdown",
                payload = buildJsonObject {
                    put("providerType", context.providerType)
                    put("model", context.model)
                    putIfNotNull("requestPreparedMs", trace.requestPreparedAt?.minus(trace.requestCreatedAt))
                    putIfNotNull("firstByteLatencyMs", trace.requestStartedAt?.let { trace.firstByteAt?.minus(it) })
                    putIfNotNull("firstSemanticLatencyMs", trace.requestStartedAt?.let { trace.firstSemanticAt?.minus(it) })
                    putIfNotNull("firstToolCallLatencyMs", trace.requestStartedAt?.let { trace.firstToolCallAt?.minus(it) })
                    putIfNotNull("firstOutputLatencyMs", trace.requestStartedAt?.let { trace.firstOutputAt?.minus(it) })
                    put("totalDurationMs", totalDurationMs.coerceAtLeast(0L))
                    putIfNotNull("maxSilentGapMs", trace.maxSilentGapMs)
                    putIfNotNull("timeoutKind", trace.errorKind?.takeIf { it.contains("timeout") })
                    putIfNotNull("lastObservedStage", trace.lastObservedStage)
                },
            ),
        )
    }

    override suspend fun logAgentToolStarted(
        context: DiagnosticContext,
        iteration: Int,
        call: AgentToolCall,
        startedAt: Long,
    ) {
        writeEvent(
            DiagnosticEvent(
                id = createDiagnosticEventId(startedAt),
                timestamp = startedAt,
                chatId = context.chatId,
                projectId = context.projectId,
                platformUid = context.platformUid,
                turnId = context.turnId,
                category = DiagnosticCategories.AGENT_TOOL,
                level = DiagnosticLevels.INFO,
                summary = "Tool ${call.name} started",
                payload = buildJsonObject {
                    put("action", "tool_started")
                    put("iteration", iteration)
                    put("toolName", call.name)
                    put("toolCallId", call.id)
                    put("argumentBytesApprox", call.arguments.toString().toByteArray(StandardCharsets.UTF_8).size)
                    put("startedAt", startedAt)
                },
            ),
        )
    }

    override suspend fun logAgentToolFinished(
        context: DiagnosticContext,
        iteration: Int,
        result: AgentToolResult,
        startedAt: Long,
        completedAt: Long,
    ) {
        val outputString = result.output.toString()
        val errorMessage = if (result.isError) result.output.extractErrorMessage() else null
        val errorKind = if (result.isError) classifyToolError(errorMessage, result.output) else null
        val summary = if (result.isError) {
            val preview = errorMessage?.clipPreview(120) ?: "no error message"
            "Tool ${result.toolName} failed: $preview"
        } else {
            "Tool ${result.toolName} finished"
        }
        if (result.isError) {
            Log.w(
                TAG,
                "Tool ${result.toolName} (callId=${result.toolCallId}, iter=$iteration) failed: " +
                    "${errorMessage ?: "<no message>"} | output=${outputString.clipPreview(500)}",
            )
        }
        writeEvent(
            DiagnosticEvent(
                id = createDiagnosticEventId(completedAt),
                timestamp = completedAt,
                chatId = context.chatId,
                projectId = context.projectId,
                platformUid = context.platformUid,
                turnId = context.turnId,
                category = DiagnosticCategories.AGENT_TOOL,
                level = if (result.isError) DiagnosticLevels.WARN else DiagnosticLevels.INFO,
                summary = summary,
                payload = buildJsonObject {
                    put("action", "tool_finished")
                    put("iteration", iteration)
                    put("toolName", result.toolName)
                    put("toolCallId", result.toolCallId)
                    put("startedAt", startedAt)
                    put("completedAt", completedAt)
                    put("durationMs", (completedAt - startedAt).coerceAtLeast(0L))
                    put("success", !result.isError)
                    putIfNotNull("errorKind", errorKind)
                    putIfNotNull("errorMessage", errorMessage)
                    putIfNotNull("errorMessagePreview", errorMessage?.clipPreview())
                    if (result.isError) {
                        // Keep the raw output preview so non-standard error shapes
                        // (missing "error" key, nested objects) remain debuggable from the log alone.
                        put("outputPreview", outputString.clipPreview(1000))
                    }
                    put("outputBytesApprox", outputString.toByteArray(StandardCharsets.UTF_8).size)
                },
            ),
        )
    }

    private fun JsonElement.extractErrorMessage(): String? {
        return runCatching {
            val obj = jsonObject
            // Conventional shape from AgentToolCall.errorResult: {"error": "..."}.
            obj["error"]?.jsonPrimitive?.contentOrNull
                ?: obj["message"]?.jsonPrimitive?.contentOrNull
                ?: obj["errorMessage"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun classifyToolError(message: String?, output: JsonElement): String {
        val probe = (message ?: output.toString()).lowercase()
        return when {
            "timeout" in probe || "timed out" in probe -> "tool_timeout"
            "not found" in probe || "no such" in probe || "missing" in probe -> "tool_not_found"
            "permission" in probe || "denied" in probe -> "tool_permission_denied"
            "cancel" in probe -> "tool_cancelled"
            else -> "tool_error"
        }
    }

    override suspend fun logConversationCompaction(
        context: DiagnosticContext,
        iteration: Int,
        strategy: String,
        turnsCompacted: Int,
        estimatedTokens: Int,
        itemsBefore: Int,
        itemsAfter: Int,
    ) {
        logAgentLoopEvent(
            context = context,
            action = "conversation_compaction",
            summary = "Conversation compacted: $strategy ($itemsBefore→$itemsAfter items, ~${estimatedTokens}tok)",
            payload = buildJsonObject {
                put("action", "conversation_compaction")
                put("iteration", iteration)
                put("strategy", strategy)
                put("turnsCompacted", turnsCompacted)
                put("estimatedTokens", estimatedTokens)
                put("itemsBefore", itemsBefore)
                put("itemsAfter", itemsAfter)
            },
        )
    }

    override suspend fun logAgentLoopEvent(
        context: DiagnosticContext,
        action: String,
        level: String,
        summary: String,
        payload: JsonObject,
    ) {
        val timestamp = System.currentTimeMillis()
        writeEvent(
            DiagnosticEvent(
                id = createDiagnosticEventId(timestamp),
                timestamp = timestamp,
                chatId = context.chatId,
                projectId = context.projectId,
                platformUid = context.platformUid,
                turnId = context.turnId,
                category = DiagnosticCategories.AGENT_LOOP,
                level = level,
                summary = summary,
                payload = payload,
            ),
        )
    }

    override suspend fun readChatLog(chatId: Int): DiagnosticLogSnapshot? = withContext(Dispatchers.IO) {
        runCatching {
            val currentFile = currentLogFile(chatId)
            if (!currentFile.exists()) {
                return@withContext null
            }
            val content = currentFile.readText(StandardCharsets.UTF_8)
            val index = readIndexLocked(chatId)
            DiagnosticLogSnapshot(
                content = content,
                eventCount = index?.eventCount ?: content.lineSequence().count { it.isNotBlank() }.toLong(),
            )
        }.getOrElse {
            Log.w(TAG, "Failed to read chat diagnostics for $chatId", it)
            null
        }
    }

    override suspend fun migrateChatLogs(fromChatId: Int, toChatId: Int) {
        if (fromChatId <= 0 || fromChatId == toChatId) return
        withContext(Dispatchers.IO) {
            runCatching {
                mutex.withLock {
                    val sourceFile = currentLogFile(fromChatId)
                    if (!sourceFile.exists()) {
                        deleteChatDirectory(fromChatId)
                        return@withLock
                    }

                    val rewrittenLines = sourceFile.useLines { lines ->
                        lines.filter { it.isNotBlank() }
                            .mapNotNull { line ->
                                runCatching {
                                    val event = json.decodeFromString<DiagnosticEvent>(line)
                                    json.encodeToString(event.copy(chatId = toChatId))
                                }.getOrNull()
                            }
                            .toList()
                    }
                    if (rewrittenLines.isEmpty()) {
                        deleteChatDirectory(fromChatId)
                        return@withLock
                    }

                    val destinationDir = chatDirectory(toChatId)
                    destinationDir.mkdirs()
                    val destinationFile = currentLogFile(toChatId)
                    if (!destinationFile.exists()) {
                        destinationFile.createNewFile()
                    }
                    destinationFile.appendText(
                        rewrittenLines.joinToString(separator = "\n", postfix = "\n"),
                        StandardCharsets.UTF_8,
                    )
                    writeIndexLocked(toChatId, System.currentTimeMillis())
                    deleteChatDirectory(fromChatId)
                }
            }.onFailure {
                Log.w(TAG, "Failed to migrate chat diagnostics $fromChatId -> $toChatId", it)
            }
        }
    }

    override suspend fun deleteChatLog(chatId: Int) {
        if (chatId <= 0) return
        withContext(Dispatchers.IO) {
            runCatching {
                mutex.withLock {
                    deleteChatDirectory(chatId)
                }
            }.onFailure {
                Log.w(TAG, "Failed to delete diagnostic log for chatId=$chatId", it)
            }
        }
    }

    private suspend fun writeEvent(event: DiagnosticEvent) {
        withContext(Dispatchers.IO) {
            runCatching {
                mutex.withLock {
                    val directory = chatDirectory(event.chatId)
                    directory.mkdirs()
                    rollIfNeeded(event.chatId)
                    val currentFile = currentLogFile(event.chatId)
                    if (!currentFile.exists()) {
                        currentFile.createNewFile()
                    }
                    currentFile.appendText(json.encodeToString(event) + "\n", StandardCharsets.UTF_8)
                    writeIndexLocked(event.chatId, event.timestamp)
                    pruneChatLogsIfNeeded()
                }
            }.onFailure {
                Log.w(TAG, "Failed to write diagnostic event ${event.category}", it)
            }
        }
    }

    private fun chatDirectory(chatId: Int): File = File(context.filesDir, "logs/chats/$chatId")

    private fun currentLogFile(chatId: Int): File = File(chatDirectory(chatId), CURRENT_LOG_FILE)

    private fun indexFile(chatId: Int): File = File(chatDirectory(chatId), INDEX_FILE)

    private fun readIndexLocked(chatId: Int): DiagnosticIndex? {
        val indexFile = indexFile(chatId)
        if (!indexFile.exists()) return null
        return runCatching {
            json.decodeFromString<DiagnosticIndex>(indexFile.readText(StandardCharsets.UTF_8))
        }.getOrNull()
    }

    private fun writeIndexLocked(chatId: Int, timestamp: Long) {
        val currentFile = currentLogFile(chatId)
        val eventCount = if (currentFile.exists()) {
            currentFile.useLines { lines -> lines.count { it.isNotBlank() }.toLong() }
        } else {
            0L
        }
        val index = DiagnosticIndex(
            chatId = chatId,
            schemaVersion = SCHEMA_VERSION,
            currentFile = CURRENT_LOG_FILE,
            eventCount = eventCount,
            lastUpdatedAt = timestamp,
            byteSizeApprox = currentFile.length(),
        )
        indexFile(chatId).writeText(json.encodeToString(index), StandardCharsets.UTF_8)
    }

    private fun deleteChatDirectory(chatId: Int) {
        chatDirectory(chatId).deleteRecursively()
    }

    private fun rollIfNeeded(chatId: Int) {
        // Placeholder for V2 rolling implementation.
        currentLogFile(chatId)
    }

    private fun pruneChatLogsIfNeeded() {
        // Placeholder for V2 global quota pruning.
    }

    private fun String.toEndpointPathSummary(): String {
        return runCatching {
            java.net.URI(this).path?.takeIf { it.isNotBlank() } ?: this
        }.getOrDefault(this)
    }

    private fun JsonObjectBuilder.putIfNotNull(key: String, value: String?) {
        if (value != null) put(key, value)
    }

    private fun JsonObjectBuilder.putIfNotNull(key: String, value: Int?) {
        if (value != null) put(key, value)
    }

    private fun JsonObjectBuilder.putIfNotNull(key: String, value: Long?) {
        if (value != null) put(key, value)
    }

    private companion object {
        const val TAG = "ChatDiagnosticLogger"
        const val SCHEMA_VERSION = 1
        const val CURRENT_LOG_FILE = "current.ndjson"
        const val INDEX_FILE = "index.json"
    }
}
