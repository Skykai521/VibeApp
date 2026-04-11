package com.vibe.app.feature.agent.loop.iteration

import com.vibe.app.feature.project.VibeProjectDirs
import com.vibe.app.feature.project.memo.Intent
import com.vibe.app.feature.project.memo.IntentStore
import com.vibe.app.feature.project.snapshot.RestoreResult
import com.vibe.app.feature.project.snapshot.Snapshot
import com.vibe.app.feature.project.snapshot.SnapshotHandle
import com.vibe.app.feature.project.snapshot.SnapshotManager
import com.vibe.app.feature.project.snapshot.SnapshotType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class IterationModeDetectorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newDirs(): VibeProjectDirs {
        val root = tmp.newFolder()
        return VibeProjectDirs.fromWorkspaceRoot(File(root, "app").apply { mkdirs() })
            .also { it.ensureCreated() }
    }

    private fun snap(type: SnapshotType, succeeded: Boolean): Snapshot = Snapshot(
        id = "s",
        projectId = "p1",
        type = type,
        createdAtEpochMs = 0,
        turnIndex = 1,
        label = "",
        parentSnapshotId = null,
        buildSucceeded = succeeded,
    )

    @Test
    fun `no intent no snapshot is GREENFIELD`() = runTest {
        val detector = IterationModeDetector(
            intentStore = FakeIntentStore(hasIntent = false),
            snapshotManager = FakeSnapManager(emptyList()),
        )
        assertEquals(AgentMode.GREENFIELD, detector.detect("p1", newDirs()))
    }

    @Test
    fun `no intent but successful TURN is still GREENFIELD`() = runTest {
        val detector = IterationModeDetector(
            intentStore = FakeIntentStore(hasIntent = false),
            snapshotManager = FakeSnapManager(listOf(snap(SnapshotType.TURN, true))),
        )
        assertEquals(AgentMode.GREENFIELD, detector.detect("p1", newDirs()))
    }

    @Test
    fun `intent without any snapshot is GREENFIELD`() = runTest {
        val detector = IterationModeDetector(
            intentStore = FakeIntentStore(hasIntent = true),
            snapshotManager = FakeSnapManager(emptyList()),
        )
        assertEquals(AgentMode.GREENFIELD, detector.detect("p1", newDirs()))
    }

    @Test
    fun `intent with only failed TURN is GREENFIELD`() = runTest {
        val detector = IterationModeDetector(
            intentStore = FakeIntentStore(hasIntent = true),
            snapshotManager = FakeSnapManager(listOf(snap(SnapshotType.TURN, false))),
        )
        assertEquals(AgentMode.GREENFIELD, detector.detect("p1", newDirs()))
    }

    @Test
    fun `intent with successful MANUAL snapshot is still GREENFIELD - must be TURN`() = runTest {
        val detector = IterationModeDetector(
            intentStore = FakeIntentStore(hasIntent = true),
            snapshotManager = FakeSnapManager(listOf(snap(SnapshotType.MANUAL, true))),
        )
        assertEquals(AgentMode.GREENFIELD, detector.detect("p1", newDirs()))
    }

    @Test
    fun `intent plus successful TURN is ITERATE`() = runTest {
        val detector = IterationModeDetector(
            intentStore = FakeIntentStore(hasIntent = true),
            snapshotManager = FakeSnapManager(listOf(snap(SnapshotType.TURN, true))),
        )
        assertEquals(AgentMode.ITERATE, detector.detect("p1", newDirs()))
    }

    @Test
    fun `intent plus mixed snapshots including a successful TURN is ITERATE`() = runTest {
        val detector = IterationModeDetector(
            intentStore = FakeIntentStore(hasIntent = true),
            snapshotManager = FakeSnapManager(listOf(
                snap(SnapshotType.TURN, false),
                snap(SnapshotType.MANUAL, true),
                snap(SnapshotType.TURN, true),
            )),
        )
        assertEquals(AgentMode.ITERATE, detector.detect("p1", newDirs()))
    }
}

// Test helpers — must implement all interface methods.

private class FakeIntentStore(private val hasIntent: Boolean) : IntentStore {
    override suspend fun exists(vibeDirs: VibeProjectDirs) = hasIntent
    override suspend fun load(vibeDirs: VibeProjectDirs): Intent? =
        if (hasIntent) Intent("", emptyList(), emptyList()) else null
    override suspend fun save(vibeDirs: VibeProjectDirs, intent: Intent, appName: String) = Unit
    override suspend fun loadRawMarkdown(vibeDirs: VibeProjectDirs): String? =
        if (hasIntent) "# x" else null
    override suspend fun saveRawMarkdown(vibeDirs: VibeProjectDirs, markdown: String) = Unit
}

private class FakeSnapManager(private val entries: List<Snapshot>) : SnapshotManager {
    override suspend fun list(projectId: String, vibeDirs: VibeProjectDirs) = entries
    override suspend fun prepare(
        projectId: String, workspaceRoot: File, vibeDirs: VibeProjectDirs,
        type: SnapshotType, label: String, turnIndex: Int?,
    ): SnapshotHandle = error("not used")
    override suspend fun restore(
        snapshotId: String, projectId: String, workspaceRoot: File, vibeDirs: VibeProjectDirs, createBackup: Boolean,
    ): RestoreResult = error("not used")
    override suspend fun delete(
        snapshotId: String, projectId: String, vibeDirs: VibeProjectDirs,
    ) = Unit
    override suspend fun enforceRetention(
        projectId: String, vibeDirs: VibeProjectDirs, keepTurnCount: Int,
    ) = Unit
    override suspend fun recoverPendingRestore(
        projectId: String, workspaceRoot: File, vibeDirs: VibeProjectDirs,
    ) = Unit
}
