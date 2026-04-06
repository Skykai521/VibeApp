# Conversation Compaction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a multi-strategy conversation compaction system for the agent loop that controls context size and preserves tool-calling compliance across all providers.

**Architecture:** A new `ConversationCompactor` replaces the existing `ConversationContextManager` as a unified compaction engine used by `DefaultAgentLoopCoordinator` before every model call. It supports three strategies in a fallback chain: (1) rule-based trimming of tool result payloads, (2) structural summarization of older turns, and (3) model-based summarization via the same provider API. The coordinator applies compaction to `fullConversation` centrally, so individual gateways no longer need to manage context budgets independently.

**Tech Stack:** Kotlin, Coroutines, kotlinx.serialization, existing OpenAIAPI / AnthropicAPI network layer

---

## Context & Motivation

### Problem
After 2-3 agent loop iterations, `fullConversation` in `DefaultAgentLoopCoordinator` grows unbounded. Tool results (file contents, build logs, view trees) can be 10-50KB each. By turn 3, providers like Kimi/MiniMax stop calling tools because the tool definitions and system prompt get "buried" in the large context. Even providers with larger context windows (Anthropic 200K, OpenAI 128K) degrade in tool-calling compliance as context grows.

### Current State
- `ConversationContextManager` exists but is only called once during `buildInitialConversation()` — not during the agent loop iterations.
- Kimi and MiniMax gateways each instantiate their own `ConversationContextManager` locally (added in a recent fix), but other providers have no trimming.
- No mechanism exists for model-based summarization.

### Design Principles
1. **Compaction happens centrally** in the coordinator, not per-gateway.
2. **Three strategies** form a pipeline: Trim → Summarize (rule-based) → Summarize (model-based).
3. **Recent turns are sacred** — the last N turns are never compacted.
4. **Tool results are the #1 target** — file contents and build logs are aggressively truncated after they've been "consumed" by the model.
5. **Provider-aware budgets** — different providers get different token limits.

### Strategy Details

#### Strategy 1: Rule-Based Payload Trimming (`TrimStrategy`)
Runs first, always, zero cost. Targets the biggest context consumers:
- **`read_project_file` results**: After the model has seen the file and made a tool call referencing it, the full content is replaced with `[File: path, 245 lines — content trimmed]`.
- **`run_build_pipeline` results**: Build logs beyond the error summary are dropped: `[Build: FAILED — 3 errors, full logs trimmed]`.
- **`fix_crash_guide` source_files**: Source file contents in older turns are trimmed.
- **`inspect_ui` / `interact_ui` view trees**: View tree dumps in older turns are trimmed.
- Only applies to turns **outside** the recent window (older turns).

#### Strategy 2: Structural Turn Summarization (`SummarizeStrategy`)
Extends current `ConversationContextManager.summarizeTurn()` with richer output:
- Preserves: user request (full text, up to 500 chars), tool names + paths operated on, final assistant text (up to 500 chars), error/success status.
- Drops: full tool call arguments, tool result payloads, reasoning content.
- Output format: A single USER-role item with `[Compacted Turn]` prefix.

#### Strategy 3: Model-Based Summarization (`ModelSummarizeStrategy`)
Used as a last resort when rule-based strategies still leave context over budget. Makes a lightweight non-streaming API call to the same provider asking it to summarize a batch of older turns into a concise narrative. Uses `completeQwenChatCompletion` for OpenAI-compatible providers. Anthropic and Google would need their own paths (deferred — can use structural summary as fallback).

### Provider Context Budgets

| Provider | Max Context (model) | Our Budget | Recent Turns |
|----------|-------------------|------------|--------------|
| OpenAI (Responses API) | Stateful | No trimming needed (delta-only) | N/A |
| Anthropic | 200K | 80K | 5 |
| Qwen | 128K | 40K | 4 |
| Kimi | 128K | 24K | 3 |
| MiniMax | 1M | 40K | 4 |
| Groq/Ollama/Custom | Varies | 24K | 3 |

---

## File Structure

### New Files
```
app/src/main/kotlin/com/vibe/app/feature/agent/loop/
├── compaction/
│   ├── ConversationCompactor.kt          # Orchestrator: runs strategy chain
│   ├── CompactionStrategy.kt             # Interface + strategy enum
│   ├── ToolResultTrimStrategy.kt         # Strategy 1: payload trimming
│   ├── StructuralSummaryStrategy.kt      # Strategy 2: turn summarization
│   ├── ModelSummaryStrategy.kt           # Strategy 3: model-based summary
│   └── ProviderContextBudget.kt          # Per-provider token budgets
```

### Modified Files
```
app/src/main/kotlin/com/vibe/app/feature/agent/loop/
├── DefaultAgentLoopCoordinator.kt        # Inject compactor, apply before each model call
├── KimiChatCompletionsAgentGateway.kt    # Remove local ConversationContextManager
├── MiniMaxChatCompletionsAgentGateway.kt # Remove local ConversationContextManager
```

