package com.vibe.app.feature.project.memo

import com.vibe.app.feature.project.VibeProjectDirs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads both memo layers from disk and assembles the compact `<project-memo>`
 * text block that gets injected into the agent's system prompt in iteration mode.
 * Assembly is aggressively compressed — ~350 tokens total — so the agent has
 * "starting context" without paying to re-read the whole project every turn.
 */
@Singleton
class MemoLoader @Inject constructor(
    private val intentStore: IntentStore,
) {
    suspend fun load(vibeDirs: VibeProjectDirs): ProjectMemo? = withContext(Dispatchers.IO) {
        val intent = intentStore.load(vibeDirs)
        val outline = if (vibeDirs.outlineFile.exists()) {
            runCatching { OutlineJson.decode(vibeDirs.outlineFile.readText()) }.getOrNull()
        } else null
        if (intent == null && outline == null) null else ProjectMemo(outline, intent)
    }

    companion object {
        /**
         * Formats a memo as the text block injected into the agent system prompt.
         * Pure function — no I/O — so it's trivially testable and safe to call
         * from any thread.
         */
        fun assembleForPrompt(memo: ProjectMemo): String = buildString {
            append("<project-memo>\n")
            memo.intent?.let { intent ->
                append("## Intent\n")
                append("Purpose: ").append(intent.purpose).append('\n')
                if (intent.keyDecisions.isNotEmpty()) {
                    append("Key Decisions: ")
                    append(intent.keyDecisions.joinToString("; "))
                    append('\n')
                }
                if (intent.knownLimits.isNotEmpty()) {
                    append("Known Limits: ")
                    append(intent.knownLimits.joinToString("; "))
                    append('\n')
                }
                append('\n')
            }
            memo.outline?.let { outline ->
                append("## Outline\n")
                append("- Activities: ")
                append(outline.activities.joinToString(", ") { act ->
                    if (act.purpose != null) "${act.name} (${act.purpose})" else act.name
                })
                append('\n')
                append("- Files: ${outline.fileCount}\n")
                if (outline.permissions.isNotEmpty()) {
                    append("- Permissions: ${outline.permissions.joinToString(", ")}\n")
                }
                if (outline.recentTurns.isNotEmpty()) {
                    append("- Recent: ")
                    append(outline.recentTurns.joinToString("; ") { "t${it.turnIndex} ${it.userPrompt}" })
                    append('\n')
                }
            }
            append("</project-memo>")
        }
    }
}
