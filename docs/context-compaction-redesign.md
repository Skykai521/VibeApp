# Context Compaction Redesign

## Problem

### Symptom

Kimi (kimi-k2.5) stops calling tools after 2-3 turns of multi-turn conversation. Diagnostic logs show request body sizes growing from 15 KB to 1.18 MB across turns, while the compaction system never triggers.

### Root Cause

The compaction system has a **blind spot for cross-turn conversation history**.

When a new agent loop starts, `buildInitialConversation()` loads previous chat history from Room as flat USER/ASSISTANT text pairs. A single assistant message can exceed 500 KB because it contains the full streamed output from a previous agent loop (30 iterations of tool calls, file contents, build logs, view trees — all concatenated into one text blob).

All three compaction strategies share the same guard:

```kotlin
val turns = splitIntoTurns(items)
if (turns.size <= recentTurnCount) return null  // Kimi: recentTurns=3
```

`splitIntoTurns()` counts USER messages. With 2-3 previous user messages (= 2-3 turns ≤ 3), every strategy returns null. The 500 KB+ conversation goes to Kimi's API uncompressed.

Even when turn count exceeds `recentTurns`, the 3 most recent turns are kept in full — but each can be 100-500 KB.

### Data Evidence (from diagnostic log)

| Turn | Messages | Body Size | Tool Calls | Compaction |
|------|----------|-----------|------------|------------|
| 4-1  | 2        | 15 KB     | 16 tools   | N/A (first turn) |
| 4-2  | 4        | **513 KB** | 3 tools   | **None (2 turns ≤ 3)** |
| 4-3  | 6        | **783 KB** | 0 tools   | **None (3 turns ≤ 3)** |
| 4-4  | 8        | **785 KB** | 30 tools  | Strategies fire but recent turns still huge |
| 4-5  | 10       | **787 KB** | **0 tools** | Insufficient |
| 4-6  | 12       | **1,184 KB** | **0 tools** | Insufficient |
| 4-7  | 14       | **1,185 KB** | **0 tools** | Insufficient |

### Contributing Factors

1. **Kimi doesn't support `tool_choice: "required"`** — gateway sends `"auto"` with a soft system prompt instruction. When context is too large, the model ignores it.
2. **No per-message size awareness** — strategies think in "turns" not "bytes".
3. **`recentTurns` is a hard gate, not a soft preference** — it blocks compaction entirely rather than doing partial compression.

## Decision

### Information Value Analysis

Model performance depends on what information is preserved, not just how much:

| Information Type | Value | Rationale |
|-----------------|-------|-----------|
| System prompt + tool definitions | Highest | Always needed for tool calling |
| Current user message | Highest | The active task |
| Latest assistant response (1 turn back) | High | Continuity with current task |
| Previous turns' user requests | Medium | Usually short, worth keeping |
| Previous turns' file paths + tool names | Medium | Project context |
| Previous turns' error messages | Medium | Avoid repeating mistakes |
| Previous turns' full file contents | Low | Can re-read with tools |
| Previous turns' build logs | Low | Can rebuild |
| Previous turns' view trees | Lowest | Ephemeral, can re-inspect |

### Chosen Approach: Two-Phase Compaction (Option 1)

Split compaction into two phases, each solving a different problem:

**Phase A — Cross-turn compaction** (new, handles Room-loaded flat messages):

```
Budget allocation by recency (decaying):
  Most recent assistant: up to 4000 chars (~1000 tokens)
  Second recent assistant: up to 1500 chars (~375 tokens)
  Older assistants: structural summary ~500 chars each
  All user messages: kept in full (usually small)
```

Phase A runs inside `buildInitialConversation()` with access to the provider's budget. It recognizes that assistant messages without `toolCalls` are flat text from Room and applies recency-weighted truncation.

**Phase B — Within-loop compaction** (existing strategies 1-3, unchanged):

```
Strategy 1: Trim tool result payloads in older iterations
Strategy 2: Structural summarization of older iterations
Strategy 3: Model-based summarization (API call)
Safety net: TEXT_TRUNCATION fallback (added as part of this work)
```

