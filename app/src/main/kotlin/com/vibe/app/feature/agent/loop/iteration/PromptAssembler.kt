package com.vibe.app.feature.agent.loop.iteration

import com.vibe.app.feature.project.memo.MemoLoader
import com.vibe.app.feature.project.memo.ProjectMemo

/**
 * Splices a base system prompt, an optional project memo, and an iteration-mode
 * appendix into the text sent to the model at the start of a turn.
 *
 * - GREENFIELD: returns the base prompt unchanged (memo is ignored even if provided).
 * - ITERATE: `<project-memo>` block (if memo is non-null) + base prompt + appendix.
 *
 * Pure function — no I/O. The Coordinator loads the two asset files and calls this.
 */
object PromptAssembler {
    fun assemble(
        basePrompt: String,
        iterationAppendix: String,
        mode: AgentMode,
        memo: ProjectMemo?,
    ): String = buildString {
        if (mode == AgentMode.ITERATE && memo != null) {
            append(MemoLoader.assembleForPrompt(memo))
            append("\n\n")
        }
        append(basePrompt)
        if (mode == AgentMode.ITERATE) {
            append("\n\n")
            append(iterationAppendix)
        }
    }
}
