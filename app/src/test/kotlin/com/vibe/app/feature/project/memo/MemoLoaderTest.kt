package com.vibe.app.feature.project.memo

import com.vibe.app.feature.project.VibeProjectDirs
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MemoLoaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newDirs(): VibeProjectDirs {
        val projectRoot = tmp.newFolder("projects", "p1")
        val ws = File(projectRoot, "app").apply { mkdirs() }
        return VibeProjectDirs.fromWorkspaceRoot(ws).also { it.ensureCreated() }
    }

    private val loader = MemoLoader(DefaultIntentStore())

    @Test
    fun `load returns null when neither intent nor outline exists`() = runTest {
        assertNull(loader.load(newDirs()))
    }

    @Test
    fun `load returns memo with only intent when outline missing`() = runTest {
        val dirs = newDirs()
        DefaultIntentStore().save(
            dirs,
            Intent("test app", emptyList(), emptyList()),
            "TestApp",
        )
        val memo = loader.load(dirs)!!
        assertTrue(memo.intent != null)
        assertTrue(memo.outline == null)
    }

    @Test
    fun `load returns memo with only outline when intent missing`() = runTest {
        val dirs = newDirs()
        dirs.outlineFile.writeText(OutlineJson.encode(sampleOutline()))
        val memo = loader.load(dirs)!!
        assertTrue(memo.outline != null)
        assertTrue(memo.intent == null)
    }

    @Test
    fun `assembleForPrompt produces project-memo block with intent and outline summary`() = runTest {
        val dirs = newDirs()
        DefaultIntentStore().save(
            dirs,
            Intent(
                purpose = "Weather app",
                keyDecisions = listOf("wttr.in"),
                knownLimits = listOf("no cache")
            ),
            appName = "Weather",
        )
        dirs.outlineFile.writeText(OutlineJson.encode(sampleOutline()))

        val memo = loader.load(dirs)!!
        val prompt = MemoLoader.assembleForPrompt(memo)

        assertTrue("starts with open tag", prompt.startsWith("<project-memo>"))
        assertTrue("ends with close tag", prompt.trimEnd().endsWith("</project-memo>"))
        assertTrue("contains Intent section", prompt.contains("## Intent"))
        assertTrue("contains purpose", prompt.contains("Weather app"))
        assertTrue("contains key decisions joined", prompt.contains("wttr.in"))
        assertTrue("contains known limits joined", prompt.contains("no cache"))
        assertTrue("contains Outline section", prompt.contains("## Outline"))
        assertTrue("contains activities", prompt.contains("MainActivity"))
        assertTrue("contains activity purpose when present", prompt.contains("主界面"))
        assertTrue("contains file count", prompt.contains("Files: 12"))
        assertTrue("contains permissions", prompt.contains("android.permission.INTERNET"))
        assertTrue("contains recent turns", prompt.contains("t1 init"))
    }

    @Test
    fun `assembleForPrompt with only intent skips Outline section`() {
        val memo = ProjectMemo(
            outline = null,
            intent = Intent("purpose only", emptyList(), emptyList()),
        )
        val prompt = MemoLoader.assembleForPrompt(memo)
        assertTrue(prompt.contains("## Intent"))
        assertTrue(!prompt.contains("## Outline"))
    }

    @Test
    fun `assembleForPrompt with only outline skips Intent section`() {
        val memo = ProjectMemo(outline = sampleOutline(), intent = null)
        val prompt = MemoLoader.assembleForPrompt(memo)
        assertTrue(!prompt.contains("## Intent"))
        assertTrue(prompt.contains("## Outline"))
    }

    private fun sampleOutline() = Outline(
        generatedAtEpochMs = 0,
        appName = "Weather",
        packageName = "com.example.weather",
        activities = listOf(OutlineActivity("MainActivity", "activity_main", "主界面")),
        fileCount = 12,
        permissions = listOf("android.permission.INTERNET"),
        stringKeys = listOf("app_name"),
        recentTurns = listOf(
            OutlineRecentTurn(turnIndex = 1, userPrompt = "init", changedFiles = 5, buildOk = true),
        ),
    )
}
