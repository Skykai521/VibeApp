package com.vibe.app.feature.agent.loop.compaction

import com.vibe.app.feature.agent.AgentConversationItem
import com.vibe.app.feature.agent.AgentMessageRole
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ToolResultTrimStrategyTest {

    private val strategy = ToolResultTrimStrategy()

    // ─── helpers ──────────────────────────────────────────────────────────────

    private fun userItem(text: String = "user message") =
        AgentConversationItem(role = AgentMessageRole.USER, text = text)

    private fun assistantItem(text: String = "assistant reply") =
        AgentConversationItem(role = AgentMessageRole.ASSISTANT, text = text)

    private fun grepToolItem(
        matches: String = "src/Foo.java:10:some match\nsrc/Bar.java:20:another match",
        matchCount: Int = 2,
        fileCount: Int = 2,
        truncated: Boolean = false,
    ) = AgentConversationItem(
        role = AgentMessageRole.TOOL,
        toolName = "grep_project_files",
        payload = buildJsonObject {
            put("matches", JsonPrimitive(matches))
            put("match_count", JsonPrimitive(matchCount))
            put("file_count", JsonPrimitive(fileCount))
            put("truncated", JsonPrimitive(truncated))
        },
    )

    // ─── tests ────────────────────────────────────────────────────────────────

    /**
     * Conversation with 4 turns (turn = starts on USER).
     * Turns 1–2 are "older", turns 3–4 are "recent" (recentTurnCount = 2).
     *
     * Turn 1: USER + ASSISTANT + TOOL(grep_project_files with big matches)
     * Turn 2: USER + ASSISTANT
     * Turn 3: USER + ASSISTANT + TOOL(grep_project_files with big matches) ← recent, must be untouched
     * Turn 4: USER + ASSISTANT
     */
    @Test
    fun `grep results in older turns are trimmed, recent turns are untouched`() = runBlocking {
        val bigMatches = "src/Foo.java:10:some match\n".repeat(50)

        val items = listOf(
            // turn 1 (older)
            userItem("find usages"),
            assistantItem("searching..."),
            grepToolItem(matches = bigMatches),
            // turn 2 (older)
            userItem("ok thanks"),
            assistantItem("you're welcome"),
            // turn 3 (recent)
            userItem("find again"),
            assistantItem("searching again..."),
            grepToolItem(matches = bigMatches),
            // turn 4 (recent)
            userItem("done"),
            assistantItem("great"),
        )

        val result = strategy.compact(items, recentTurnCount = 2, tokenBudget = Int.MAX_VALUE)

        assertNotNull("Strategy should have trimmed something", result)
        val compacted = result!!.items

        // ── older turn (index 2 in the result list) ──────────────────────────
        val olderGrepItem = compacted[2]
        assertEquals(AgentMessageRole.TOOL, olderGrepItem.role)
        assertEquals("grep_project_files", olderGrepItem.toolName)

        val olderPayload = olderGrepItem.payload!!.jsonObject
        // large matches field must be gone
        assertFalse("matches field should be removed", olderPayload.containsKey("matches"))
        // small metadata fields must be preserved
        assertNotNull("file_count should be preserved", olderPayload["file_count"])
        assertNotNull("match_count should be preserved", olderPayload["match_count"])
        assertNotNull("truncated should be preserved", olderPayload["truncated"])
        // note must be present
        val note = olderPayload["note"]?.jsonPrimitive?.content
        assertEquals(
            "[Grep results trimmed — run grep_project_files again if you need the matches]",
            note,
        )

        // ── recent turn grep item must be untouched ───────────────────────────
        val recentGrepItem = compacted[7]
        assertEquals(AgentMessageRole.TOOL, recentGrepItem.role)
        assertEquals("grep_project_files", recentGrepItem.toolName)

        val recentPayload = recentGrepItem.payload!!.jsonObject
        val recentMatches = recentPayload["matches"]?.jsonPrimitive?.content
        assertEquals("Recent grep result should be unchanged", bigMatches, recentMatches)
        assertNull("note should not be present in recent item", recentPayload["note"])
    }

    @Test
    fun `grep payload in files_with_matches mode drops files field and preserves metadata`() =
        runBlocking {
            val items = listOf(
                // turn 1 (older)
                userItem("list files"),
                assistantItem("listing..."),
                AgentConversationItem(
                    role = AgentMessageRole.TOOL,
                    toolName = "grep_project_files",
                    payload = buildJsonObject {
                        put("files", JsonPrimitive("src/Foo.java\nsrc/Bar.java"))
                        put("file_count", JsonPrimitive(2))
                        put("truncated", JsonPrimitive(false))
                    },
                ),
                // turn 2 (recent)
                userItem("ok"),
                assistantItem("done"),
            )

            val result = strategy.compact(items, recentTurnCount = 1, tokenBudget = Int.MAX_VALUE)

            assertNotNull(result)
            val olderGrepPayload = result!!.items[2].payload!!.jsonObject
            assertFalse("files field should be removed", olderGrepPayload.containsKey("files"))
            assertNotNull(olderGrepPayload["file_count"])
            assertNotNull(olderGrepPayload["note"])
        }

    @Test
    fun `grep payload in count mode drops counts field and preserves metadata`() = runBlocking {
        val items = listOf(
            // turn 1 (older)
            userItem("count matches"),
            assistantItem("counting..."),
            AgentConversationItem(
                role = AgentMessageRole.TOOL,
                toolName = "grep_project_files",
                payload = buildJsonObject {
                    put("counts", JsonPrimitive("src/Foo.java: 5\nsrc/Bar.java: 3"))
                    put("file_count", JsonPrimitive(2))
                    put("truncated", JsonPrimitive(false))
                },
            ),
            // turn 2 (recent)
            userItem("ok"),
            assistantItem("done"),
        )

        val result = strategy.compact(items, recentTurnCount = 1, tokenBudget = Int.MAX_VALUE)

        assertNotNull(result)
        val olderGrepPayload = result!!.items[2].payload!!.jsonObject
        assertFalse("counts field should be removed", olderGrepPayload.containsKey("counts"))
        assertNotNull(olderGrepPayload["file_count"])
        assertNotNull(olderGrepPayload["note"])
    }
}
