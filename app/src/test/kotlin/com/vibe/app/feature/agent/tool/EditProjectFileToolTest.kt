package com.vibe.app.feature.agent.tool

import com.vibe.app.data.database.entity.Project
import com.vibe.app.feature.agent.AgentToolCall
import com.vibe.app.feature.agent.AgentToolContext
import com.vibe.app.feature.project.ProjectManager
import com.vibe.app.feature.project.ProjectWorkspace
import com.vibe.build.engine.model.BuildResult
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditProjectFileToolTest {

    private class MemoryWorkspace(initial: Map<String, String>) : ProjectWorkspace {
        val files = initial.toMutableMap()
        override val projectId: String = "test"
        override val rootDir: File = File("/tmp/unused")
        override val project: Project get() = error("not used")
        override suspend fun readTextFile(relativePath: String): String =
            files[relativePath] ?: error("no file: $relativePath")
        override suspend fun writeTextFile(relativePath: String, content: String) {
            files[relativePath] = content
        }
        override suspend fun deleteFile(relativePath: String) = error("not used")
        override suspend fun listFiles(): List<String> = files.keys.toList()
        override suspend fun cleanBuildCache() = error("not used")
        override suspend fun buildProject(): BuildResult = error("not used")
        override suspend fun resolveFile(relativePath: String): File = File(rootDir, relativePath)
    }

    private class MemoryProjectManager(private val ws: MemoryWorkspace) : ProjectManager {
        override suspend fun createProject(enabledPlatforms: List<String>, name: String?): Project = error("not used")
        override suspend fun openWorkspace(projectId: String): ProjectWorkspace = ws
        override fun observeProject(projectId: String): Flow<Project?> = emptyFlow()
        override suspend fun deleteProject(projectId: String) = error("not used")
        override suspend fun generateProjectId(date: LocalDate): String = error("not used")
    }

    private val context = AgentToolContext(chatId = 1, platformUid = "p", iteration = 1, projectId = "test")

    private fun call(vararg edits: Triple<String, String, Boolean?>): AgentToolCall = AgentToolCall(
        id = "c1",
        name = "edit_project_file",
        arguments = buildJsonObject {
            put("path", JsonPrimitive("Main.java"))
            put(
                "edits",
                buildJsonArray {
                    edits.forEach { (old, new, replaceAll) ->
                        add(
                            buildJsonObject {
                                put("old_string", JsonPrimitive(old))
                                put("new_string", JsonPrimitive(new))
                                replaceAll?.let { put("replace_all", JsonPrimitive(it)) }
                            },
                        )
                    }
                },
            )
        },
    )

    @Test
    fun `zero matches returns isError and does not write file`() = runBlocking {
        val ws = MemoryWorkspace(mapOf("Main.java" to "int a = 1;"))
        val tool = EditProjectFileTool(MemoryProjectManager(ws))

        val result = tool.execute(call(Triple("does-not-exist", "x", null)), context)

        assertTrue(result.isError)
        assertEquals("int a = 1;", ws.files["Main.java"])
        val edits = result.output.jsonObject["edits"]!!.jsonArray
        assertEquals("false", edits[0].jsonObject["matched"]!!.jsonPrimitive.content)
    }

    @Test
    fun `ambiguous match without replace_all is rejected with occurrence count`() = runBlocking {
        val ws = MemoryWorkspace(mapOf("Main.java" to "foo(); foo();"))
        val tool = EditProjectFileTool(MemoryProjectManager(ws))

        val result = tool.execute(call(Triple("foo()", "bar()", null)), context)

        assertTrue(result.isError)
        assertEquals("foo(); foo();", ws.files["Main.java"])
        val edit = result.output.jsonObject["edits"]!!.jsonArray[0].jsonObject
        assertEquals("2", edit["occurrences"]!!.jsonPrimitive.content)
        assertTrue(edit["reason"]!!.jsonPrimitive.content.contains("ambiguous"))
    }

    @Test
    fun `replace_all replaces every occurrence`() = runBlocking {
        val ws = MemoryWorkspace(mapOf("Main.java" to "foo(); foo();"))
        val tool = EditProjectFileTool(MemoryProjectManager(ws))

        val result = tool.execute(call(Triple("foo()", "bar()", true)), context)

        assertFalse(result.isError)
        assertEquals("bar(); bar();", ws.files["Main.java"])
        // applied_count counts EDITS applied (1), not occurrences replaced (2).
        assertEquals("1", result.output.jsonObject["applied_count"]!!.jsonPrimitive.content)
    }

    @Test
    fun `unique match applies and partial failure still writes with counts`() = runBlocking {
        val ws = MemoryWorkspace(mapOf("Main.java" to "int a = 1;"))
        val tool = EditProjectFileTool(MemoryProjectManager(ws))

        val result = tool.execute(
            call(Triple("int a = 1;", "int a = 2;", null), Triple("missing", "x", null)),
            context,
        )

        assertFalse(result.isError)
        assertEquals("int a = 2;", ws.files["Main.java"])
        assertEquals("1", result.output.jsonObject["applied_count"]!!.jsonPrimitive.content)
        assertEquals("1", result.output.jsonObject["failed_count"]!!.jsonPrimitive.content)
    }
}
