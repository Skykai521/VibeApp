package com.vibe.app.feature.agent.loop

import com.vibe.app.data.database.entity.PlatformV2
import com.vibe.app.data.dto.openai.request.ResponseFormat
import com.vibe.app.data.model.ClientType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal fun PlatformV2.qwenResponseFormatOrNull(): ResponseFormat? {
    return if (compatibleType == ClientType.QWEN) {
        ResponseFormat(type = "json_object")
    } else {
        null
    }
}

internal fun JsonElement.isEmptyJsonObject(): Boolean {
    return this is JsonObject && isEmpty()
}

internal fun JsonElement.toQwenCompatibleJsonSchemaOrNull(): JsonElement? {
    if (isEmptyJsonObject()) {
        return null
    }

    return when (this) {
        is JsonObject -> sanitizeJsonSchemaObject().takeUnless { it.isEmpty() }
        else -> this
    }
}

private fun JsonObject.sanitizeJsonSchemaObject(): JsonObject {
    return buildJsonObject {
        this@sanitizeJsonSchemaObject["type"]?.let { put("type", it) }
        this@sanitizeJsonSchemaObject["description"]?.let { put("description", it) }
        this@sanitizeJsonSchemaObject["enum"]?.let { put("enum", it) }

        (this@sanitizeJsonSchemaObject["required"] as? JsonArray)
            ?.takeIf { it.isNotEmpty() }
            ?.let { put("required", it) }

        (this@sanitizeJsonSchemaObject["properties"] as? JsonObject)
            ?.let { properties ->
                val sanitizedProperties = buildJsonObject {
                    properties.forEach { (name, value) ->
                        val sanitizedValue = value.toQwenCompatibleJsonSchemaOrNull()
                        if (sanitizedValue != null) {
                            put(name, sanitizedValue)
                        }
                    }
                }
                if (sanitizedProperties.isNotEmpty()) {
                    putJsonObject("properties") {
                        sanitizedProperties.forEach { (name, value) -> put(name, value) }
                    }
                }
            }

        this@sanitizeJsonSchemaObject["items"]
            ?.toQwenCompatibleJsonSchemaOrNull()
            ?.let { sanitizedItems ->
                when (sanitizedItems) {
                    is JsonArray -> putJsonArray("items") {
                        sanitizedItems.forEach { add(it) }
                    }

                    else -> put("items", sanitizedItems)
                }
            }
    }
}
