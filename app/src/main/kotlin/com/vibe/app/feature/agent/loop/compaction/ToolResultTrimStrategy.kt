package com.vibe.app.feature.agent.loop.compaction

import com.vibe.app.feature.agent.AgentConversationItem
import com.vibe.app.feature.agent.AgentMessageRole
import com.vibe.app.feature.agent.loop.ConversationContextManager
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Strategy 1: Trim large tool result payloads in older turns.
 *
 * Targets specific tool types known to produce large outputs:
 * - read_project_file: file contents → "[File: path, N lines — trimmed]"
 * - run_build_pipeline: build logs → "[Build: STATUS — errors, logs trimmed]"
 * - fix_crash_guide: source files → trimmed
 * - launch_app / inspect_ui / interact_ui: view trees → trimmed
 * - read_runtime_log: log contents → trimmed
 *
 * Only applies to turns outside the recent window.
 * Also strips reasoning content from older assistant messages.
 */
class ToolResultTrimStrategy : CompactionStrategy {
    override val type = CompactionStrategyType.TOOL_RESULT_TRIM

    override suspend fun compact(
        items: List<AgentConversationItem>,
        recentTurnCount: Int,
        tokenBudget: Int,
    ): CompactionResult? {
        val turns = splitIntoTurns(items)
        if (turns.size <= recentTurnCount) return null

        val olderTurns = turns.dropLast(recentTurnCount)
        val recentTurns = turns.takeLast(recentTurnCount)

        var trimmed = false
        val compactedOlder = olderTurns.map { turn ->
            turn.map { item ->
                if (item.role == AgentMessageRole.TOOL && item.payload != null) {
                    val trimmedPayload = trimToolPayload(item.toolName, item.payload)
                    if (trimmedPayload !== item.payload) {
                        trimmed = true
                        item.copy(payload = trimmedPayload)
                    } else {
                        item
                    }
                } else if (item.role == AgentMessageRole.ASSISTANT && item.reasoningContent != null) {
                    trimmed = true
                    item.copy(reasoningContent = null)
                } else {
                    item
                }
            }
        }

        if (!trimmed) return null

        val result = compactedOlder.flatten() + recentTurns.flatten()
        val tokens = estimateTokens(result)
        return CompactionResult(
            items = result,
            estimatedTokens = tokens,
            strategyUsed = type,
            turnsCompacted = olderTurns.size,
        )
    }

    private fun trimToolPayload(toolName: String?, payload: JsonElement): JsonElement {
        if (payload !is JsonObject) return payload
        return when (toolName) {
            "read_project_file" -> trimReadFilePayload(payload)
            "run_build_pipeline" -> trimBuildPayload(payload)
            "fix_crash_guide" -> trimCrashGuidePayload(payload)
            "launch_app" -> buildJsonObject {
                put("status", payload["status"] ?: JsonPrimitive("trimmed"))
                put("view_tree", JsonPrimitive("[View tree trimmed — use inspect_ui for current state]"))
            }
            "inspect_ui" -> buildJsonObject {
                put("trimmed", JsonPrimitive("[View tree trimmed — use inspect_ui to get current state]"))
            }
            "interact_ui" -> trimInteractUiPayload(payload)
            "read_runtime_log" -> buildJsonObject {
                put("note", JsonPrimitive("[Runtime logs trimmed — use read_runtime_log for latest]"))
            }
            else -> payload
        }
    }

    private fun trimReadFilePayload(payload: JsonObject): JsonElement {
        val path = payload["path"]?.jsonPrimitive?.content
        val content = payload["content"]?.jsonPrimitive?.content
        if (path != null && content != null) {
            val lines = content.count { it == '\n' } + 1
            return buildJsonObject {
                put("path", JsonPrimitive(path))
                put("content", JsonPrimitive("[File: $path, $lines lines — content trimmed from earlier turn]"))
            }
        }
        val files = payload["files"]
        if (files is JsonArray) {
            return buildJsonObject {
                put("files", buildJsonArray {
                    files.forEach { fileEl ->
                        val fileObj = fileEl.jsonObject
                        val filePath = fileObj["path"]?.jsonPrimitive?.content ?: "unknown"
                        val fileContent = fileObj["content"]?.jsonPrimitive?.content
                        add(buildJsonObject {
                            put("path", JsonPrimitive(filePath))
                            if (fileContent != null) {
                                val lineCount = fileContent.count { it == '\n' } + 1
                                put("content", JsonPrimitive("[File: $filePath, $lineCount lines — trimmed]"))
                            } else {
                                fileObj["error"]?.let { put("error", it) }
                            }
                        })
                    }
                })
            }
        }
        return payload
    }

    private fun trimBuildPayload(payload: JsonObject): JsonElement {
        val status = payload["status"]?.jsonPrimitive?.content ?: "UNKNOWN"
        val errorMessage = payload["errorMessage"]?.jsonPrimitive?.content
        return buildJsonObject {
            put("status", JsonPrimitive(status))
            if (errorMessage != null) {
                put("errorMessage", JsonPrimitive(errorMessage.take(500)))
            }
            put("note", JsonPrimitive("[Detailed build logs trimmed from earlier turn]"))
        }
    }

    private fun trimCrashGuidePayload(payload: JsonObject): JsonElement {
        val crashLog = payload["crash_log"]?.jsonPrimitive?.content
        return buildJsonObject {
            if (crashLog != null) {
                put("crash_log", JsonPrimitive(crashLog.take(500)))
            }
            put("note", JsonPrimitive("[Source files trimmed from earlier turn]"))
        }
    }

    private fun trimInteractUiPayload(payload: JsonObject): JsonElement {
        val result = payload["result"]?.jsonPrimitive?.content
        return buildJsonObject {
            if (result != null) put("result", JsonPrimitive(result))
            put("view_tree", JsonPrimitive("[View tree trimmed — use inspect_ui for current state]"))
        }
    }

    companion object {
        fun splitIntoTurns(items: List<AgentConversationItem>): List<List<AgentConversationItem>> {
            val turns = mutableListOf<MutableList<AgentConversationItem>>()
            for (item in items) {
                if (item.role == AgentMessageRole.USER) {
                    turns.add(mutableListOf(item))
                } else if (turns.isNotEmpty()) {
                    turns.last().add(item)
                }
            }
            return turns
        }

        fun estimateTokens(items: List<AgentConversationItem>): Int {
            return ConversationContextManager.estimateTokens(items)
        }
    }
}
