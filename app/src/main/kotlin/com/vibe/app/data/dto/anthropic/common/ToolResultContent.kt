package com.vibe.app.data.dto.anthropic.common

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents a user-role content block that carries the result of a tool invocation back to
 * the model.  Placed in the `content` array of a user `InputMessage`.
 *
 * The Anthropic API allows `content` to be either a string or an array of content blocks.
 * We always send an array of [TextContent] (and optionally [ImageContent]) so a tool that
 * produces a screenshot can inline the image alongside its textual payload.
 *
 * Example:
 * ```json
 * { "type": "tool_result", "tool_use_id": "toolu_xxx",
 *   "content": [ { "type": "text", "text": "file content here" } ] }
 * ```
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("tool_result")
data class ToolResultContent(

    @SerialName("tool_use_id")
    val toolUseId: String,

    @SerialName("content")
    val content: List<MessageContent>,

    @SerialName("is_error")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val isError: Boolean? = null,
) : MessageContent()
