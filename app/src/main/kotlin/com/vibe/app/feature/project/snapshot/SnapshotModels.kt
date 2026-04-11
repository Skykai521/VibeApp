package com.vibe.app.feature.project.snapshot

import kotlinx.serialization.Serializable

@Serializable
data class Snapshot(
    val id: String,
    val projectId: String,
    val type: SnapshotType,
    val createdAtEpochMs: Long,
    val turnIndex: Int? = null,
    val label: String,
    val parentSnapshotId: String? = null,
    val buildSucceeded: Boolean,
    val affectedFiles: List<String> = emptyList(),
    val deletedFiles: List<String> = emptyList(),
)

@Serializable
enum class SnapshotType { TURN, MANUAL }

@Serializable
data class SnapshotIndex(
    val entries: List<Snapshot>,
)

/**
 * In-memory result of a restore operation, returned by SnapshotManager.restore().
 * Not serialized — lives only in memory for UI / logging. No @Serializable.
 */
data class RestoreResult(
    val restoredFiles: List<String>,
    val deletedFiles: List<String>,
    val backupSnapshotId: String,
)

/**
 * Manifest for a single snapshot's file set. Stored at
 * `.vibe/snapshots/{snapshotId}/manifest.json`. Lists every workspace file
 * the snapshot contains — restoring dumps exactly this set and deletes any
 * workspace file not in it.
 */
@Serializable
data class SnapshotManifest(
    val snapshotId: String,
    val files: List<String>,
)
