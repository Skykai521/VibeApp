package com.vibe.app.data.dto.anthropic.common

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Represents an assistant-role content block where the model invokes a tool.
 * Placed in the `content` array of an assistant `InputMessage`.
 *
 * Example:
 * ```json
 * { "type": "tool_use", "id": "toolu_xxx", "name": "read_project_file", "input": { "path": "..." } }
 * ```
 */
@Serializable
@SerialName("tool_use")
data class ToolUseContent(

    @SerialName("id")
    val id: String,

    @SerialName("name")
    val name: String,

    @SerialName("input")
    val input: JsonElement,
) : MessageContent()
