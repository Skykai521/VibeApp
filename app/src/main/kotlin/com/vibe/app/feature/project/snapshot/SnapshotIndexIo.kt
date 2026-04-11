package com.vibe.app.feature.project.snapshot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Atomic JSON read/write for the per-project snapshot index.
 * Writes go to `<name>.tmp` first and then rename over the target so a crash
 * cannot leave a half-written index file.
 *
 * Corrupt or missing files are treated as "empty index" — restore-from-disaster
 * over strict failure, since snapshots are an ergonomic feature, not source of truth.
 */
class SnapshotIndexIo {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun load(indexFile: File): SnapshotIndex = withContext(Dispatchers.IO) {
        if (!indexFile.exists()) return@withContext SnapshotIndex(entries = emptyList())
        runCatching { json.decodeFromString(SnapshotIndex.serializer(), indexFile.readText()) }
            .getOrElse { SnapshotIndex(entries = emptyList()) }
    }

    suspend fun save(indexFile: File, index: SnapshotIndex): Unit = withContext(Dispatchers.IO) {
        indexFile.parentFile?.mkdirs()
        val tmp = File(indexFile.parentFile, "${indexFile.name}.tmp")
        tmp.writeText(json.encodeToString(SnapshotIndex.serializer(), index))
        if (indexFile.exists()) indexFile.delete()
        check(tmp.renameTo(indexFile)) { "Failed to rename index tmp file" }
    }
}
