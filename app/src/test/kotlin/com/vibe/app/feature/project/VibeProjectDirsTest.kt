package com.vibe.app.feature.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VibeProjectDirsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `vibeDir is sibling of workspace rootDir`() {
        val projectRoot = tmp.newFolder("projects", "proj_1")
        val appDir = File(projectRoot, "app").apply { mkdirs() }

        val dirs = VibeProjectDirs.fromWorkspaceRoot(appDir)

        assertEquals(File(projectRoot, ".vibe"), dirs.vibeDir)
        assertEquals(File(projectRoot, ".vibe/snapshots"), dirs.snapshotsDir)
        assertEquals(File(projectRoot, ".vibe/memo"), dirs.memoDir)
        assertEquals(File(projectRoot, ".vibe/memo/outline.json"), dirs.outlineFile)
        assertEquals(File(projectRoot, ".vibe/memo/intent.md"), dirs.intentFile)
        assertEquals(File(projectRoot, ".vibe/snapshots/index.json"), dirs.snapshotIndexFile)
        assertEquals(File(projectRoot, ".vibe/snapshots/.pending_restore"), dirs.pendingRestoreMarker)
    }

    @Test
    fun `ensureCreated creates all directories`() {
        val projectRoot = tmp.newFolder("projects", "proj_1")
        val appDir = File(projectRoot, "app").apply { mkdirs() }

        val dirs = VibeProjectDirs.fromWorkspaceRoot(appDir).also { it.ensureCreated() }

        assertTrue(dirs.vibeDir.isDirectory)
        assertTrue(dirs.snapshotsDir.isDirectory)
        assertTrue(dirs.memoDir.isDirectory)
    }
}
