package com.vibe.app.feature.agent.tool

import com.vibe.app.feature.agent.AgentToolCall
import com.vibe.app.feature.agent.AgentToolResult
import com.vibe.app.feature.build.BuildFailureAnalysis
import com.vibe.build.engine.model.BuildLogLevel
import com.vibe.build.engine.model.BuildResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// ── Argument extraction ─────────────────────────────────────────────

fun JsonElement.requireString(key: String): String {
    return jsonObject[key]?.jsonPrimitive?.content
        ?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("Missing required string field: $key")
}

fun JsonElement.optionalString(key: String): String? {
    return jsonObject[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
}

fun JsonElement.optionalInt(key: String, default: Int): Int {
    return jsonObject[key]?.jsonPrimitive?.content?.toIntOrNull() ?: default
}

fun JsonElement.optionalBoolean(key: String, default: Boolean): Boolean {
    return jsonObject[key]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: default
}

// ── Common result builders ──────────────────────────────────────────

fun AgentToolCall.okResult(): AgentToolResult = AgentToolResult(
    toolCallId = id,
    toolName = name,
    output = buildJsonObject { put("ok", JsonPrimitive(true)) },
)

fun AgentToolCall.errorResult(message: String): AgentToolResult = AgentToolResult(
    toolCallId = id,
    toolName = name,
    output = buildJsonObject { put("error", JsonPrimitive(message)) },
    isError = true,
)

fun AgentToolCall.result(output: JsonElement, isError: Boolean = false): AgentToolResult =
    AgentToolResult(
        toolCallId = id,
        toolName = name,
        output = output,
        isError = isError,
    )

// ── Schema helpers ──────────────────────────────────────────────────

fun stringProp(description: String? = null): JsonObject = buildJsonObject {
    put("type", JsonPrimitive("string"))
    description?.let { put("description", JsonPrimitive(it)) }
}

fun intProp(description: String? = null): JsonObject = buildJsonObject {
    put("type", JsonPrimitive("integer"))
    description?.let { put("description", JsonPrimitive(it)) }
}

fun booleanProp(description: String? = null): JsonObject = buildJsonObject {
    put("type", JsonPrimitive("boolean"))
    description?.let { put("description", JsonPrimitive(it)) }
}

fun requiredFields(vararg fields: String): JsonArray =
    JsonArray(fields.map { JsonPrimitive(it) })

fun arrayProp(
    description: String? = null,
    itemType: String = "string",
): JsonObject = buildJsonObject {
    put("type", JsonPrimitive("array"))
    description?.let { put("description", JsonPrimitive(it)) }
    put("items", buildJsonObject { put("type", JsonPrimitive(itemType)) })
}

fun objectArrayProp(
    description: String? = null,
    properties: JsonObject,
    required: JsonArray? = null,
): JsonObject = buildJsonObject {
    put("type", JsonPrimitive("array"))
    description?.let { put("description", JsonPrimitive(it)) }
    put("items", buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", properties)
        required?.let { put("required", it) }
    })
}

// ── Build result conversion ─────────────────────────────────────────

fun BuildResult.toFilteredJson(analysis: BuildFailureAnalysis? = null): JsonObject {
    val isSuccess = errorMessage == null
    return buildJsonObject {
        put("status", JsonPrimitive(status.name))
        errorMessage?.let { put("errorMessage", JsonPrimitive(it)) }
        analysis?.let { put("analysis", it.toJson()) }
        val filteredLogs = logs.filter {
            it.level == BuildLogLevel.WARNING || it.level == BuildLogLevel.ERROR
        }
        if (!isSuccess && filteredLogs.isNotEmpty()) {
            put("totalLogCount", JsonPrimitive(filteredLogs.size))
            put(
                "logs",
                buildJsonArray {
                    filteredLogs.take(MAX_TOOL_LOGS).forEach { log ->
                        add(
                            buildJsonObject {
                                put("stage", JsonPrimitive(log.stage.name))
                                put("level", JsonPrimitive(log.level.name))
                                put("message", JsonPrimitive(log.message))
                                log.sourcePath?.let { put("sourcePath", JsonPrimitive(it)) }
                                log.line?.let { put("line", JsonPrimitive(it)) }
                            },
                        )
                    }
                },
            )
        }
    }
}

private const val MAX_TOOL_LOGS = 12
