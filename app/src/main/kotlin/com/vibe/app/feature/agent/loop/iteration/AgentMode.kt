package com.vibe.app.feature.agent.loop.iteration

/**
 * Controls the agent loop's behavior for a given turn.
 *
 * - [GREENFIELD]: brand-new project or early-stage work. Agent uses the full
 *   system prompt and is free to rename, plan, and explore exhaustively.
 * - [ITERATE]: project has an intent.md file and at least one successful TURN
 *   snapshot. Agent sees a `<project-memo>` block prepended to the system
 *   prompt plus an iteration-mode appendix, and is nudged toward surgical
 *   edits rather than full re-exploration.
 */
enum class AgentMode { GREENFIELD, ITERATE }
