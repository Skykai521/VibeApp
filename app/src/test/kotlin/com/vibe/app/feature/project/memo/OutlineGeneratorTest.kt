package com.vibe.app.feature.project.memo

import com.vibe.app.feature.project.VibeProjectDirs
import com.vibe.app.feature.project.snapshot.Snapshot
import com.vibe.app.feature.project.snapshot.SnapshotHandle
import com.vibe.app.feature.project.snapshot.SnapshotManager
import com.vibe.app.feature.project.snapshot.SnapshotType
import com.vibe.app.feature.project.snapshot.RestoreResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OutlineGeneratorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `regenerate writes outline with package activities permissions and recent turns`() = runTest {
        val projectRoot = tmp.newFolder("projects", "p1")
        val workspace = File(projectRoot, "app").apply { mkdirs() }
        val dirs = VibeProjectDirs.fromWorkspaceRoot(workspace).also { it.ensureCreated() }

        File(workspace, "src/main/AndroidManifest.xml").apply {
            parentFile.mkdirs()
            writeText("""
                <manifest package="com.example.weather">
                    <uses-permission android:name="android.permission.INTERNET"/>
                    <application>
                        <activity android:name=".MainActivity"/>
                        <activity android:name=".ForecastActivity"/>
                    </application>
                </manifest>
            """.trimIndent())
        }
        File(workspace, "src/main/res/values/strings.xml").apply {
            parentFile.mkdirs()
            writeText("""<resources><string name="app_name">Weather</string><string name="hint_city">City</string></resources>""")
        }
        File(workspace, "src/main/java/com/example/weather/MainActivity.java").apply {
            parentFile.mkdirs()
            writeText("package com.example.weather; public class MainActivity {}")
        }

        val fakeSnapshotManager = FakeSnapshotManager(listOf(
            Snapshot(id = "s1", projectId = "p1", type = SnapshotType.TURN,
                createdAtEpochMs = 1, turnIndex = 1, label = "initial",
                parentSnapshotId = null, buildSucceeded = true,
                affectedFiles = listOf("MainActivity.java"), deletedFiles = emptyList()),
            Snapshot(id = "s2", projectId = "p1", type = SnapshotType.TURN,
                createdAtEpochMs = 2, turnIndex = 2, label = "add forecast",
                parentSnapshotId = null, buildSucceeded = true,
                affectedFiles = listOf("ForecastActivity.java", "activity_forecast.xml"),
                deletedFiles = emptyList()),
        ))
        val generator = OutlineGenerator(snapshotManager = fakeSnapshotManager)

        generator.regenerate("p1", workspace, dirs)

        assertTrue(dirs.outlineFile.exists())
        val outline = OutlineJson.decode(dirs.outlineFile.readText())
        assertEquals("Weather", outline.appName)
        assertEquals("com.example.weather", outline.packageName)
        assertEquals(listOf("MainActivity", "ForecastActivity"), outline.activities.map { it.name })
        assertEquals(listOf("android.permission.INTERNET"), outline.permissions)
        assertEquals(setOf("app_name", "hint_city"), outline.stringKeys.toSet())
        assertEquals(2, outline.recentTurns.size)
        assertEquals("add forecast", outline.recentTurns.last().userPrompt)
        assertEquals(2, outline.recentTurns.last().changedFiles)
        assertTrue(outline.fileCount >= 3)  // manifest + strings.xml + java file
    }

    @Test
    fun `regenerate handles missing manifest and strings gracefully`() = runTest {
        val projectRoot = tmp.newFolder("projects", "p1")
        val workspace = File(projectRoot, "app").apply { mkdirs() }
        val dirs = VibeProjectDirs.fromWorkspaceRoot(workspace).also { it.ensureCreated() }

        val generator = OutlineGenerator(snapshotManager = FakeSnapshotManager(emptyList()))
        generator.regenerate("p1", workspace, dirs)

        val outline = OutlineJson.decode(dirs.outlineFile.readText())
        assertEquals("", outline.packageName)
        assertEquals(emptyList<OutlineActivity>(), outline.activities)
        assertEquals(emptyList<String>(), outline.permissions)
        assertEquals(emptyList<String>(), outline.stringKeys)
        assertEquals(emptyList<OutlineRecentTurn>(), outline.recentTurns)
    }

    @Test
    fun `regenerate takes only last 3 TURN snapshots and ignores MANUAL`() = runTest {
        val projectRoot = tmp.newFolder("projects", "p1")
        val workspace = File(projectRoot, "app").apply { mkdirs() }
        val dirs = VibeProjectDirs.fromWorkspaceRoot(workspace).also { it.ensureCreated() }

        fun snap(id: String, type: SnapshotType, ts: Long, label: String) = Snapshot(
            id = id, projectId = "p1", type = type, createdAtEpochMs = ts,
            turnIndex = if (type == SnapshotType.TURN) ts.toInt() else null,
            label = label, parentSnapshotId = null, buildSucceeded = true,
        )

        val snapshots = listOf(
            snap("a", SnapshotType.TURN, 1, "t1"),
            snap("b", SnapshotType.TURN, 2, "t2"),
            snap("c", SnapshotType.MANUAL, 3, "manual save"),
            snap("d", SnapshotType.TURN, 4, "t4"),
            snap("e", SnapshotType.TURN, 5, "t5"),
        )
        val generator = OutlineGenerator(snapshotManager = FakeSnapshotManager(snapshots))
        generator.regenerate("p1", workspace, dirs)

        val outline = OutlineJson.decode(dirs.outlineFile.readText())
        // Last 3 TURN: t2, t4, t5 (MANUAL "c" excluded)
        assertEquals(listOf("t2", "t4", "t5"), outline.recentTurns.map { it.userPrompt })
    }
}

// Test helper — must implement ALL 6 SnapshotManager methods.
private class FakeSnapshotManager(private val entries: List<Snapshot>) : SnapshotManager {
    override suspend fun list(projectId: String, vibeDirs: VibeProjectDirs): List<Snapshot> = entries
    override suspend fun prepare(projectId: String, workspaceRoot: File, vibeDirs: VibeProjectDirs,
        type: SnapshotType, label: String, turnIndex: Int?): SnapshotHandle = error("not used")
    override suspend fun restore(snapshotId: String, projectId: String, workspaceRoot: File, vibeDirs: VibeProjectDirs, createBackup: Boolean): RestoreResult = error("not used")
    override suspend fun delete(snapshotId: String, projectId: String, vibeDirs: VibeProjectDirs) = Unit
    override suspend fun enforceRetention(projectId: String, vibeDirs: VibeProjectDirs, keepTurnCount: Int) = Unit
    override suspend fun recoverPendingRestore(projectId: String, workspaceRoot: File, vibeDirs: VibeProjectDirs) = Unit
}