### Deprecated (kept for reference, unused)
```
app/src/main/kotlin/com/vibe/app/feature/agent/loop/
├── ConversationContextManager.kt         # Logic migrated into compaction/
```

---

## Task 1: Define Compaction Interfaces and Provider Budgets

**Files:**
- Create: `app/src/main/kotlin/com/vibe/app/feature/agent/loop/compaction/CompactionStrategy.kt`
- Create: `app/src/main/kotlin/com/vibe/app/feature/agent/loop/compaction/ProviderContextBudget.kt`

- [ ] **Step 1: Create CompactionStrategy interface**

```kotlin
// CompactionStrategy.kt
package com.vibe.app.feature.agent.loop.compaction

import com.vibe.app.feature.agent.AgentConversationItem

/**
 * Result of a compaction pass. Contains the compacted conversation and metadata
 * about what changed.
 */
data class CompactionResult(
    val items: List<AgentConversationItem>,
    val estimatedTokens: Int,
    val strategyUsed: CompactionStrategyType,
    val turnsCompacted: Int,
)

enum class CompactionStrategyType {
    /** No compaction needed — context is within budget. */
    NONE,
    /** Tool result payloads in older turns were trimmed. */
    TOOL_RESULT_TRIM,
    /** Older turns were structurally summarized (client-side rules). */
    STRUCTURAL_SUMMARY,
    /** Older turns were summarized via a model API call. */
    MODEL_SUMMARY,
}

/**
 * A single compaction strategy that can reduce conversation context size.
 * Strategies are applied in order until context fits within budget.
 */
interface CompactionStrategy {
    val type: CompactionStrategyType

    /**
     * Attempt to compact the conversation.
     *
     * @param items Full conversation history.
     * @param recentTurnCount Number of recent turns to preserve untouched.
     * @param tokenBudget Maximum token count target.
     * @return Compacted result, or null if this strategy cannot help further.
     */
    suspend fun compact(
        items: List<AgentConversationItem>,
        recentTurnCount: Int,
        tokenBudget: Int,
    ): CompactionResult?
}
```

- [ ] **Step 2: Create ProviderContextBudget**

```kotlin
// ProviderContextBudget.kt
package com.vibe.app.feature.agent.loop.compaction

import com.vibe.app.data.model.ClientType

/**
 * Per-provider context budget configuration.
 *
 * These budgets are intentionally conservative relative to the model's actual
 * context window to keep tool definitions and system prompts prominent.
 */
data class ProviderContextBudget(
    val maxTokens: Int,
    val recentTurns: Int,
) {
    companion object {
        fun forProvider(clientType: ClientType): ProviderContextBudget = when (clientType) {
            ClientType.ANTHROPIC -> ProviderContextBudget(maxTokens = 80_000, recentTurns = 5)
            ClientType.QWEN -> ProviderContextBudget(maxTokens = 40_000, recentTurns = 4)
            ClientType.KIMI -> ProviderContextBudget(maxTokens = 24_000, recentTurns = 3)
            ClientType.MINIMAX -> ProviderContextBudget(maxTokens = 40_000, recentTurns = 4)
            ClientType.OPENAI -> ProviderContextBudget(maxTokens = 60_000, recentTurns = 5)
            // Conservative defaults for unknown/custom providers
            ClientType.GROQ, ClientType.OLLAMA, ClientType.OPENROUTER, ClientType.CUSTOM ->
                ProviderContextBudget(maxTokens = 24_000, recentTurns = 3)
            ClientType.GOOGLE -> ProviderContextBudget(maxTokens = 60_000, recentTurns = 5)
        }
    }
}
```

- [ ] **Step 3: Verify compilation**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/feature/agent/loop/compaction/CompactionStrategy.kt \
      app/src/main/kotlin/com/vibe/app/feature/agent/loop/compaction/ProviderContextBudget.kt
git commit -m "feat(agent): add compaction strategy interface and provider context budgets"
```

---

## Task 2: Implement Tool Result Trim Strategy

**Files:**
- Create: `app/src/main/kotlin/com/vibe/app/feature/agent/loop/compaction/ToolResultTrimStrategy.kt`

This is the most impactful strategy — it targets the biggest context consumers (file contents, build logs, view trees) in older turns without losing any structural information.

- [ ] **Step 1: Create ToolResultTrimStrategy**

```kotlin
// ToolResultTrimStrategy.kt
package com.vibe.app.feature.agent.loop.compaction

import com.vibe.app.feature.agent.AgentConversationItem
import com.vibe.app.feature.agent.AgentMessageRole
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Strategy 1: Trim large tool result payloads in older turns.
 *
 * Targets specific tool types known to produce large outputs:
 * - read_project_file: file contents → "[File: path, N lines — trimmed]"
 * - run_build_pipeline: build logs → "[Build: STATUS — N errors, logs trimmed]"
 * - fix_crash_guide: source files → trimmed
 * - inspect_ui / interact_ui: view trees → trimmed
 * - read_runtime_log: log contents → trimmed
 *
 * Only applies to turns outside the recent window.
 */
