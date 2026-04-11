package com.vibe.app.feature.project.snapshot

import com.vibe.app.feature.project.VibeProjectDirs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultSnapshotManager @Inject constructor(
    private val storage: SnapshotStorage,
    private val indexIo: SnapshotIndexIo,
    private val clock: Clock = SystemClock,
    private val idGenerator: SnapshotIdGenerator = RandomSnapshotIdGenerator,
) : SnapshotManager {

    private val indexMutex = Mutex()
    private val manifestJson = Json { prettyPrint = false; ignoreUnknownKeys = true }

    override suspend fun prepare(
        projectId: String,
        workspaceRoot: File,
        vibeDirs: VibeProjectDirs,
        type: SnapshotType,
        label: String,
        turnIndex: Int?,
    ): SnapshotHandle {
        vibeDirs.ensureCreated()
        val id = idGenerator.generate()
        return DefaultSnapshotHandle(
            id = id,
            projectId = projectId,
            type = type,
            label = label.take(LABEL_MAX),
            turnIndex = turnIndex,
            workspaceRoot = workspaceRoot,
            vibeDirs = vibeDirs,
            createdAt = clock.nowEpochMs(),
        )
    }

    override suspend fun list(projectId: String, vibeDirs: VibeProjectDirs): List<Snapshot> =
        indexMutex.withLock {
            indexIo.load(vibeDirs.snapshotIndexFile).entries
                .filter { it.projectId == projectId }
                .sortedBy { it.createdAtEpochMs }
        }

    override suspend fun restore(
        snapshotId: String,
        projectId: String,
        workspaceRoot: File,
        vibeDirs: VibeProjectDirs,
    ): RestoreResult = withContext(Dispatchers.IO) {
        // 1. Take a backup MANUAL snapshot of current state before destroying it.
        val backup = prepare(
            projectId = projectId,
            workspaceRoot = workspaceRoot,
            vibeDirs = vibeDirs,
            type = SnapshotType.MANUAL,
            label = "Before restore",
            turnIndex = null,
        )
        backup.commit()
        val currentFiles = workspaceRoot.walkTopDown()
            .onEnter { it.name != "build" }
            .filter { it.isFile }
            .map { it.toRelativeString(workspaceRoot) }
            .toList()
        backup.finalize(buildSucceeded = true, affectedFiles = currentFiles, deletedFiles = emptyList())
        val backupId = (backup as DefaultSnapshotHandle).id

        // 2. Mark pending, restore, clear marker (crash-safe: recovered on next startup).
        vibeDirs.pendingRestoreMarker.writeText(snapshotId)
        val snapDir = File(vibeDirs.snapshotsDir, snapshotId)
        val manifestFile = File(snapDir, "manifest.json")
        val manifest = manifestJson.decodeFromString(
            SnapshotManifest.serializer(),
            manifestFile.readText()
        )
        val diff = storage.restoreSnapshot(manifest, snapDir, workspaceRoot)
        vibeDirs.pendingRestoreMarker.delete()

        RestoreResult(
            restoredFiles = diff.restoredFiles,
            deletedFiles = diff.deletedFiles,
            backupSnapshotId = backupId,
        )
    }

    override suspend fun delete(
        snapshotId: String,
        projectId: String,
        vibeDirs: VibeProjectDirs,
    ) {
        indexMutex.withLock {
            val current = indexIo.load(vibeDirs.snapshotIndexFile)
            val updated = SnapshotIndex(current.entries.filterNot { it.id == snapshotId })
            indexIo.save(vibeDirs.snapshotIndexFile, updated)
            File(vibeDirs.snapshotsDir, snapshotId).deleteRecursively()
        }
    }

    override suspend fun enforceRetention(
        projectId: String,
        vibeDirs: VibeProjectDirs,
        keepTurnCount: Int,
    ) {
        indexMutex.withLock {
            val current = indexIo.load(vibeDirs.snapshotIndexFile)
            // Keep entries belonging to other projects untouched
            val otherProjects = current.entries.filter { it.projectId != projectId }
            val thisProject = current.entries.filter { it.projectId == projectId }
            val turns = thisProject.filter { it.type == SnapshotType.TURN }
                .sortedBy { it.createdAtEpochMs }
            val manuals = thisProject.filter { it.type == SnapshotType.MANUAL }
            val toDrop = if (turns.size > keepTurnCount) {
                turns.subList(0, turns.size - keepTurnCount)
            } else emptyList()
            toDrop.forEach { File(vibeDirs.snapshotsDir, it.id).deleteRecursively() }
            val keptTurns = turns - toDrop.toSet()
            indexIo.save(
                vibeDirs.snapshotIndexFile,
                SnapshotIndex(otherProjects + manuals + keptTurns)
            )
        }
    }

    internal suspend fun appendToIndex(entry: Snapshot, vibeDirs: VibeProjectDirs) {
        indexMutex.withLock {
            val current = indexIo.load(vibeDirs.snapshotIndexFile)
            indexIo.save(vibeDirs.snapshotIndexFile, SnapshotIndex(current.entries + entry))
        }
    }

    private inner class DefaultSnapshotHandle(
        val id: String,
        val projectId: String,
        val type: SnapshotType,
        val label: String,
        val turnIndex: Int?,
        val workspaceRoot: File,
        val vibeDirs: VibeProjectDirs,
        val createdAt: Long,
    ) : SnapshotHandle {
        private var committed = false

        override suspend fun commit() {
            if (committed) return
            val snapDir = File(vibeDirs.snapshotsDir, id).apply { mkdirs() }
            val dumped = storage.dumpWorkspace(id, workspaceRoot, snapDir)
            File(snapDir, "manifest.json").writeText(
                manifestJson.encodeToString(SnapshotManifest.serializer(), dumped)
            )
            committed = true
        }

        override suspend fun finalize(
            buildSucceeded: Boolean,
            affectedFiles: List<String>,
            deletedFiles: List<String>,
        ) {
            if (!committed) return  // abandoned — no on-disk trace, no index entry
            appendToIndex(
                Snapshot(
                    id = id,
                    projectId = projectId,
                    type = type,
                    createdAtEpochMs = createdAt,
                    turnIndex = turnIndex,
                    label = label,
                    parentSnapshotId = null,
                    buildSucceeded = buildSucceeded,
                    affectedFiles = affectedFiles,
                    deletedFiles = deletedFiles,
                ),
                vibeDirs,
            )
        }
    }

    private companion object {
        const val LABEL_MAX = 40
    }
}

object SystemClock : Clock {
    override fun nowEpochMs(): Long = System.currentTimeMillis()
}

object RandomSnapshotIdGenerator : SnapshotIdGenerator {
    override fun generate(): String {
        val ts = System.currentTimeMillis()
        val suffix = UUID.randomUUID().toString().take(4)
        return "snap_${ts}_$suffix"
    }
}
