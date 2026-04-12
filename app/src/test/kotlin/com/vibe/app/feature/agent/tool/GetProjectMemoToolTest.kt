package com.vibe.app.feature.agent.tool

import com.vibe.app.feature.agent.AgentToolCall
import com.vibe.app.feature.agent.AgentToolContext
import com.vibe.app.feature.project.VibeProjectDirs
import com.vibe.app.feature.project.memo.DefaultIntentStore
import com.vibe.app.feature.project.memo.Intent
import com.vibe.app.feature.project.memo.MemoLoader
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GetProjectMemoToolTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var workspaceRoot: File
    private lateinit var dirs: VibeProjectDirs
    private lateinit var tool: GetProjectMemoTool

    @Before
    fun setup() {
        val projectRoot = tmp.newFolder("projects", "p1")
        workspaceRoot = File(projectRoot, "app").apply { mkdirs() }
        dirs = VibeProjectDirs.fromWorkspaceRoot(workspaceRoot).also { it.ensureCreated() }
        val intentStore = DefaultIntentStore()
        tool = GetProjectMemoTool(
            projectManager = FakeProjectManager(workspaceRoot),
            memoLoader = MemoLoader(intentStore),
        )
    }

    private fun call() = AgentToolCall(
        id = "call_1",
        name = "get_project_memo",
        arguments = buildJsonObject {},
    )

    private val ctx = AgentToolContext(chatId = 0, platformUid = "p", iteration = 0, projectId = "p1")

    private fun outputText(output: JsonObject): String =
        output["memo"]?.jsonPrimitive?.content
            ?: output["text"]?.jsonPrimitive?.content
            ?: error("output missing memo/text: $output")

    @Test
    fun `returns no-memo placeholder when nothing saved yet`() = runTest {
        val result = tool.execute(call(), ctx)
        assertFalse(result.isError)
        val memo = outputText(result.output.jsonObject)
        assertTrue(memo.contains("<project-memo>"))
        assertTrue(memo.contains("no memo"))
    }

    @Test
    fun `returns assembled memo after intent is saved`() = runTest {
        DefaultIntentStore().save(
            dirs,
            Intent("test app", listOf("decision"), emptyList()),
            "TestApp",
        )
        val result = tool.execute(call(), ctx)
        assertFalse(result.isError)
        val memo = outputText(result.output.jsonObject)
        assertTrue(memo.contains("<project-memo>"))
        assertTrue(memo.contains("## Intent"))
        assertTrue(memo.contains("test app"))
    }

    @Test
    fun `errors out when projectId is blank`() = runTest {
        val result = tool.execute(call(), ctx.copy(projectId = ""))
        assertTrue(result.isError)
    }
}
