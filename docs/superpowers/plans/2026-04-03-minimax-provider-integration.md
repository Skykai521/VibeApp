# MiniMax Provider Integration Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add MiniMax as a new AI platform provider with a dedicated `MiniMaxChatCompletionsAgentGateway`, following the same pattern as Kimi/Qwen.

**Architecture:** MiniMax exposes an OpenAI-compatible chat completions API at `https://api.minimaxi.com/v1` with model `MiniMax-M2.7`. It supports function calling and reasoning (`reasoning_content` field, same as Qwen/Kimi). We add a `MINIMAX` entry to `ClientType`, wire it through the existing `QwenChatCompletionRequest` DTOs (the request/response format is identical), create a dedicated `MiniMaxChatCompletionsAgentGateway` (modeled on `KimiChatCompletionsAgentGateway`), and register it in the router.

**Tech Stack:** Kotlin, kotlinx.serialization, Ktor (via existing `OpenAIAPI`), Hilt DI, Jetpack Compose

---

## File Map

| File | Action | Responsibility |
|------|--------|---------------|
| `app/.../data/model/ClientType.kt` | Modify | Add `MINIMAX` enum value |
| `app/.../data/ModelConstants.kt` | Modify | Add `MINIMAX_API_URL` constant |
| `app/.../feature/diagnostic/DiagnosticModels.kt` | Modify | Add `MINIMAX -> "minimax"` mapping |
| `app/.../feature/agent/loop/MiniMaxChatCompletionsAgentGateway.kt` | Create | Dedicated agent gateway for MiniMax |
| `app/.../feature/agent/loop/ProviderAgentGatewayRouter.kt` | Modify | Route `MINIMAX` to new gateway |
| `app/.../presentation/ui/setup/SetupViewModelV2.kt` | Modify | Add MiniMax defaults (name, URL, model) |
| `app/.../presentation/ui/setup/SetupPlatformTypeScreen.kt` | Modify | Add MiniMax to platform selection list |
| `app/src/main/res/values/strings.xml` | Modify | Add MiniMax string resources |
| `app/src/main/res/values-zh-rCN/strings.xml` | Modify | Add MiniMax Chinese string resources |

---

### Task 1: Add `MINIMAX` to `ClientType` and Constants

**Files:**
- Modify: `app/src/main/kotlin/com/vibe/app/data/model/ClientType.kt`
- Modify: `app/src/main/kotlin/com/vibe/app/data/ModelConstants.kt`
- Modify: `app/src/main/kotlin/com/vibe/app/feature/diagnostic/DiagnosticModels.kt`

- [ ] **Step 1: Add MINIMAX to ClientType enum**

In `ClientType.kt`, add `MINIMAX` after `KIMI`:

```kotlin
enum class ClientType {
    OPENAI,
    ANTHROPIC,
    GOOGLE,
    GROQ,
    OPENROUTER,
    OLLAMA,
    CUSTOM,
    QWEN,
    KIMI,
    MINIMAX,
}
```

- [ ] **Step 2: Add MINIMAX_API_URL to ModelConstants**

In `ModelConstants.kt`, add after `KIMI_API_URL`:

```kotlin
const val MINIMAX_API_URL = "https://api.minimaxi.com/v1/"
```

- [ ] **Step 3: Add diagnostic mapping**

In `DiagnosticModels.kt`, add to the `toDiagnosticProviderType()` when-expression before the closing brace:

```kotlin
ClientType.MINIMAX -> "minimax"
```

- [ ] **Step 4: Build to verify no compile errors**

Run: `./gradlew assembleDebug`
Expected: The `when` expressions in `SetupViewModelV2.kt` that use `else ->` will still compile. The only `when` without `else` is `toDiagnosticProviderType()` which we already updated.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/data/model/ClientType.kt \
       app/src/main/kotlin/com/vibe/app/data/ModelConstants.kt \
       app/src/main/kotlin/com/vibe/app/feature/diagnostic/DiagnosticModels.kt
