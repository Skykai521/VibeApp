package com.vibe.app.feature.agent.tool

import com.vibe.app.feature.agent.AgentToolCall
import com.vibe.app.feature.agent.AgentToolContext
import com.vibe.app.feature.uipattern.DesignGuideLoader
import com.vibe.app.feature.uipattern.PatternKind
import com.vibe.app.feature.uipattern.PatternLibrary
import com.vibe.app.feature.uipattern.PatternRecord
import com.vibe.app.feature.uipattern.PatternSearchHit
import com.vibe.app.feature.uipattern.PatternSlot
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiPatternToolsTest {

    private val record = PatternRecord(
        id = "list_item_two_line",
        kind = PatternKind.BLOCK,
        description = "Two-line list row",
        keywords = listOf("list", "item"),
        slots = listOf(PatternSlot("title", "Primary", "Title")),
        dependencies = emptyList(),
        layoutXml = "<View/>",
        notes = "Use for rows.",
    )

    private val hit = PatternSearchHit(
        id = "list_item_two_line",
        kind = PatternKind.BLOCK,
        description = "Two-line list row",
        keywords = listOf("list", "item"),
        slotNames = listOf("title"),
        dependencies = emptyList(),
    )

    private class FakeLibrary(
        private val hits: List<PatternSearchHit>,
        private val record: PatternRecord?,
    ) : PatternLibrary(object : AssetProvider {
        override fun openIndex() = ByteArrayInputStream("{\"version\":1,\"patterns\":[]}".toByteArray())
        override fun openFile(relativePath: String) = ByteArrayInputStream(ByteArray(0))
    }) {
        override fun allHits() = hits
        override fun get(id: String): PatternRecord? = if (record?.id == id) record else null
    }

    private val designGuide = DesignGuideLoader {
        ByteArrayInputStream(
            """
                # Guide

                ## Tokens

                Token rules here.

                ## Components

                Component rules.
            """.trimIndent().toByteArray(),
        )
    }

    private val context = AgentToolContext(
        chatId = 1,
        platformUid = "p",
        iteration = 0,
        projectId = "proj",
    )

    private fun call(name: String, args: JsonObject) = AgentToolCall(id = "c1", name = name, arguments = args)

    @Test
    fun `search tool returns hits`() = runBlocking {
        val tool = SearchUiPatternTool(FakeLibrary(listOf(hit), record))
        val result = tool.execute(
            call("search_ui_pattern", buildJsonObject { put("keyword", JsonPrimitive("list")) }),
            context,
        )
        assertFalse(result.isError)
        assertTrue(result.output.toString().contains("list_item_two_line"))
    }

    @Test
    fun `search tool rejects empty keyword`() = runBlocking {
        val tool = SearchUiPatternTool(FakeLibrary(listOf(hit), record))
        val result = tool.execute(
            call("search_ui_pattern", buildJsonObject { put("keyword", JsonPrimitive("")) }),
            context,
        )
        assertTrue(result.isError)
    }

    @Test
    fun `get tool returns full record`() = runBlocking {
        val tool = GetUiPatternTool(FakeLibrary(listOf(hit), record))
        val result = tool.execute(
            call("get_ui_pattern", buildJsonObject { put("id", JsonPrimitive("list_item_two_line")) }),
            context,
        )
        assertFalse(result.isError)
        assertTrue(result.output.toString().contains("<View/>"))
        assertTrue(result.output.toString().contains("Use for rows."))
    }

    @Test
    fun `get tool unknown id returns error`() = runBlocking {
        val tool = GetUiPatternTool(FakeLibrary(emptyList(), null))
        val result = tool.execute(
            call("get_ui_pattern", buildJsonObject { put("id", JsonPrimitive("nope")) }),
            context,
        )
        assertTrue(result.isError)
    }

    @Test
    fun `design guide tool returns section`() = runBlocking {
        val tool = GetDesignGuideTool(designGuide)
        val result = tool.execute(
            call("get_design_guide", buildJsonObject { put("section", JsonPrimitive("tokens")) }),
            context,
        )
        assertFalse(result.isError)
        assertTrue(result.output.toString().contains("Token rules here"))
    }
}
