package com.vibe.app.feature.agent.tool

import com.vibe.app.feature.agent.AgentToolCall
import com.vibe.app.feature.agent.AgentToolResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

