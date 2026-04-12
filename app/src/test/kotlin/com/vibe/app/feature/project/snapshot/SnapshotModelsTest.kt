package com.vibe.app.feature.project.snapshot

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class SnapshotModelsTest {

    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }

    @Test
    fun `Snapshot round-trips through JSON`() {
        val original = Snapshot(
            id = "snap_20260411_143012_a1b2",
            projectId = "proj_1",
            type = SnapshotType.TURN,
            createdAtEpochMs = Instant.parse("2026-04-11T14:30:12Z").toEpochMilli(),
            turnIndex = 3,
            label = "加 7 天预报",
            parentSnapshotId = "snap_20260411_142500_xyz",
            buildSucceeded = true,
            affectedFiles = listOf("app/src/main/java/.../Main.java"),
            deletedFiles = emptyList(),
        )
        val roundTripped = json.decodeFromString(
            Snapshot.serializer(),
            json.encodeToString(Snapshot.serializer(), original)
        )
        assertEquals(original, roundTripped)
    }

    @Test
    fun `SnapshotIndex empty list serializes`() {
        val index = SnapshotIndex(entries = emptyList())
        val text = json.encodeToString(SnapshotIndex.serializer(), index)
        assertEquals(SnapshotIndex(emptyList()), json.decodeFromString(SnapshotIndex.serializer(), text))
    }

    @Test
    fun `SnapshotManifest round-trips`() {
        val manifest = SnapshotManifest(
            snapshotId = "snap_1",
            files = listOf("src/Main.java", "res/values/strings.xml"),
        )
        val roundTripped = json.decodeFromString(
            SnapshotManifest.serializer(),
            json.encodeToString(SnapshotManifest.serializer(), manifest)
        )
        assertEquals(manifest, roundTripped)
    }
}
