package com.vibe.app.data.dto.anthropic.request

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Tool definition sent in the request's `tools` array.
 * Maps to Anthropic's tool schema format:
 *   { "name": "...", "description": "...", "input_schema": { "type": "object", ... } }
 */
@Serializable
data class AnthropicTool(
    @SerialName("name")
    val name: String,

    @SerialName("description")
    val description: String,

    @SerialName("input_schema")
    val inputSchema: JsonElement,
)

/**
 * Controls how the model selects tools.
 * - `auto` (default) — model decides
 * - `any`            — must use at least one tool
 * - `none`           — must not use tools
 * - `tool`           — force a specific tool (requires [name])
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AnthropicToolChoice(
    @SerialName("type")
    val type: String,

    @SerialName("name")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val name: String? = null,
)