class ToolResultTrimStrategy : CompactionStrategy {
    override val type = CompactionStrategyType.TOOL_RESULT_TRIM

    override suspend fun compact(
        items: List<AgentConversationItem>,
        recentTurnCount: Int,
        tokenBudget: Int,
    ): CompactionResult? {
        val turns = splitIntoTurns(items)
        if (turns.size <= recentTurnCount) return null

        val olderTurns = turns.dropLast(recentTurnCount)
        val recentTurns = turns.takeLast(recentTurnCount)

        var trimmed = false
        val compactedOlder = olderTurns.map { turn ->
            turn.map { item ->
                if (item.role == AgentMessageRole.TOOL && item.payload != null) {
                    val trimmedPayload = trimToolPayload(item.toolName, item.payload)
                    if (trimmedPayload !== item.payload) {
                        trimmed = true
                        item.copy(payload = trimmedPayload)
                    } else {
                        item
                    }
                } else if (item.role == AgentMessageRole.ASSISTANT) {
                    // Drop reasoning content from older turns — it's already been "consumed"
                    if (item.reasoningContent != null) {
                        trimmed = true
                        item.copy(reasoningContent = null)
                    } else {
                        item
                    }
                } else {
                    item
                }
            }
        }

        if (!trimmed) return null

        val result = compactedOlder.flatten() + recentTurns.flatten()
        val tokens = estimateTokens(result)
        return CompactionResult(
            items = result,
            estimatedTokens = tokens,
            strategyUsed = type,
            turnsCompacted = olderTurns.size,
        )
    }

    private fun trimToolPayload(toolName: String?, payload: JsonElement): JsonElement {
        if (payload !is JsonObject) return payload

        return when (toolName) {
            "read_project_file" -> trimReadFilePayload(payload)
            "run_build_pipeline" -> trimBuildPayload(payload)
            "fix_crash_guide" -> trimCrashGuidePayload(payload)
            "inspect_ui" -> buildJsonObject {
                put("trimmed", JsonPrimitive("[View tree trimmed — use inspect_ui to get current state]"))
            }
            "interact_ui" -> trimInteractUiPayload(payload)
            "read_runtime_log" -> trimRuntimeLogPayload(payload)
            else -> payload
        }
    }

    private fun trimReadFilePayload(payload: JsonObject): JsonElement {
        // Single file: {"path": "...", "content": "..."}
        val path = payload["path"]?.jsonPrimitive?.content
        val content = payload["content"]?.jsonPrimitive?.content
        if (path != null && content != null) {
            val lines = content.count { it == '\n' } + 1
            return buildJsonObject {
                put("path", JsonPrimitive(path))
                put("content", JsonPrimitive("[File: $path, $lines lines — content trimmed from earlier turn]"))
            }
        }

        // Multi file: {"files": [{"path": "...", "content": "..."}]}
        val files = payload["files"]
        if (files != null) {
            return buildJsonObject {
                put("files", kotlinx.serialization.json.buildJsonArray {
                    (files as? kotlinx.serialization.json.JsonArray)?.forEach { fileEl ->
                        val fileObj = fileEl.jsonObject
                        val filePath = fileObj["path"]?.jsonPrimitive?.content ?: "unknown"
                        val fileContent = fileObj["content"]?.jsonPrimitive?.content
                        add(buildJsonObject {
                            put("path", JsonPrimitive(filePath))
                            if (fileContent != null) {
                                val lineCount = fileContent.count { it == '\n' } + 1
                                put("content", JsonPrimitive("[File: $filePath, $lineCount lines — trimmed]"))
                            } else {
                                fileObj["error"]?.let { put("error", it) }
                            }
                        })
                    }
                })
            }
        }

        return payload
    }

    private fun trimBuildPayload(payload: JsonObject): JsonElement {
        val status = payload["status"]?.jsonPrimitive?.content ?: "UNKNOWN"
        val errorMessage = payload["errorMessage"]?.jsonPrimitive?.content
        return buildJsonObject {
            put("status", JsonPrimitive(status))
            if (errorMessage != null) {
                put("errorMessage", JsonPrimitive(errorMessage.take(500)))
            }
            put("note", JsonPrimitive("[Detailed build logs trimmed from earlier turn]"))
        }
    }

    private fun trimCrashGuidePayload(payload: JsonObject): JsonElement {
        val crashLog = payload["crash_log"]?.jsonPrimitive?.content
        return buildJsonObject {
            if (crashLog != null) {
                put("crash_log", JsonPrimitive(crashLog.take(500)))
            }
            put("note", JsonPrimitive("[Source files trimmed from earlier turn]"))
        }
    }

    private fun trimInteractUiPayload(payload: JsonObject): JsonElement {
        val result = payload["result"]?.jsonPrimitive?.content
        return buildJsonObject {
            if (result != null) put("result", JsonPrimitive(result))
            put("view_tree", JsonPrimitive("[View tree trimmed — use inspect_ui for current state]"))
        }
    }

