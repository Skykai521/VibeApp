package com.vibe.app.feature.project.snapshot

import com.vibe.app.feature.project.VibeProjectDirs
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DefaultSnapshotManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var workspaceRoot: File
    private lateinit var dirs: VibeProjectDirs
    private lateinit var manager: DefaultSnapshotManager

    @Before
    fun setup() {
        val projectRoot = tmp.newFolder("projects", "p1")
        workspaceRoot = File(projectRoot, "app").apply { mkdirs() }
        dirs = VibeProjectDirs.fromWorkspaceRoot(workspaceRoot).also { it.ensureCreated() }
        File(workspaceRoot, "src/Main.java").apply { parentFile.mkdirs(); writeText("v1") }

        manager = DefaultSnapshotManager(
            storage = SnapshotStorage(),
            indexIo = SnapshotIndexIo(),
            clock = FixedClock(1_700_000_000_000L),
            idGenerator = IncrementingIdGenerator(),
        )
    }

    @Test
    fun `prepare returns handle without writing files`() = runTest {
        val handle = manager.prepare(
            projectId = "p1",
            workspaceRoot = workspaceRoot,
            vibeDirs = dirs,
            type = SnapshotType.TURN,
            label = "add feature",
            turnIndex = 1,
        )
        assertNotNull(handle)
        // snapshotsDir should only have the index file (if any) — no snapshot subdirs
        val snapshotSubdirs = dirs.snapshotsDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        assertEquals(0, snapshotSubdirs.size)
    }

    @Test
    fun `commit dumps workspace then finalize updates index`() = runTest {
        val handle = manager.prepare("p1", workspaceRoot, dirs, SnapshotType.TURN, "t1", 1)
        handle.commit()
        handle.finalize(buildSucceeded = true, affectedFiles = listOf("src/Main.java"), deletedFiles = emptyList())

        val list = manager.list("p1", dirs)
        assertEquals(1, list.size)
        assertEquals("t1", list[0].label)
        assertTrue(list[0].buildSucceeded)
        assertTrue(File(dirs.snapshotsDir, "${list[0].id}/files/src/Main.java").exists())
    }

    @Test
    fun `uncommitted handle finalize is a no-op — no index entry`() = runTest {
        val handle = manager.prepare("p1", workspaceRoot, dirs, SnapshotType.TURN, "t1", 1)
        handle.finalize(buildSucceeded = true, affectedFiles = emptyList(), deletedFiles = emptyList())
        assertEquals(0, manager.list("p1", dirs).size)
    }

    @Test
    fun `enforceRetention keeps most recent N TURN and all MANUAL`() = runTest {
        repeat(5) { i ->
            val h = manager.prepare("p1", workspaceRoot, dirs, SnapshotType.TURN, "t$i", i)
            h.commit(); h.finalize(true, listOf("src/Main.java"), emptyList())
        }
        val manualHandle = manager.prepare("p1", workspaceRoot, dirs, SnapshotType.MANUAL, "important", null)
        manualHandle.commit(); manualHandle.finalize(true, listOf("src/Main.java"), emptyList())

        manager.enforceRetention("p1", dirs, keepTurnCount = 3)

        val remaining = manager.list("p1", dirs)
        val turns = remaining.filter { it.type == SnapshotType.TURN }
        val manuals = remaining.filter { it.type == SnapshotType.MANUAL }
        assertEquals(3, turns.size)
        assertEquals(1, manuals.size)
        // list() is sorted by createdAtEpochMs ascending, so the 3 kept turns are t2, t3, t4
        assertEquals(listOf("t2", "t3", "t4"), turns.map { it.label })
    }

    @Test
    fun `restore creates backup MANUAL snapshot and restores files`() = runTest {
        val h1 = manager.prepare("p1", workspaceRoot, dirs, SnapshotType.TURN, "t1", 1)
        h1.commit(); h1.finalize(true, listOf("src/Main.java"), emptyList())
        val turn1Id = manager.list("p1", dirs)[0].id

        // Modify to v2 and snapshot as t2
        File(workspaceRoot, "src/Main.java").writeText("v2")
        val h2 = manager.prepare("p1", workspaceRoot, dirs, SnapshotType.TURN, "t2", 2)
        h2.commit(); h2.finalize(true, listOf("src/Main.java"), emptyList())

        // Restore to turn 1
        val result = manager.restore(turn1Id, "p1", workspaceRoot, dirs)

        assertEquals("v1", File(workspaceRoot, "src/Main.java").readText())
        val all = manager.list("p1", dirs)
        val backup = all.find { it.id == result.backupSnapshotId }
        assertNotNull(backup)
        assertEquals(SnapshotType.MANUAL, backup!!.type)
    }

    @Test
    fun `recoverPendingRestore replays an interrupted restore`() = runTest {
        // Turn 1: v1 (already written by setup())
        val h1 = manager.prepare("p1", workspaceRoot, dirs, SnapshotType.TURN, "t1", 1)
        h1.commit(); h1.finalize(true, listOf("src/Main.java"), emptyList())
        val turn1Id = manager.list("p1", dirs)[0].id

        // Simulate a mid-restore crash: workspace partly modified, marker still present.
        File(workspaceRoot, "src/Main.java").writeText("partial")
        dirs.pendingRestoreMarker.writeText(turn1Id)

        manager.recoverPendingRestore("p1", workspaceRoot, dirs)

        assertEquals("v1", File(workspaceRoot, "src/Main.java").readText())
        assertFalse(dirs.pendingRestoreMarker.exists())
    }

    @Test
    fun `recoverPendingRestore with no marker is a no-op`() = runTest {
        // Write a file to the workspace and confirm it's untouched after recovery.
        File(workspaceRoot, "src/Main.java").writeText("untouched")
        manager.recoverPendingRestore("p1", workspaceRoot, dirs)
        assertEquals("untouched", File(workspaceRoot, "src/Main.java").readText())
        assertFalse(dirs.pendingRestoreMarker.exists())
    }

    @Test
    fun `recoverPendingRestore with stale marker pointing at missing snapshot deletes marker`() = runTest {
        dirs.pendingRestoreMarker.writeText("snap_nonexistent")
        manager.recoverPendingRestore("p1", workspaceRoot, dirs)
        assertFalse(dirs.pendingRestoreMarker.exists())
    }
}

// Test helpers — kept in the same file so the test is self-contained.
private class FixedClock(private var nowMs: Long) : Clock {
    override fun nowEpochMs(): Long = nowMs.also { nowMs += 1 }
}

private class IncrementingIdGenerator : SnapshotIdGenerator {
    private var counter = 0
    override fun generate(): String = "snap_${counter++}"
}
