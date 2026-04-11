package com.vibe.app.feature.project.snapshot

import com.vibe.app.feature.project.VibeProjectDirs
import java.io.File

interface SnapshotManager {
    suspend fun prepare(
        projectId: String,
        workspaceRoot: File,
        vibeDirs: VibeProjectDirs,
        type: SnapshotType,
        label: String,
        turnIndex: Int?,
    ): SnapshotHandle

    suspend fun list(projectId: String, vibeDirs: VibeProjectDirs): List<Snapshot>

    suspend fun restore(
        snapshotId: String,
        projectId: String,
        workspaceRoot: File,
        vibeDirs: VibeProjectDirs,
    ): RestoreResult

    suspend fun delete(
        snapshotId: String,
        projectId: String,
        vibeDirs: VibeProjectDirs,
    )

    suspend fun enforceRetention(
        projectId: String,
        vibeDirs: VibeProjectDirs,
        keepTurnCount: Int = 20,
    )
}

/**
 * Per-turn lazy-commit primitive. `prepare()` returns a handle without any disk I/O.
 * The handle only dumps the workspace on [commit], and only appends to the index
 * on [finalize]. If neither is called (e.g. a read-only turn), the handle leaves
 * no trace.
 */
interface SnapshotHandle {
    suspend fun commit()
    suspend fun finalize(
        buildSucceeded: Boolean,
        affectedFiles: List<String>,
        deletedFiles: List<String>,
    )
}

interface Clock {
    fun nowEpochMs(): Long
}

interface SnapshotIdGenerator {
    fun generate(): String
}
