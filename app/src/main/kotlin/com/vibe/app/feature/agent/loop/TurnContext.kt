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
    /** Flipped to true by the Coordinator's write interceptor on the first
     *  file-mutating tool call in this turn, so snapshotHandle.commit() runs
     *  exactly once — and only when the turn actually changes workspace state. */
    @Volatile
    var firstWriteDone: Boolean = false

    /** Relative paths touched by this turn's write/edit tools, used as the
     *  snapshot entry's affectedFiles. Populated by the Coordinator after each
     *  successful write-tool result; read once in FINALIZE. */
    val writtenFiles: MutableSet<String> = mutableSetOf()

    /** Relative paths removed by this turn's delete tool, paired with
     *  writtenFiles for snapshot metadata. */
    val deletedFiles: MutableSet<String> = mutableSetOf()
}
