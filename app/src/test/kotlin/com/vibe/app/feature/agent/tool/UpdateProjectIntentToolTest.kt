package com.vibe.app.feature.agent.tool

import com.vibe.app.feature.agent.AgentToolCall
import com.vibe.app.feature.agent.AgentToolContext
import com.vibe.app.feature.project.VibeProjectDirs
import com.vibe.app.feature.project.memo.DefaultIntentStore
import com.vibe.app.feature.project.memo.Intent
import com.vibe.app.feature.project.memo.IntentMarkdownCodec
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UpdateProjectIntentToolTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var projectRoot: File
    private lateinit var workspaceRoot: File
    private lateinit var dirs: VibeProjectDirs
    private lateinit var tool: UpdateProjectIntentTool

    @Before
    fun setup() {
        projectRoot = tmp.newFolder("projects", "p1")
        workspaceRoot = File(projectRoot, "app").apply { mkdirs() }
        dirs = VibeProjectDirs.fromWorkspaceRoot(workspaceRoot).also { it.ensureCreated() }
        val fakePm = FakeProjectManager(workspaceRoot)
        tool = UpdateProjectIntentTool(
            projectManager = fakePm,
            intentStore = DefaultIntentStore(),
        )
    }

    private fun call(
        purpose: String,
        appName: String = "TestApp",
        keyDecisions: List<String> = emptyList(),
        knownLimits: List<String> = emptyList(),
    ): AgentToolCall = AgentToolCall(
        id = "call_1",
        name = "update_project_intent",
        arguments = buildJsonObject {
            put("appName", JsonPrimitive(appName))
            put("purpose", JsonPrimitive(purpose))
            put("keyDecisions", JsonArray(keyDecisions.map { JsonPrimitive(it) }))
            put("knownLimits", JsonArray(knownLimits.map { JsonPrimitive(it) }))
        },
    )

    private val ctx = AgentToolContext(
        chatId = 0, platformUid = "p", iteration = 0, projectId = "p1",
    )

    @Test
    fun `execute writes intent markdown with given fields`() = runTest {
        val result = tool.execute(
            call(
                purpose = "City weather forecast",
                keyDecisions = listOf("Use wttr.in"),
                knownLimits = listOf("Celsius only"),
            ),
            ctx,
        )
        assertFalse("tool result should not be an error", result.isError)
        val intent = IntentMarkdownCodec.decode(dirs.intentFile.readText())
        assertEquals("City weather forecast", intent.purpose)
        assertEquals(listOf("Use wttr.in"), intent.keyDecisions)
        assertEquals(listOf("Celsius only"), intent.knownLimits)
    }

    @Test
    fun `execute rejects over-limit keyDecisions list`() = runTest {
        val result = tool.execute(
            call(purpose = "p", keyDecisions = List(10) { "d$it" }),
            ctx,
        )
        assertTrue(result.isError)
    }

    @Test
    fun `execute rejects over-limit knownLimits list`() = runTest {
        val result = tool.execute(
            call(purpose = "p", knownLimits = List(10) { "l$it" }),
            ctx,
        )
        assertTrue(result.isError)
    }

    @Test
    fun `execute errors out when projectId is blank`() = runTest {
        val blankCtx = ctx.copy(projectId = "")
        val result = tool.execute(call(purpose = "p"), blankCtx)
        assertTrue(result.isError)
    }

    @Test
    fun `definition has correct name and required fields`() {
        assertEquals("update_project_intent", tool.definition.name)
    }
}
