# VibeApp Project Memory

## Architecture
- Android app generating/compiling/installing APKs on-device
- Language: Kotlin (app), Java (generated code)
- Build: Jetpack Compose + MVVM + Hilt + Room + DataStore + Coroutines/Flow

## Agent Loop (feature/agent/)
- `AgentModels.kt`: data classes — `AgentConversationItem` (has `toolCalls` for ASSISTANT turns), `AgentLoopRequest`, `AgentToolCall`, etc.
- `AgentContracts.kt`: interfaces — `AgentModelGateway`, `AgentLoopCoordinator`, `AgentToolRegistry`
  - `AgentModelRequest` has TWO conversation fields:
    - `conversation`: delta only (for stateful OpenAI Responses API)
    - `fullConversation`: full accumulated history (for stateless Anthropic Messages API)
- `DefaultAgentLoopCoordinator`: maintains both delta and full history each iteration
- `ProviderAgentGatewayRouter`: routes to OpenAI or Anthropic gateway based on `platform.compatibleType`
- `OpenAiResponsesAgentGateway`: uses `previousResponseId` + `conversation` (delta)
- `AnthropicMessagesAgentGateway`: uses `fullConversation`; accumulates `input_json_delta` per block index; groups TOOL items into single user message with `tool_result` blocks

## Anthropic DTOs
- Request: `AnthropicTool`, `AnthropicToolChoice` in `data/dto/anthropic/request/ToolRequest.kt`
- Common: `ToolUseContent`, `ToolResultContent` extend `MessageContent` sealed class
- `MessageRequest` has `tools` and `toolChoice` fields
- `ContentBlockType` includes `TOOL_USE` and `INPUT_JSON_DELTA`
- `ContentBlock` has `id`, `name`, `partialJson` for tool_use/delta blocks

## Key Patterns
- `ClientType.ANTHROPIC` → AnthropicMessagesAgentGateway; everything else → OpenAI
- Tool calling: serial execution (no parallel), max 8 iterations default
- 3 tools: `read_project_file`, `write_project_file`, `run_build_pipeline`
- Agent mode: single-platform only; multi-platform chat still uses old parallel text flow