Phase B strategies keep their `recentTurns` guard because after Phase A, the initial conversation is already within a reasonable size. The strategies only need to handle the growth from current loop iterations.

### Why Not Option 2 (Unified Budget Allocation)

Option 2 would remove the `recentTurns` hard gate entirely and replace it with a weight-based budget allocation system across all items. While more theoretically correct, it requires rewriting the core logic of all three strategies and introduces risk of breaking the within-loop compaction that already works well.

Option 1 is preferred because:
- Targeted fix: Phase A handles 90% of the problem (Room message bloat)
- Minimal disruption: existing strategies continue working as designed
- Clear separation: cross-turn vs within-loop are distinct problems with distinct solutions
- Safety net: TEXT_TRUNCATION fallback covers any remaining edge cases

## Implementation

### Phase A: Cross-Turn Compaction

Location: `DefaultAgentLoopCoordinator.buildInitialConversation()`

1. After building the initial conversation items from Room messages, estimate total tokens
2. If within budget → no changes (fast path)
3. If over budget → apply recency-weighted truncation:
   - Identify assistant items (no `toolCalls`) from oldest to newest
   - Latest assistant: truncate to `maxRecentChars` (4000)
   - Second latest: truncate to `maxOlderChars` (1500)
   - Older assistants: replace with structural summary (user request + tools + files + errors + outcome, ~500 chars)
4. Budget is determined by `ProviderContextBudget.forProvider(clientType)`

### Phase B: Within-Loop Compaction (existing)

No changes to strategies 1-3. They continue to handle within-loop growth.

### Safety Net: TEXT_TRUNCATION (already implemented)

The `ConversationCompactor.compact()` fallback that truncates assistant text when all strategies fail or produce results still over budget. This catches any edge case where Phase A + Phase B are insufficient.

**Critical fix (2026-04-06):** Phase 3 of `truncateToFitBudget()` previously used `removeAt(0)` to drop individual items, which could break `tool_call_id` pairing (e.g., removing an assistant message with `toolCalls` while keeping the corresponding `tool` result item). This caused Kimi to return `"Invalid request: tool_call_id is not found"` (HTTP 400), followed by rate-limiting (`engine_overloaded`, HTTP 429) from the accumulated failed requests. Fixed by dropping items by complete turn (USER + all subsequent ASSISTANT/TOOL items until the next USER) to preserve tool call integrity.

### Provider Budgets

Current budgets in `ProviderContextBudget`:

| Provider | Max Tokens | Recent Turns |
|----------|-----------|--------------|
| Anthropic | 80,000 | 5 |
| OpenAI | 60,000 | 5 |
| Google | 60,000 | 5 |
| Qwen | 40,000 | 4 |
| MiniMax | 40,000 | 4 |
| Kimi | 24,000 | 3 |
| Others | 24,000 | 3 |

Phase A uses `maxTokens * 0.6` as its budget for the initial conversation (reserving 40% for system prompt, tools, and current loop growth).

## Future Iteration Ideas

### Option 2: Unified Budget Allocation (if Option 1 proves insufficient)

Replace the turn-count-based system with weight-based budget allocation:

```
compact() flow:
  1. Calculate total budget (e.g., Kimi 24K tokens)
  2. Reserve system prompt + tool definitions (~40%)
  3. Allocate remaining budget by decaying weights:
     - Most recent turn: weight 4
     - Second recent: weight 2
     - Older turns: weight 1
  4. Within each turn, prioritize by information value:
     Delete view trees → build logs → file contents → reasoning → text
```

### Smarter Room Persistence

Instead of saving the full agent loop output as a single text blob, save a structured representation:
- Summary text (what was accomplished)
- File paths modified
- Build status
- Error messages
- Tool call sequence

This would make cross-turn compaction trivial because the data is already structured.

### Adaptive Budget Tuning

Monitor tool-calling success rate per provider and adjust budgets dynamically. If a provider stops calling tools at a certain context size, reduce the budget proactively.

### Provider-Specific Token Counting

Replace the character-based heuristic (`~4 chars/token Latin, ~2 chars/token CJK`) with provider-specific tokenizers for more accurate budget management.
