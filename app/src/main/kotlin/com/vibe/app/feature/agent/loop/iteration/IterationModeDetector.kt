package com.vibe.app.feature.agent.loop.iteration

import com.vibe.app.feature.project.VibeProjectDirs
import com.vibe.app.feature.project.memo.IntentStore
import com.vibe.app.feature.project.snapshot.SnapshotManager
import com.vibe.app.feature.project.snapshot.SnapshotType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides whether a turn runs in GREENFIELD or ITERATE mode. The rule is
 * deterministic: iterate iff (intent.md exists) AND (at least one TURN-type
 * snapshot with buildSucceeded=true exists for this project).
 *
 * Both conditions must hold together — intent alone isn't enough, and a
 * successful build without an intent also isn't enough. This prevents half-
 * built projects from being misclassified.
 */
@Singleton
class IterationModeDetector @Inject constructor(
    private val intentStore: IntentStore,
    private val snapshotManager: SnapshotManager,
) {
    suspend fun detect(projectId: String, vibeDirs: VibeProjectDirs): AgentMode {
        if (!intentStore.exists(vibeDirs)) return AgentMode.GREENFIELD
        val hasSuccessfulTurn = snapshotManager.list(projectId, vibeDirs).any {
            it.type == SnapshotType.TURN && it.buildSucceeded
        }
        return if (hasSuccessfulTurn) AgentMode.ITERATE else AgentMode.GREENFIELD
    }
}