    private fun trimRuntimeLogPayload(payload: JsonObject): JsonElement {
        return buildJsonObject {
            put("note", JsonPrimitive("[Runtime logs trimmed from earlier turn — use read_runtime_log for latest]"))
        }
    }

    companion object {
        /** Split items into turns, where each turn starts with a USER message. */
        fun splitIntoTurns(items: List<AgentConversationItem>): List<List<AgentConversationItem>> {
            val turns = mutableListOf<MutableList<AgentConversationItem>>()
            for (item in items) {
                if (item.role == AgentMessageRole.USER) {
                    turns.add(mutableListOf(item))
                } else if (turns.isNotEmpty()) {
                    turns.last().add(item)
                }
            }
            return turns
        }

        fun estimateTokens(items: List<AgentConversationItem>): Int {
            return ConversationContextManager.estimateTokens(items)
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/feature/agent/loop/compaction/ToolResultTrimStrategy.kt
git commit -m "feat(agent): implement tool result trim strategy for conversation compaction"
```

---

## Task 3: Implement Structural Summary Strategy

**Files:**
- Create: `app/src/main/kotlin/com/vibe/app/feature/agent/loop/compaction/StructuralSummaryStrategy.kt`

This migrates and improves the logic from `ConversationContextManager.summarizeTurn()`.

- [ ] **Step 1: Create StructuralSummaryStrategy**

```kotlin
// StructuralSummaryStrategy.kt
package com.vibe.app.feature.agent.loop.compaction

import com.vibe.app.feature.agent.AgentConversationItem
import com.vibe.app.feature.agent.AgentMessageRole

/**
 * Strategy 2: Structurally summarize older turns into compact single-item representations.
 *
 * Each older turn is compressed into a single USER-role item containing:
 * - User request (up to 500 chars)
 * - Tool names and file paths operated on
 * - Assistant response summary (up to 500 chars)
 * - Error/success indicators
 *
 * This is a client-side operation with zero API cost.
 */
class StructuralSummaryStrategy : CompactionStrategy {
    override val type = CompactionStrategyType.STRUCTURAL_SUMMARY

    override suspend fun compact(
        items: List<AgentConversationItem>,
        recentTurnCount: Int,
        tokenBudget: Int,
    ): CompactionResult? {
        val turns = ToolResultTrimStrategy.splitIntoTurns(items)
        if (turns.size <= recentTurnCount) return null

        val olderTurns = turns.dropLast(recentTurnCount)
        val recentTurns = turns.takeLast(recentTurnCount)

        val summaryItems = olderTurns.mapNotNull { turn -> summarizeTurn(turn) }
        val recentItems = recentTurns.flatten()
        val result = summaryItems + recentItems

        // If still over budget, progressively drop oldest summaries
        val mutableResult = result.toMutableList()
        while (mutableResult.size > recentItems.size &&
            ToolResultTrimStrategy.estimateTokens(mutableResult) > tokenBudget
        ) {
            mutableResult.removeAt(0)
        }

        val tokens = ToolResultTrimStrategy.estimateTokens(mutableResult)
        return CompactionResult(
            items = mutableResult,
            estimatedTokens = tokens,
            strategyUsed = type,
            turnsCompacted = olderTurns.size,
        )
    }

    private fun summarizeTurn(turn: List<AgentConversationItem>): AgentConversationItem? {
        val userItem = turn.firstOrNull { it.role == AgentMessageRole.USER } ?: return null
        val userText = userItem.text?.take(MAX_USER_TEXT) ?: return null

        val toolCalls = turn
            .filter { it.role == AgentMessageRole.ASSISTANT }
            .flatMap { it.toolCalls.orEmpty() }

        val toolSummaries = toolCalls.map { call ->
            val pathArg = runCatching {
                val args = call.arguments
                if (args is kotlinx.serialization.json.JsonObject) {
                    args["path"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                } else null
            }.getOrNull()
            if (pathArg != null) "${call.name}($pathArg)" else call.name
        }.distinct()

        val toolErrors = turn
            .filter { it.role == AgentMessageRole.TOOL }
            .mapNotNull { item ->
                val payload = item.payload
                if (payload is kotlinx.serialization.json.JsonObject && payload.containsKey("error")) {
                    "${item.toolName}: ${payload["error"]}"
                } else null
            }

        val assistantText = turn
            .filter { it.role == AgentMessageRole.ASSISTANT }
            .mapNotNull { it.text?.take(MAX_ASSISTANT_TEXT) }
            .joinToString(" ")
            .take(MAX_ASSISTANT_TEXT)

        val summary = buildString {
            append("[Compacted Turn]\n")
            append("User: $userText\n")
            if (toolSummaries.isNotEmpty()) {
                append("Tools: ${toolSummaries.joinToString(", ")}\n")
            }
            if (toolErrors.isNotEmpty()) {
                append("Errors: ${toolErrors.joinToString("; ")}\n")
            }
            if (assistantText.isNotBlank()) {
                append("Result: $assistantText")
            }
        }

        return AgentConversationItem(
            role = AgentMessageRole.USER,
            text = summary,
        )
    }

    companion object {
        private const val MAX_USER_TEXT = 500
        private const val MAX_ASSISTANT_TEXT = 500
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/feature/agent/loop/compaction/StructuralSummaryStrategy.kt
git commit -m "feat(agent): implement structural summary strategy for conversation compaction"
```

---

## Task 4: Implement Model-Based Summary Strategy

**Files:**
- Create: `app/src/main/kotlin/com/vibe/app/feature/agent/loop/compaction/ModelSummaryStrategy.kt`

This strategy calls the same provider's API to generate a concise summary of older turns when rule-based strategies aren't sufficient. Only available for OpenAI-compatible providers (which covers Qwen, Kimi, MiniMax, Groq, etc.) via the existing `completeQwenChatCompletion`.

- [ ] **Step 1: Create ModelSummaryStrategy**

```kotlin
// ModelSummaryStrategy.kt
package com.vibe.app.feature.agent.loop.compaction

import com.vibe.app.data.dto.qwen.request.QwenChatCompletionRequest
import com.vibe.app.data.dto.qwen.request.QwenChatMessage
import com.vibe.app.data.dto.qwen.request.qwenTextContent
import com.vibe.app.data.model.ClientType
import com.vibe.app.data.network.OpenAIAPI
import com.vibe.app.feature.agent.AgentConversationItem
import com.vibe.app.feature.agent.AgentMessageRole

/**
 * Strategy 3: Model-based summarization of older conversation turns.
 *
 * Makes a non-streaming API call to the same provider, asking it to produce
 * a concise summary of the conversation history. The summary replaces all
 * older turns with a single USER-role item.
 *
 * Availability:
 * - OpenAI-compatible providers (Qwen, Kimi, MiniMax, Groq, Ollama, OpenRouter, Custom): YES
 * - OpenAI (Responses API): Skipped (stateful, doesn't need compaction)
 * - Anthropic / Google: Falls back to structural summary (no non-streaming endpoint wired)
 *
 * Cost: One additional API call per compaction. Uses a small, fast model when possible.
 */
class ModelSummaryStrategy(
    private val openAIAPI: OpenAIAPI,
) : CompactionStrategy {
    override val type = CompactionStrategyType.MODEL_SUMMARY

    /**
     * Which provider types support model-based summarization.
     * OpenAI is excluded because it uses the stateful Responses API.
     * Anthropic/Google are excluded until their non-streaming endpoints are wired.
     */
    private val supportedProviders = setOf(
        ClientType.QWEN,
        ClientType.KIMI,
        ClientType.MINIMAX,
        ClientType.GROQ,
        ClientType.OLLAMA,
        ClientType.OPENROUTER,
        ClientType.CUSTOM,
    )

    override suspend fun compact(
        items: List<AgentConversationItem>,
        recentTurnCount: Int,
        tokenBudget: Int,
    ): CompactionResult? {
        val turns = ToolResultTrimStrategy.splitIntoTurns(items)
        if (turns.size <= recentTurnCount) return null

        val olderTurns = turns.dropLast(recentTurnCount)
        val recentTurns = turns.takeLast(recentTurnCount)

        // Build the text to summarize from older turns
        val textToSummarize = buildSummarizationInput(olderTurns.flatten())
        if (textToSummarize.isBlank()) return null

        val summary = try {
            callSummarizationAPI(textToSummarize)
        } catch (_: Exception) {
            // If API call fails, return null to let the next strategy handle it
            return null
        }

        if (summary.isNullOrBlank()) return null

        val summaryItem = AgentConversationItem(
            role = AgentMessageRole.USER,
            text = "[Conversation Summary]\n$summary",
        )

        val result = listOf(summaryItem) + recentTurns.flatten()
        val tokens = ToolResultTrimStrategy.estimateTokens(result)
        return CompactionResult(
            items = result,
            estimatedTokens = tokens,
            strategyUsed = type,
            turnsCompacted = olderTurns.size,
        )
    }

    fun isSupported(clientType: ClientType): Boolean = clientType in supportedProviders

    private fun buildSummarizationInput(items: List<AgentConversationItem>): String {
        return buildString {
            items.forEach { item ->
                when (item.role) {
                    AgentMessageRole.USER -> {
                        append("User: ${item.text?.take(1000) ?: ""}\n")
                    }
                    AgentMessageRole.ASSISTANT -> {
                        val tools = item.toolCalls?.joinToString(", ") { it.name } ?: ""
                        if (tools.isNotEmpty()) append("Assistant used tools: $tools\n")
                        item.text?.take(500)?.let { append("Assistant: $it\n") }
                    }
                    AgentMessageRole.TOOL -> {
                        val result = item.payload?.toString()?.take(200) ?: item.text?.take(200) ?: ""
                        append("Tool ${item.toolName} result: $result\n")
                    }
                    AgentMessageRole.SYSTEM -> Unit
                }
            }
        }.take(MAX_SUMMARIZATION_INPUT)
    }

    private suspend fun callSummarizationAPI(text: String): String? {
        val response = openAIAPI.completeQwenChatCompletion(
            QwenChatCompletionRequest(
                model = "", // Uses whatever model is currently configured via setToken/setAPIUrl
                messages = listOf(
                    QwenChatMessage(
                        role = "system",
                        content = qwenTextContent(SUMMARIZATION_SYSTEM_PROMPT),
                    ),
                    QwenChatMessage(
                        role = "user",
                        content = qwenTextContent(text),
                    ),
                ),
                stream = false,
            ),
        )
        return response.choices?.firstOrNull()?.message?.content?.trim()
    }

    companion object {
        private const val MAX_SUMMARIZATION_INPUT = 8_000

        private const val SUMMARIZATION_SYSTEM_PROMPT =
            """You are a conversation summarizer for an AI coding assistant. Summarize the following conversation history into a concise narrative that preserves:
1. What the user asked for
2. Which files were created, read, or modified (include file paths)
3. What tools were used and their outcomes
4. Any errors encountered and how they were resolved
5. The current state of the project

Be concise but preserve all file paths and technical details. Output plain text, no markdown headers. Max 500 words."""
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/feature/agent/loop/compaction/ModelSummaryStrategy.kt
git commit -m "feat(agent): implement model-based summary strategy for conversation compaction"
```

---

## Task 5: Implement ConversationCompactor Orchestrator

**Files:**
- Create: `app/src/main/kotlin/com/vibe/app/feature/agent/loop/compaction/ConversationCompactor.kt`

The orchestrator runs strategies in order until context fits within budget.

- [ ] **Step 1: Create ConversationCompactor**

```kotlin
// ConversationCompactor.kt
package com.vibe.app.feature.agent.loop.compaction

import com.vibe.app.data.model.ClientType
import com.vibe.app.data.network.OpenAIAPI
import com.vibe.app.feature.agent.AgentConversationItem
import com.vibe.app.feature.agent.loop.ConversationContextManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central conversation compaction engine for the agent loop.
 *
 * Runs a chain of strategies to reduce conversation context size:
 * 1. [ToolResultTrimStrategy] — trim large tool payloads in older turns (free)
 * 2. [StructuralSummaryStrategy] — summarize older turns client-side (free)
 * 3. [ModelSummaryStrategy] — summarize via model API (costs one API call)
 *
 * Each strategy is tried in order. If context fits within budget after a strategy,
 * the chain stops. If all strategies are exhausted, the best result is returned.
 */
@Singleton
class ConversationCompactor @Inject constructor(
    private val openAIAPI: OpenAIAPI,
) {
    private val toolResultTrimStrategy = ToolResultTrimStrategy()
    private val structuralSummaryStrategy = StructuralSummaryStrategy()
    private val modelSummaryStrategy = ModelSummaryStrategy(openAIAPI)

    /**
     * Compact the conversation to fit within the provider's context budget.
     *
     * @param items The full conversation history.
     * @param clientType The provider type (determines budget and available strategies).
     * @return The compacted conversation. May be unchanged if already within budget.
     */
    suspend fun compact(
        items: List<AgentConversationItem>,
        clientType: ClientType,
    ): CompactionResult {
        val budget = ProviderContextBudget.forProvider(clientType)
        val currentTokens = ConversationContextManager.estimateTokens(items)

        // Already within budget — no compaction needed
        if (currentTokens <= budget.maxTokens) {
            return CompactionResult(
                items = items,
                estimatedTokens = currentTokens,
                strategyUsed = CompactionStrategyType.NONE,
                turnsCompacted = 0,
            )
        }

        // Strategy 1: Trim tool result payloads
        val trimResult = toolResultTrimStrategy.compact(items, budget.recentTurns, budget.maxTokens)
        if (trimResult != null && trimResult.estimatedTokens <= budget.maxTokens) {
            return trimResult
        }

        // Use the trimmed result as input for the next strategy (cumulative)
        val afterTrim = trimResult?.items ?: items

        // Strategy 2: Structural summarization
        val structuralResult = structuralSummaryStrategy.compact(
            afterTrim, budget.recentTurns, budget.maxTokens,
        )
        if (structuralResult != null && structuralResult.estimatedTokens <= budget.maxTokens) {
            return structuralResult
        }

        // Strategy 3: Model-based summarization (only for supported providers)
        if (modelSummaryStrategy.isSupported(clientType)) {
            val modelResult = modelSummaryStrategy.compact(
                afterTrim, budget.recentTurns, budget.maxTokens,
            )
            if (modelResult != null) {
                return modelResult
            }
        }

        // All strategies exhausted — return best result we have
        return structuralResult ?: trimResult ?: CompactionResult(
            items = items,
            estimatedTokens = currentTokens,
            strategyUsed = CompactionStrategyType.NONE,
            turnsCompacted = 0,
        )
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/feature/agent/loop/compaction/ConversationCompactor.kt
git commit -m "feat(agent): implement ConversationCompactor orchestrator with strategy chain"
```

---

## Task 6: Integrate Compactor into DefaultAgentLoopCoordinator

**Files:**
- Modify: `app/src/main/kotlin/com/vibe/app/feature/agent/loop/DefaultAgentLoopCoordinator.kt`

The compactor is called before every model call in the agent loop. This replaces the old `ConversationContextManager` usage and makes compaction centralized rather than per-gateway.

- [ ] **Step 1: Add compactor injection and apply before model calls**

In `DefaultAgentLoopCoordinator.kt`, make these changes:

1. Add `ConversationCompactor` to constructor injection (alongside existing deps).
2. Before each `agentModelGateway.streamTurn()` call, compact `fullConversation`.
3. Remove the old `contextManager` field since compaction is now centralized.

```kotlin
// Changes to DefaultAgentLoopCoordinator.kt

// 1. Add import
import com.vibe.app.feature.agent.loop.compaction.ConversationCompactor

// 2. Update constructor — add compactor parameter
@Singleton
class DefaultAgentLoopCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val agentModelGateway: AgentModelGateway,
    private val agentToolRegistry: AgentToolRegistry,
    private val diagnosticLogger: ChatDiagnosticLogger,
    private val projectManager: ProjectManager,
    private val conversationCompactor: ConversationCompactor,
) : AgentLoopCoordinator {

    // 3. Remove old contextManager field:
    // DELETE: private val contextManager = ConversationContextManager()

    // 4. In run(), before the model call inside the loop (line ~69), compact fullConversation:
    //    Replace the block that builds AgentModelRequest to use compacted conversation.

    // Before:
    //   agentModelGateway.streamTurn(
    //       AgentModelRequest(
    //           ...
    //           fullConversation = fullConversation.toList(),
    //           ...
    //       ),
    //   )

    // After:
    //   val compactionResult = conversationCompactor.compact(
    //       items = fullConversation.toList(),
    //       clientType = request.platform.compatibleType,
    //   )
    //   val compactedConversation = compactionResult.items
    //
    //   agentModelGateway.streamTurn(
    //       AgentModelRequest(
    //           ...
    //           fullConversation = compactedConversation,
    //           ...
    //       ),
    //   )

    // 5. In buildInitialConversation(), replace contextManager.trimConversation() with
    //    just returning the items directly (compaction now happens before each model call):
    //
    //    Before: return contextManager.trimConversation(items)
    //    After:  return items

    // 6. Apply same compaction to the wind-down model call (line ~221):
    //   val compactionResult = conversationCompactor.compact(
    //       items = fullConversation.toList(),
    //       clientType = request.platform.compatibleType,
    //   )
    //   agentModelGateway.streamTurn(
    //       AgentModelRequest(
    //           ...
    //           fullConversation = compactionResult.items,
    //           ...
    //       ),
    //   )
}
```

The key insight: `fullConversation` itself remains the **uncompacted** ground truth. Compaction is applied as a view when building the `AgentModelRequest`. This means:
- Tool results are always stored in full in `fullConversation`.
- Only the version sent to the model is compacted.
- If the model needs to re-read a file, it can call `read_project_file` again.

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/feature/agent/loop/DefaultAgentLoopCoordinator.kt
git commit -m "feat(agent): integrate ConversationCompactor into agent loop coordinator"
```

---

## Task 7: Remove Per-Gateway Local Compaction

**Files:**
- Modify: `app/src/main/kotlin/com/vibe/app/feature/agent/loop/KimiChatCompletionsAgentGateway.kt`
- Modify: `app/src/main/kotlin/com/vibe/app/feature/agent/loop/MiniMaxChatCompletionsAgentGateway.kt`

Since compaction is now centralized in the coordinator, remove the per-gateway `ConversationContextManager` instances. Keep the `TOOL_ENCOURAGE_INSTRUCTION` since that's a system prompt enhancement, not compaction.

- [ ] **Step 1: Update KimiChatCompletionsAgentGateway**

Remove:
```kotlin
private val contextManager = ConversationContextManager(
    maxContextTokens = KIMI_MAX_CONTEXT_TOKENS,
    recentTurnsToKeepFull = KIMI_RECENT_TURNS,
)
```

In `buildMessages()`, replace:
```kotlin
val trimmedConversation = contextManager.trimConversation(request.fullConversation)
trimmedConversation.forEach { item ->
```
With:
```kotlin
request.fullConversation.forEach { item ->
```

Remove from companion object:
```kotlin
private const val KIMI_MAX_CONTEXT_TOKENS = 16_000
private const val KIMI_RECENT_TURNS = 3
```

Keep `TOOL_REQUIRED_INSTRUCTION` and `TOOL_ENCOURAGE_INSTRUCTION` — these are system prompt enhancements unrelated to compaction.

- [ ] **Step 2: Update MiniMaxChatCompletionsAgentGateway**

Same changes as Kimi:
- Remove `contextManager` field and `MINIMAX_MAX_CONTEXT_TOKENS`/`MINIMAX_RECENT_TURNS` constants.
- Replace `contextManager.trimConversation(request.fullConversation)` with `request.fullConversation`.
- Keep tool instruction constants.

- [ ] **Step 3: Verify compilation**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/feature/agent/loop/KimiChatCompletionsAgentGateway.kt \
      app/src/main/kotlin/com/vibe/app/feature/agent/loop/MiniMaxChatCompletionsAgentGateway.kt
git commit -m "refactor(agent): remove per-gateway compaction in favor of centralized compactor"
```

---

## Task 8: Add Compaction Logging via Diagnostics

**Files:**
- Modify: `app/src/main/kotlin/com/vibe/app/feature/agent/loop/DefaultAgentLoopCoordinator.kt`

Add a diagnostic log entry whenever compaction occurs, so we can monitor effectiveness.

- [ ] **Step 1: Add compaction logging after each compact() call**

After each `conversationCompactor.compact()` call in the coordinator, add:

```kotlin
if (compactionResult.strategyUsed != CompactionStrategyType.NONE) {
    request.diagnosticContext?.copy(platformUid = request.platform.uid)?.let { ctx ->
        diagnosticLogger.logCustomEvent(
            context = ctx,
            event = "conversation_compaction",
            details = mapOf(
                "strategy" to compactionResult.strategyUsed.name,
                "turns_compacted" to compactionResult.turnsCompacted.toString(),
                "estimated_tokens" to compactionResult.estimatedTokens.toString(),
                "items_before" to fullConversation.size.toString(),
                "items_after" to compactionResult.items.size.toString(),
            ),
        )
    }
}
```

Note: This assumes `ChatDiagnosticLogger` has a `logCustomEvent` method. If it doesn't, check the existing interface and use the closest available method, or add `logCustomEvent` if needed.

- [ ] **Step 2: Verify `logCustomEvent` exists or add it**

Check `ChatDiagnosticLogger` for available methods. If `logCustomEvent` doesn't exist, add:

```kotlin
fun logCustomEvent(
    context: DiagnosticContext,
    event: String,
    details: Map<String, String> = emptyMap(),
)
```

With implementation that logs to the existing diagnostic output (Logcat or file).

- [ ] **Step 3: Verify compilation**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/feature/agent/loop/DefaultAgentLoopCoordinator.kt \
      app/src/main/kotlin/com/vibe/app/feature/diagnostic/ChatDiagnosticLogger.kt
git commit -m "feat(agent): add diagnostic logging for conversation compaction events"
```

---

## Task 9: Deprecate ConversationContextManager

**Files:**
- Modify: `app/src/main/kotlin/com/vibe/app/feature/agent/loop/ConversationContextManager.kt`

The logic has been migrated to the new compaction system. Keep the file for its `estimateTokens` utility but deprecate the class.

- [ ] **Step 1: Add deprecation annotation**

```kotlin
/**
 * @deprecated Use [com.vibe.app.feature.agent.loop.compaction.ConversationCompactor] instead.
 * Kept for [estimateTokens] utility methods used by compaction strategies.
 */
@Deprecated(
    message = "Use ConversationCompactor for conversation compaction",
    replaceWith = ReplaceWith("ConversationCompactor", "com.vibe.app.feature.agent.loop.compaction.ConversationCompactor"),
)
class ConversationContextManager(
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (with deprecation warnings, not errors)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/feature/agent/loop/ConversationContextManager.kt
git commit -m "refactor(agent): deprecate ConversationContextManager in favor of ConversationCompactor"
```

---

## Architecture Summary

```
┌─────────────────────────────────────────────────────────────┐
│                 DefaultAgentLoopCoordinator                  │
│                                                             │
│  fullConversation (uncompacted ground truth)                │
│         │                                                   │
│         ▼                                                   │
│  ┌─────────────────────────┐                                │
│  │  ConversationCompactor  │                                │
│  │                         │                                │
│  │  1. ToolResultTrim ─────┼── Free: trim payloads          │
│  │  2. StructuralSummary ──┼── Free: summarize turns        │
│  │  3. ModelSummary ───────┼── 1 API call: LLM summary      │
│  │                         │                                │
│  │  Budget: per-provider   │                                │
│  └────────────┬────────────┘                                │
│               │                                             │
│               ▼                                             │
│  compactedConversation (sent to model)                      │
│         │                                                   │
│         ▼                                                   │
│  ┌─────────────────────────────┐                            │
│  │  ProviderAgentGatewayRouter │                            │
│  │  ├─ OpenAI (delta only)     │                            │
│  │  ├─ Anthropic (full)        │                            │
│  │  ├─ Qwen (full)             │                            │
│  │  ├─ Kimi (full)             │                            │
│  │  └─ MiniMax (full)          │                            │
│  └─────────────────────────────┘                            │
└─────────────────────────────────────────────────────────────┘
```

**Key design decisions:**
1. `fullConversation` is never mutated by compaction — compaction produces a view.
2. Strategies are cumulative — Strategy 2 operates on the output of Strategy 1.
3. OpenAI Responses API is exempt — it uses delta-only stateful protocol.
4. Model-based summary is a last resort — most conversations should be handled by free strategies.
5. Per-provider budgets are conservative (25-60% of actual context window) to maintain tool-calling compliance.
