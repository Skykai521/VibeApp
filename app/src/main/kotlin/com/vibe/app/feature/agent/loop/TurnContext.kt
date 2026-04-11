package com.vibe.app.feature.agent.loop

import com.vibe.app.feature.agent.loop.iteration.AgentMode
import com.vibe.app.feature.project.VibeProjectDirs
import com.vibe.app.feature.project.snapshot.SnapshotHandle
import java.io.File

/**
 * Per-turn mutable state, held by the Coordinator for the duration of one
 * agent loop run. Carries everything the turn lifecycle needs that isn't
 * part of the base AgentLoopRequest — most importantly the lazy-commit
 * SnapshotHandle that turns the first file write into an on-disk snapshot.
 *
 * For turns that have no projectId (utility calls, diagnostic), TurnContext
 * is null — the Coordinator skips PREPARE/FINALIZE entirely in that case.
 */
class TurnContext(
    val projectId: String,
    val workspaceRoot: File,
    val vibeDirs: VibeProjectDirs,
    val mode: AgentMode,
    val snapshotHandle: SnapshotHandle,
    val turnIndex: Int,
) {
    /** Set by Task 5.2 WriteInterceptor on first write-tool call in this turn. */
    @Volatile
    var firstWriteDone: Boolean = false

    /** Task 5.2 will populate these as write tools execute. Empty in Task 5.1. */
    val writtenFiles: MutableSet<String> = mutableSetOf()
    val deletedFiles: MutableSet<String> = mutableSetOf()
}
