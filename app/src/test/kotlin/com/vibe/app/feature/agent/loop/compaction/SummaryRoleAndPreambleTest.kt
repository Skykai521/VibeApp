package com.vibe.app.feature.agent.loop.compaction

import com.vibe.app.feature.agent.AgentConversationItem
import com.vibe.app.feature.agent.AgentMessageRole
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryRoleAndPreambleTest {

    private fun user(t: String) = AgentConversationItem(role = AgentMessageRole.USER, text = t)
    private fun assistant(t: String) = AgentConversationItem(role = AgentMessageRole.ASSISTANT, text = t)

    @Test
    fun `splitIntoTurns keeps leading non-user items as preamble group`() {
        val items = listOf(assistant("[Compacted context] earlier summary"), user("next"), assistant("reply"))
        val turns = ToolResultTrimStrategy.splitIntoTurns(items)
        assertEquals("all items must survive round-trip", items, turns.flatten())
    }

    @Test
    fun `structural summary emits assistant role with compacted prefix`() = runBlocking {
        val items = (1..4).flatMap { listOf(user("req $it"), assistant("resp $it")) }
        val result = StructuralSummaryStrategy().compact(items, recentTurnCount = 1, tokenBudget = Int.MAX_VALUE)!!
        val summary = result.items.first()
        assertEquals(AgentMessageRole.ASSISTANT, summary.role)
        assertTrue(summary.text!!.startsWith("[Compacted context]"))
    }

    @Test
    fun `structural summary passes preamble group through unchanged`() = runBlocking {
        val preamble = assistant("[Compacted context] old summary")
        val items = listOf(preamble) + (1..3).flatMap { listOf(user("req $it"), assistant("resp $it")) }
        val result = StructuralSummaryStrategy().compact(items, recentTurnCount = 1, tokenBudget = Int.MAX_VALUE)!!
        assertTrue("preamble summary must survive", result.items.any { it.text == preamble.text })
    }
}