git commit -m "feat: add MINIMAX to ClientType enum and constants"
```

---

### Task 2: Create `MiniMaxChatCompletionsAgentGateway`

**Files:**
- Create: `app/src/main/kotlin/com/vibe/app/feature/agent/loop/MiniMaxChatCompletionsAgentGateway.kt`

This gateway is modeled on `KimiChatCompletionsAgentGateway`. Key differences from Kimi:
- No image support (MiniMax M2.7 docs don't mention vision)
- Uses `tool_choice: "auto"` when tools are present (safe default for reasoning models)
- URL normalization: just trim trailing slash (MiniMax base URL already ends with `/v1/`)

- [ ] **Step 1: Create the gateway file**

Create `app/src/main/kotlin/com/vibe/app/feature/agent/loop/MiniMaxChatCompletionsAgentGateway.kt`:

```kotlin
package com.vibe.app.feature.agent.loop

import com.vibe.app.data.dto.qwen.request.QwenChatCompletionRequest
import com.vibe.app.data.dto.qwen.request.QwenChatMessage
import com.vibe.app.data.dto.qwen.request.QwenFunctionCall
import com.vibe.app.data.dto.qwen.request.QwenFunctionDefinition
import com.vibe.app.data.dto.qwen.request.QwenTool
import com.vibe.app.data.dto.qwen.request.QwenToolCall
import com.vibe.app.data.dto.qwen.request.qwenTextContent
import com.vibe.app.data.network.OpenAIAPI
import com.vibe.app.feature.agent.AgentMessageRole
import com.vibe.app.feature.agent.AgentModelEvent
import com.vibe.app.feature.agent.AgentModelGateway
import com.vibe.app.feature.agent.AgentModelRequest
import com.vibe.app.feature.agent.AgentToolCall
import com.vibe.app.feature.agent.AgentToolChoiceMode
import com.vibe.app.feature.diagnostic.ChatDiagnosticLogger
import com.vibe.app.feature.diagnostic.ModelExecutionTrace
import com.vibe.app.feature.diagnostic.ModelRequestDiagnosticContext
import com.vibe.app.feature.diagnostic.toDiagnosticProviderType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Agent gateway for MiniMax.
 *
 * MiniMax is OpenAI-compatible with reasoning support (reasoning_content field).
 * Uses tool_choice "auto" to avoid conflicts with reasoning-enabled models.
 */
