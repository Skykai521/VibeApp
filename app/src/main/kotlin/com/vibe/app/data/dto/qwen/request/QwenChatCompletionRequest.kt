package com.vibe.app.data.dto.qwen.request

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class QwenChatCompletionRequest(
    @SerialName("model")
    val model: String,

    @SerialName("messages")
    val messages: List<QwenChatMessage>,

    @SerialName("tools")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val tools: List<QwenTool>? = null,

    @SerialName("tool_choice")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val toolChoice: String? = null,

    @SerialName("stream")
    val stream: Boolean = false,

    @SerialName("thinking")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val thinking: QwenThinkingParam? = null,
)

@Serializable
data class QwenThinkingParam(
    @SerialName("type")
    val type: String,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class QwenTool(
    @SerialName("type")
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val type: String = "function",

    @SerialName("function")
    val function: QwenFunctionDefinition,
)

@Serializable
data class QwenFunctionDefinition(
    @SerialName("name")
    val name: String,

    @SerialName("description")
    val description: String,

    @SerialName("parameters")
    val parameters: JsonElement,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class QwenChatMessage(
    @SerialName("role")
    val role: String,

    @SerialName("content")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val content: JsonElement? = null,

    @SerialName("reasoning_content")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val reasoningContent: String? = null,

    @SerialName("tool_calls")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val toolCalls: List<QwenToolCall>? = null,

    @SerialName("tool_call_id")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val toolCallId: String? = null,
)

fun qwenTextContent(text: String?): JsonElement? = text?.let(::JsonPrimitive)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class QwenToolCall(
    @SerialName("id")
    val id: String,

    @SerialName("type")
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val type: String = "function",

    @SerialName("function")
    val function: QwenFunctionCall,
)

@Serializable
data class QwenFunctionCall(
    @SerialName("name")
    val name: String,

    @SerialName("arguments")
    val arguments: String,
)
