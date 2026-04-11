package com.vibe.app.feature.project.snapshot

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SnapshotStorageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val storage = SnapshotStorage()

    @Test
    fun `dumpWorkspace copies all files under workspace root excluding build dir`() = runTest {
        val workspace = tmp.newFolder("app")
        File(workspace, "src/main/java/Main.java").apply {
            parentFile.mkdirs(); writeText("class Main {}")
        }
        File(workspace, "src/main/AndroidManifest.xml").writeText("<manifest/>")
        // build/ should be excluded
        File(workspace, "build/intermediates/foo.class").apply {
            parentFile.mkdirs(); writeText("junk")
        }

        val snapshotDir = tmp.newFolder("snap_1")
        val manifest = storage.dumpWorkspace(
            snapshotId = "snap_1",
            workspaceRoot = workspace,
            snapshotDir = snapshotDir,
        )

        assertEquals(
            setOf("src/main/java/Main.java", "src/main/AndroidManifest.xml"),
            manifest.files.toSet()
        )
        assertTrue(File(snapshotDir, "files/src/main/java/Main.java").exists())
        assertFalse(File(snapshotDir, "files/build/intermediates/foo.class").exists())
        assertEquals(
            "class Main {}",
            File(snapshotDir, "files/src/main/java/Main.java").readText()
        )
    }

    @Test
    fun `restoreSnapshot overwrites workspace and deletes out-of-snapshot files`() = runTest {
        val workspace = tmp.newFolder("app")
        val snapshotDir = tmp.newFolder("snap_1")

        // snapshot captured earlier: one file
        File(snapshotDir, "files/src/main/java/Main.java").apply {
            parentFile.mkdirs(); writeText("class Main { /* old */ }")
        }
        val manifest = SnapshotManifest(
            snapshotId = "snap_1",
            files = listOf("src/main/java/Main.java"),
        )

        // workspace has been modified after snapshot: Main.java edited + Extra.java added
        File(workspace, "src/main/java/Main.java").apply {
            parentFile.mkdirs(); writeText("class Main { /* new */ }")
        }
        File(workspace, "src/main/java/Extra.java").writeText("class Extra {}")

        val result = storage.restoreSnapshot(
            manifest = manifest,
            snapshotDir = snapshotDir,
            workspaceRoot = workspace,
        )

        assertEquals(
            "class Main { /* old */ }",
            File(workspace, "src/main/java/Main.java").readText()
        )
        assertFalse(File(workspace, "src/main/java/Extra.java").exists())
        assertEquals(listOf("src/main/java/Main.java"), result.restoredFiles)
        assertEquals(listOf("src/main/java/Extra.java"), result.deletedFiles)
    }

    @Test
    fun `restoreSnapshot ignores build dir when computing files to delete`() = runTest {
        val workspace = tmp.newFolder("app")
        val snapshotDir = tmp.newFolder("snap_1")

        File(snapshotDir, "files/src/Main.java").apply {
            parentFile.mkdirs(); writeText("// snapshot")
        }
        val manifest = SnapshotManifest("snap_1", listOf("src/Main.java"))

        File(workspace, "build/intermediates/foo.class").apply {
            parentFile.mkdirs(); writeText("stale")
        }

        val result = storage.restoreSnapshot(manifest, snapshotDir, workspace)

        assertTrue(File(workspace, "build/intermediates/foo.class").exists())
        assertTrue(result.deletedFiles.none { it.startsWith("build/") })
    }
}
