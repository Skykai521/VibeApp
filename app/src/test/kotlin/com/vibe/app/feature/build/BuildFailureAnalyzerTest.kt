package com.vibe.app.feature.build

import com.vibe.build.engine.model.BuildLogEntry
import com.vibe.build.engine.model.BuildLogLevel
import com.vibe.build.engine.model.BuildResult
import com.vibe.build.engine.model.BuildStage
import com.vibe.build.engine.model.BuildStatus
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BuildFailureAnalyzerTest {

    private lateinit var root: File
    private val analyzer = BuildFailureAnalyzer()

    @Before
    fun setUp() {
        root = Files.createTempDirectory("build-failure-analyzer").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `compile cannot find symbol reports category hint and relative path`() {
        val source = File(root, "src/main/java/com/demo/MainActivity.java").apply {
            parentFile?.mkdirs()
            writeText("class MainActivity {}")
        }
        val result = failedResult(
            BuildLogEntry(
                stage = BuildStage.COMPILE,
                level = BuildLogLevel.ERROR,
                message = """
                    cannot find symbol
                      symbol:   class MaterialSwitch
                      location: class MainActivity
                """.trimIndent(),
                sourcePath = source.absolutePath,
                line = 42,
            ),
        )

        val analysis = analyzer.analyze(result, root)

        assertNotNull(analysis)
        val primary = analysis!!.primaryErrors.single()
        assertEquals("java_cannot_find_symbol", primary.category)
        assertEquals("src/main/java/com/demo/MainActivity.java", primary.sourcePath)
        assertEquals(42, primary.line)
        assertEquals("MaterialSwitch", primary.symbol)
        assertTrue(primary.hint.orEmpty().contains("does not include MaterialSwitch"))
        assertEquals("src/main/java/com/demo/MainActivity.java", analysis.suggestedReads.single().path)
        assertEquals(32, analysis.suggestedReads.single().startLine)
        assertEquals(52, analysis.suggestedReads.single().endLine)
    }

    @Test
    fun `resource theme violation maps to style category`() {
        val source = File(root, "src/main/res/values/themes.xml").apply {
            parentFile?.mkdirs()
            writeText("<resources />")
        }
        val result = failedResult(
            BuildLogEntry(
                stage = BuildStage.RESOURCE,
                level = BuildLogLevel.ERROR,
                message = "error: resource style/Theme.Material3.DayNight.NoActionBar not found.",
                sourcePath = source.absolutePath,
                line = 7,
            ),
        )

        val analysis = analyzer.analyze(result, root)

        assertNotNull(analysis)
        val primary = analysis!!.primaryErrors.single()
        assertEquals("aapt_style_parent_invalid", primary.category)
        assertTrue(primary.hint.orEmpty().contains("Theme.MaterialComponents.DayNight.NoActionBar"))
        assertEquals(BuildStage.RESOURCE, analysis.failedStage)
    }

    @Test
    fun `duplicate errors are deduped and summary counts hidden errors`() {
        val source = File(root, "src/main/java/com/demo/MainActivity.java").apply {
            parentFile?.mkdirs()
            writeText("class MainActivity {}")
        }
        val message = "package com.google.android.material.foo does not exist"
        val result = failedResult(
            BuildLogEntry(
                stage = BuildStage.COMPILE,
                level = BuildLogLevel.ERROR,
                message = message,
                sourcePath = source.absolutePath,
                line = 10,
            ),
            BuildLogEntry(
                stage = BuildStage.COMPILE,
                level = BuildLogLevel.ERROR,
                message = message,
                sourcePath = source.absolutePath,
                line = 10,
            ),
            BuildLogEntry(
                stage = BuildStage.COMPILE,
                level = BuildLogLevel.ERROR,
                message = "incompatible types: int cannot be converted to String",
                sourcePath = source.absolutePath,
                line = 22,
            ),
        )

        val analysis = analyzer.analyze(result, root)

        assertNotNull(analysis)
        assertEquals(2, analysis!!.primaryErrors.size)
        assertEquals(0, analysis.secondaryErrorCount)
        assertTrue(analysis.summary.contains("2 primary errors"))
    }

    @Test
    fun `chat prompt includes stage primary errors and suggested reads`() {
        val source = File(root, "src/main/java/com/demo/MainActivity.java").apply {
            parentFile?.mkdirs()
            writeText("class MainActivity {}")
        }
        val analysis = analyzer.analyze(
            failedResult(
                BuildLogEntry(
                    stage = BuildStage.COMPILE,
                    level = BuildLogLevel.ERROR,
                    message = "cannot find symbol\n  symbol:   variable R",
                    sourcePath = source.absolutePath,
                    line = 18,
                ),
            ),
            root,
        )

        val prompt = analysis!!.toChatPrompt()

        assertTrue(prompt.contains("Failed stage: COMPILE"))
        assertTrue(prompt.contains("[java_missing_r_import]"))
        assertTrue(prompt.contains("Suggested reads:"))
        assertTrue(prompt.contains("src/main/java/com/demo/MainActivity.java lines 8-28"))
    }

    private fun failedResult(vararg logs: BuildLogEntry): BuildResult = BuildResult(
        status = BuildStatus.FAILED,
        artifacts = emptyList(),
        logs = logs.toList(),
        errorMessage = "Build failed",
    )
}
