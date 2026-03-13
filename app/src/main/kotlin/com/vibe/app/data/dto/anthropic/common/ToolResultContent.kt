package com.vibe.app.data.dto.anthropic.common

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents a user-role content block that carries the result of a tool invocation back to
 * the model.  Placed in the `content` array of a user `InputMessage`.
 *
 * Example:
 * ```json
 * { "type": "tool_result", "tool_use_id": "toolu_xxx", "content": "file content here" }
 * ```
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("tool_result")
data class ToolResultContent(

    @SerialName("tool_use_id")
    val toolUseId: String,

    @SerialName("content")
    val content: String,

    @SerialName("is_error")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val isError: Boolean? = null,
) : MessageContent()