@Singleton
class MiniMaxChatCompletionsAgentGateway @Inject constructor(
    private val openAIAPI: OpenAIAPI,
    private val diagnosticLogger: ChatDiagnosticLogger,
) : AgentModelGateway {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    override suspend fun streamTurn(request: AgentModelRequest): Flow<AgentModelEvent> = flow {
        openAIAPI.setToken(request.platform.token)
        openAIAPI.setAPIUrl(request.platform.apiUrl.toMiniMaxBaseUrl())
        val trace = ModelExecutionTrace()

        val messages = buildMessages(request)
        trace.markRequestPrepared()
        val requestContext = request.diagnosticContext?.copy(platformUid = request.platform.uid)?.let { diagnosticContext ->
            ModelRequestDiagnosticContext(
                diagnosticContext = diagnosticContext,
                providerType = request.platform.compatibleType.toDiagnosticProviderType(),
                apiFamily = "chat_completions",
                model = request.platform.model,
                stream = true,
                reasoningEnabled = request.platform.reasoning,
                messageCount = messages.size,
                toolCount = request.tools.size.takeIf { it > 0 },
                toolChoiceMode = "auto".takeIf { request.tools.isNotEmpty() },
                systemPromptPresent = true,
                systemPromptChars = request.instructions?.length?.takeIf { it > 0 },
            )
        }

        data class ToolCallAccumulator(
            var id: String = "",
            var name: String = "",
            val arguments: StringBuilder = StringBuilder(),
        )
        val toolCallAccumulators = mutableMapOf<Int, ToolCallAccumulator>()
        var finishReason: String? = null
        val reasoningBuilder = StringBuilder()
        var streamError: String? = null

        openAIAPI.streamQwenChatCompletion(
            QwenChatCompletionRequest(
                model = request.platform.model,
                messages = messages,
                tools = request.tools.takeIf { it.isNotEmpty() }?.map { tool ->
                    QwenTool(
                        function = QwenFunctionDefinition(
                            name = tool.name,
                            description = tool.description,
                            parameters = tool.inputSchema.toMiniMaxToolSchema(),
                        ),
                    )
                },
                toolChoice = if (request.tools.isNotEmpty()) "auto" else null,
                stream = true,
            ),
            diagnosticContext = requestContext,
            trace = trace,
        ).collect { chunk ->
            if (chunk.error != null) {
                streamError = chunk.error.message
                trace.markFailed(chunk.error.type ?: "provider_error", chunk.error.message)
                return@collect
            }

            val choice = chunk.choices?.firstOrNull() ?: return@collect
            finishReason = choice.finishReason ?: finishReason

            choice.delta.content?.takeIf { it.isNotEmpty() }?.let { delta ->
                trace.markOutput(delta)
                emit(AgentModelEvent.OutputDelta(delta))
            }

            choice.delta.reasoningContent?.takeIf { it.isNotEmpty() }?.let { delta ->
                reasoningBuilder.append(delta)
                emit(AgentModelEvent.ThinkingDelta(delta))
            }

            choice.delta.toolCalls?.forEach { deltaToolCall ->
                val acc = toolCallAccumulators.getOrPut(deltaToolCall.index) { ToolCallAccumulator() }
                deltaToolCall.id?.let { acc.id = it }
                deltaToolCall.function?.name?.let { acc.name = it }
                deltaToolCall.function?.arguments?.let { acc.arguments.append(it) }
            }
        }

        streamError?.let { error ->
            if (requestContext != null) {
                diagnosticLogger.logModelResponse(requestContext, trace, success = false)
                diagnosticLogger.logLatencyBreakdown(requestContext, trace)
            }
            emit(AgentModelEvent.Failed(error))
            return@flow
        }

        toolCallAccumulators.entries.sortedBy { it.key }.forEach { (_, acc) ->
            trace.markToolCall()
            val arguments = runCatching {
                json.parseToJsonElement(acc.arguments.toString())
            }.getOrElse {
                buildJsonObject { put("raw", JsonPrimitive(acc.arguments.toString())) }
            }
            emit(
                AgentModelEvent.ToolCallReady(
                    AgentToolCall(id = acc.id, name = acc.name, arguments = arguments),
                ),
            )
        }

        val reasoningContent = reasoningBuilder.toString().takeIf { it.isNotBlank() }
        reasoningContent?.let { trace.markThinking(it) }
        trace.finishReason = finishReason
        trace.markCompleted(finishReason)
        if (requestContext != null) {
            diagnosticLogger.logModelResponse(requestContext, trace, success = true)
            diagnosticLogger.logLatencyBreakdown(requestContext, trace)
        }
        emit(AgentModelEvent.Completed(reasoningContent = reasoningContent))
    }

    private fun buildMessages(request: AgentModelRequest): List<QwenChatMessage> {
        val messages = mutableListOf<QwenChatMessage>()
        val toolRequired = request.policy.toolChoiceMode == AgentToolChoiceMode.REQUIRED

        val systemContent = buildString {
            request.instructions?.takeIf { it.isNotBlank() }?.let { append(it) }
            if (toolRequired && request.tools.isNotEmpty()) {
                append("\n\n")
                append(TOOL_REQUIRED_INSTRUCTION)
            }
        }.trim()

        if (systemContent.isNotBlank()) {
            messages += QwenChatMessage(
                role = "system",
                content = qwenTextContent(systemContent),
            )
        }

        request.fullConversation.forEach { item ->
            when (item.role) {
                AgentMessageRole.USER -> messages += QwenChatMessage(
                    role = "user",
                    content = qwenTextContent(item.text),
                )

                AgentMessageRole.ASSISTANT -> messages += QwenChatMessage(
                    role = "assistant",
                    content = qwenTextContent(item.text),
                    reasoningContent = item.reasoningContent,
                    toolCalls = item.toolCalls
                        ?.map { toolCall ->
                            QwenToolCall(
                                id = toolCall.id,
                                function = QwenFunctionCall(
                                    name = toolCall.name,
                                    arguments = toolCall.arguments.toString(),
                                ),
                            )
                        }
                        ?.takeIf { it.isNotEmpty() },
                )

                AgentMessageRole.TOOL -> messages += QwenChatMessage(
                    role = "tool",
                    content = qwenTextContent(item.payload?.toString() ?: item.text.orEmpty()),
                    toolCallId = item.toolCallId,
                )

                AgentMessageRole.SYSTEM -> Unit
            }
        }

        return messages
    }

    companion object {
        private const val TOOL_REQUIRED_INSTRUCTION =
            """## MANDATORY TOOL USE
You MUST call at least one tool in your response. Do NOT reply with only text.
Analyze the user's request and use the appropriate tools to fulfill it.
Every response MUST include one or more tool calls — a text-only answer is NOT acceptable."""
    }
}

