package com.vibe.app.data.dto.anthropic.response

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ContentBlock(

    @SerialName("type")
    val type: ContentBlockType,

    // text / thinking / thinking_delta
    @SerialName("text")
    val text: String? = null,

    @SerialName("thinking")
    val thinking: String? = null,

    // tool_use (content_block_start)
    @SerialName("id")
    val id: String? = null,

    @SerialName("name")
    val name: String? = null,

    // input_json_delta (content_block_delta for tool_use blocks)
    @SerialName("partial_json")
    val partialJson: String? = null,
)
