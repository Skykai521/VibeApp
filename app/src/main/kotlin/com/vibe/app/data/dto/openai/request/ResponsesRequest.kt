package com.vibe.app.data.dto.openai.request

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Request body for OpenAI Responses API.
 * Used for reasoning models (o1, o3, etc.) to get reasoning content in streaming responses.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ResponsesRequest(
    @SerialName("model")
    val model: String,

    @SerialName("input")
    val input: List<ResponseInputItem>,

    @SerialName("previous_response_id")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val previousResponseId: String? = null,

    @SerialName("stream")
    val stream: Boolean = true,

    @SerialName("instructions")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val instructions: String? = null,

    @SerialName("max_output_tokens")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val maxOutputTokens: Int? = null,

    @SerialName("temperature")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val temperature: Float? = null,

    @SerialName("top_p")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val topP: Float? = null,

    @SerialName("reasoning")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val reasoning: ReasoningConfig? = null,

    @SerialName("tools")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val tools: List<ResponseTool>? = null,

    @SerialName("tool_choice")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val toolChoice: String? = null,

    @SerialName("response_format")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val responseFormat: ResponseFormat? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ResponseFormat(
    @SerialName("type")
    val type: String,

    @SerialName("json_schema")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val jsonSchema: JsonElement? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ReasoningConfig(
    @SerialName("effort")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val effort: String? = null,

    @SerialName("summary")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val summary: String? = null
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ResponseTool(
    @SerialName("type")
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val type: String = "function",

    @SerialName("name")
    val name: String,

    @SerialName("description")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val description: String? = null,

    @SerialName("parameters")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val parameters: JsonElement? = null,

    @SerialName("strict")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val strict: Boolean? = null,
)

@Serializable(with = ResponseInputItemSerializer::class)
sealed class ResponseInputItem {
    data class Message(
        val role: String,
        val content: ResponseInputContent,
    ) : ResponseInputItem()

    data class FunctionCallOutput(
        val callId: String,
        val output: String,
    ) : ResponseInputItem()

    companion object {
        fun message(
            role: String,
            content: ResponseInputContent,
        ): ResponseInputItem = Message(role = role, content = content)

        fun functionCallOutput(
            callId: String,
            output: String,
        ): ResponseInputItem = FunctionCallOutput(callId = callId, output = output)
    }
}

object ResponseInputItemSerializer : KSerializer<ResponseInputItem> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ResponseInputItem")

    override fun serialize(
        encoder: Encoder,
        value: ResponseInputItem,
    ) {
        val jsonEncoder = encoder as JsonEncoder
        when (value) {
            is ResponseInputItem.Message -> {
                jsonEncoder.encodeJsonElement(
                    buildJsonObject {
                        put("role", JsonPrimitive(value.role))
                        put(
                            "content",
                            jsonEncoder.json.encodeToJsonElement(
                                ResponseInputContentSerializer,
                                value.content,
                            ),
                        )
                    },
                )
            }

            is ResponseInputItem.FunctionCallOutput -> {
                jsonEncoder.encodeJsonElement(
                    buildJsonObject {
                        put("type", JsonPrimitive("function_call_output"))
                        put("call_id", JsonPrimitive(value.callId))
                        put("output", JsonPrimitive(value.output))
                    },
                )
            }
        }
    }

    override fun deserialize(decoder: Decoder): ResponseInputItem {
        val jsonDecoder = decoder as JsonDecoder
        val element = jsonDecoder.decodeJsonElement()
        require(element is JsonObject) { "Unexpected JSON element type for ResponseInputItem" }

        return when (element["type"]?.jsonPrimitive?.content) {
            "function_call_output" -> ResponseInputItem.FunctionCallOutput(
                callId = element.getValue("call_id").jsonPrimitive.content,
                output = element.getValue("output").jsonPrimitive.content,
            )

            else -> ResponseInputItem.Message(
                role = element.getValue("role").jsonPrimitive.content,
                content = jsonDecoder.json.decodeFromJsonElement(
                    ResponseInputContentSerializer,
                    element.getValue("content"),
                ),
            )
        }
    }
}

/**
 * Content can be either a simple string or a list of content parts.
 * Serializes as JSON string for Text, JSON array for Parts.
 */
@Serializable(with = ResponseInputContentSerializer::class)
sealed class ResponseInputContent {
    data class Text(val text: String) : ResponseInputContent()
    data class Parts(val parts: List<ResponseContentPart>) : ResponseInputContent()

    companion object {
        fun text(text: String): ResponseInputContent = Text(text)
        fun parts(parts: List<ResponseContentPart>): ResponseInputContent = Parts(parts)
    }
}

/**
 * Custom serializer that outputs string for Text and array for Parts.
 */
object ResponseInputContentSerializer : KSerializer<ResponseInputContent> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ResponseInputContent")

    override fun serialize(encoder: Encoder, value: ResponseInputContent) {
        val jsonEncoder = encoder as JsonEncoder
        when (value) {
            is ResponseInputContent.Text -> jsonEncoder.encodeJsonElement(JsonPrimitive(value.text))

            is ResponseInputContent.Parts -> {
                val jsonArray = jsonEncoder.json.encodeToJsonElement(
                    ListSerializer(ResponseContentPart.serializer()),
                    value.parts
                )
                jsonEncoder.encodeJsonElement(jsonArray)
            }
        }
    }

    override fun deserialize(decoder: Decoder): ResponseInputContent {
        val jsonDecoder = decoder as JsonDecoder
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> ResponseInputContent.Text(element.jsonPrimitive.content)

            is JsonArray -> {
                val parts = jsonDecoder.json.decodeFromJsonElement(
                    ListSerializer(ResponseContentPart.serializer()),
                    element
                )
                ResponseInputContent.Parts(parts)
            }

            else -> throw IllegalArgumentException("Unexpected JSON element type")
        }
    }
}

/**
 * Content part for multi-modal input (text or image).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ResponseContentPart(
    @SerialName("type")
    val type: String,

    @SerialName("text")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val text: String? = null,

    @SerialName("image_url")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val imageUrl: String? = null,

    @SerialName("detail")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val detail: String? = null
) {
    companion object {
        fun text(text: String) = ResponseContentPart(type = "input_text", text = text)
        fun image(url: String, detail: String = "auto") = ResponseContentPart(
            type = "input_image",
            imageUrl = url,
            detail = detail
        )
    }
}
