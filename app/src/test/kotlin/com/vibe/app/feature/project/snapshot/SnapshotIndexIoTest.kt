package com.vibe.app.feature.project.snapshot

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SnapshotIndexIoTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val io = SnapshotIndexIo()

    private fun sample(id: String, turn: Int): Snapshot = Snapshot(
        id = id,
        projectId = "p1",
        type = SnapshotType.TURN,
        createdAtEpochMs = 1_700_000_000_000L + turn,
        turnIndex = turn,
        label = "t$turn",
        parentSnapshotId = null,
        buildSucceeded = true,
    )

    @Test
    fun `load returns empty when file does not exist`() = runTest {
        val indexFile = File(tmp.newFolder(), "index.json")
        assertEquals(emptyList<Snapshot>(), io.load(indexFile).entries)
    }

    @Test
    fun `save then load round-trips`() = runTest {
        val indexFile = File(tmp.newFolder(), "index.json")
        val original = SnapshotIndex(entries = listOf(sample("a", 1), sample("b", 2)))
        io.save(indexFile, original)
        assertEquals(original, io.load(indexFile))
    }

    @Test
    fun `save leaves no stray tmp file on success`() = runTest {
        val dir = tmp.newFolder()
        val indexFile = File(dir, "index.json")
        io.save(indexFile, SnapshotIndex(entries = listOf(sample("a", 1))))
        assertEquals(listOf("index.json"), dir.list()!!.toList())
    }

    @Test
    fun `load returns empty when file is corrupt JSON`() = runTest {
        val indexFile = File(tmp.newFolder(), "index.json")
        indexFile.writeText("{{{ not json")
        assertEquals(emptyList<Snapshot>(), io.load(indexFile).entries)
    }
}
