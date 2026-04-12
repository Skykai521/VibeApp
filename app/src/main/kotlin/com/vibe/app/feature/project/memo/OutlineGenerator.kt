package com.vibe.app.feature.project.memo

import com.vibe.app.feature.project.VibeProjectDirs
import com.vibe.app.feature.project.snapshot.SnapshotManager
import com.vibe.app.feature.project.snapshot.SnapshotType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OutlineGenerator @Inject constructor(
    private val snapshotManager: SnapshotManager,
) {
    /**
     * Scans [workspaceRoot] and rebuilds `.vibe/memo/outline.json` from scratch.
     * Reads no code — only grep-level inspection of AndroidManifest.xml,
     * strings.xml, layout file names, plus `SnapshotManager.list()` for recent
     * turns. Zero LLM cost.
     */
    suspend fun regenerate(
        projectId: String,
        workspaceRoot: File,
        vibeDirs: VibeProjectDirs,
    ): Outline = withContext(Dispatchers.IO) {
        vibeDirs.ensureCreated()

        val manifestText = runCatching {
            File(workspaceRoot, "src/main/AndroidManifest.xml").readText()
        }.getOrElse { "" }

        val packageName = Regex("""package="([^"]+)"""")
            .find(manifestText)?.groupValues?.get(1) ?: ""

        val permissions = Regex("""android:name="(android\.permission\.[^"]+)"""")
            .findAll(manifestText).map { it.groupValues[1] }.distinct().toList()

        val activityNames = Regex("""<activity[^>]*android:name="\.?([\w.]+)"""")
            .findAll(manifestText)
            .map { it.groupValues[1].substringAfterLast('.') }
            .toList()

        val stringsText = runCatching {
            File(workspaceRoot, "src/main/res/values/strings.xml").readText()
        }.getOrElse { "" }

        val stringKeys = Regex("""<string\s+name="([^"]+)"""")
            .findAll(stringsText).map { it.groupValues[1] }.toList()

        val appName = Regex("""<string\s+name="app_name"[^>]*>([^<]+)""")
            .find(stringsText)?.groupValues?.get(1)
            ?: packageName.substringAfterLast('.')

        val activities = activityNames.map { name ->
            OutlineActivity(name = name, layout = inferLayoutName(name), purpose = null)
        }

        val recentTurns = snapshotManager.list(projectId, vibeDirs)
            .filter { it.type == SnapshotType.TURN }
            .takeLast(3)
            .map { s ->
                OutlineRecentTurn(
                    turnIndex = s.turnIndex ?: -1,
                    userPrompt = s.label,
                    changedFiles = s.affectedFiles.size,
                    buildOk = s.buildSucceeded,
                )
            }

        val fileCount = workspaceRoot.walkTopDown()
            .onEnter { it.name != "build" }
            .filter { it.isFile }
            .count()

        val outline = Outline(
            generatedAtEpochMs = System.currentTimeMillis(),
            appName = appName,
            packageName = packageName,
            activities = activities,
            fileCount = fileCount,
            permissions = permissions,
            stringKeys = stringKeys,
            recentTurns = recentTurns,
        )
        vibeDirs.outlineFile.writeText(OutlineJson.encode(outline))
        outline
    }

    /** "MainActivity" → "activity_main". Pure name derivation — no file check. */
    private fun inferLayoutName(activityName: String): String {
        val snake = activityName.removeSuffix("Activity")
            .replace(Regex("([a-z])([A-Z])"), "$1_$2")
            .lowercase()
        return "activity_$snake"
    }
}