private fun String.toMiniMaxBaseUrl(): String {
    return trimEnd('/')
}

private fun kotlinx.serialization.json.JsonElement.toMiniMaxToolSchema(): kotlinx.serialization.json.JsonElement {
    val schemaObject = if (this is kotlinx.serialization.json.JsonObject) this else buildJsonObject {}
    val properties = schemaObject["properties"]?.jsonObject ?: buildJsonObject {}
    val required = schemaObject["required"]

    return buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", properties)
        if (required != null) {
            put("required", required)
        }
        put("additionalProperties", JsonPrimitive(false))
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/feature/agent/loop/MiniMaxChatCompletionsAgentGateway.kt
git commit -m "feat: add MiniMaxChatCompletionsAgentGateway"
```

---

### Task 3: Register MiniMax Gateway in Router

**Files:**
- Modify: `app/src/main/kotlin/com/vibe/app/feature/agent/loop/ProviderAgentGatewayRouter.kt`

- [ ] **Step 1: Add MiniMax gateway to router**

Update `ProviderAgentGatewayRouter.kt`:

```kotlin
package com.vibe.app.feature.agent.loop

import com.vibe.app.data.model.ClientType
import com.vibe.app.feature.agent.AgentModelEvent
import com.vibe.app.feature.agent.AgentModelGateway
import com.vibe.app.feature.agent.AgentModelRequest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * Routes agent model requests to the appropriate [AgentModelGateway] implementation based on
 * the platform's [ClientType].
 *
 * Routing table:
 * - [ClientType.ANTHROPIC] → [AnthropicMessagesAgentGateway]
 * - [ClientType.QWEN]      → [QwenChatCompletionsAgentGateway]
 * - [ClientType.KIMI]      → [KimiChatCompletionsAgentGateway]
 * - [ClientType.MINIMAX]   → [MiniMaxChatCompletionsAgentGateway]
 * - everything else        → [OpenAiResponsesAgentGateway] (OpenAI-compatible APIs)
 *
 * New providers can be added here without touching the coordinator or DI graph.
 */
@Singleton
class ProviderAgentGatewayRouter @Inject constructor(
    private val openAiGateway: OpenAiResponsesAgentGateway,
    private val qwenGateway: QwenChatCompletionsAgentGateway,
    private val kimiGateway: KimiChatCompletionsAgentGateway,
    private val miniMaxGateway: MiniMaxChatCompletionsAgentGateway,
    private val anthropicGateway: AnthropicMessagesAgentGateway,
) : AgentModelGateway {

    override suspend fun streamTurn(request: AgentModelRequest): Flow<AgentModelEvent> {
        return when (request.platform.compatibleType) {
            ClientType.ANTHROPIC -> anthropicGateway.streamTurn(request)
            ClientType.QWEN -> qwenGateway.streamTurn(request)
            ClientType.KIMI -> kimiGateway.streamTurn(request)
            ClientType.MINIMAX -> miniMaxGateway.streamTurn(request)
            else -> openAiGateway.streamTurn(request)
        }
    }
}
```

- [ ] **Step 2: Build to verify DI wiring**

Run: `./gradlew assembleDebug`
Expected: Hilt can resolve `MiniMaxChatCompletionsAgentGateway` because it has `@Singleton` + `@Inject constructor`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/feature/agent/loop/ProviderAgentGatewayRouter.kt
git commit -m "feat: route MINIMAX to MiniMaxChatCompletionsAgentGateway"
```

---

### Task 4: Add MiniMax to Setup UI and Defaults

**Files:**
- Modify: `app/src/main/kotlin/com/vibe/app/presentation/ui/setup/SetupViewModelV2.kt`
- Modify: `app/src/main/kotlin/com/vibe/app/presentation/ui/setup/SetupPlatformTypeScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh-rCN/strings.xml`

- [ ] **Step 1: Add string resources (English)**

In `app/src/main/res/values/strings.xml`, add after the `kimi_description` line:

```xml
<string name="minimax" translatable="false">MiniMax</string>
<string name="minimax_description">Creator of MiniMax.</string>
```

- [ ] **Step 2: Add string resources (Chinese)**

In `app/src/main/res/values-zh-rCN/strings.xml`, add after the `kimi_description` line:

```xml
<string name="minimax_description">MiniMax 创造者</string>
```

- [ ] **Step 3: Add MiniMax to SetupViewModelV2 defaults**

In `SetupViewModelV2.kt`, update three functions:

In `getDefaultPlatformName()`, add before `ClientType.CUSTOM`:
```kotlin
ClientType.MINIMAX -> "MiniMax"
```

In `getDefaultApiUrl()`, add before `ClientType.CUSTOM`:
```kotlin
ClientType.MINIMAX -> ModelConstants.MINIMAX_API_URL
```

In `getDefaultModel()`, add before `else`:
```kotlin
ClientType.MINIMAX -> "MiniMax-M2.7"
```

- [ ] **Step 4: Add MiniMax to platform type selection screen**

In `SetupPlatformTypeScreen.kt`, add to `platformTypes` list after the Kimi entry:

```kotlin
PlatformTypeInfo(
    clientType = ClientType.MINIMAX,
    titleResId = R.string.minimax,
    descriptionResId = R.string.minimax_description
),
```

- [ ] **Step 5: Build to verify**

Run: `./gradlew assembleDebug`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/presentation/ui/setup/SetupViewModelV2.kt \
       app/src/main/kotlin/com/vibe/app/presentation/ui/setup/SetupPlatformTypeScreen.kt \
       app/src/main/res/values/strings.xml \
       app/src/main/res/values-zh-rCN/strings.xml
git commit -m "feat: add MiniMax to setup UI with default model MiniMax-M2.7"
```

---

### Task 5: Final Verification

- [ ] **Step 1: Full build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Verify exhaustive when-expressions**

Grep for `when.*compatibleType` and `when.*clientType` to confirm no `when` expression is missing the new `MINIMAX` case. Expressions with `else ->` are fine; exhaustive `when` without `else` must include `MINIMAX`.

- [ ] **Step 3: Verify all files touched**

Confirm these files were modified/created:
- `ClientType.kt` — has `MINIMAX`
- `ModelConstants.kt` — has `MINIMAX_API_URL`
- `DiagnosticModels.kt` — has `ClientType.MINIMAX -> "minimax"`
- `MiniMaxChatCompletionsAgentGateway.kt` — new file
- `ProviderAgentGatewayRouter.kt` — routes `MINIMAX`
- `SetupViewModelV2.kt` — defaults for name, URL, model
- `SetupPlatformTypeScreen.kt` — in `platformTypes` list
- `strings.xml` — `minimax` and `minimax_description`
- `strings.xml` (zh-rCN) — `minimax_description`
