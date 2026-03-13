package com.vibe.app.data.dto.anthropic.request

import com.vibe.app.data.dto.anthropic.common.MessageContent
import com.vibe.app.data.dto.anthropic.common.MessageRole
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class InputMessage(
    @SerialName("role")
    val role: MessageRole,

    @SerialName("content")
    val content: List<MessageContent>
)
