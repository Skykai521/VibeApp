package com.vibe.app.feature.agent.loop

import com.vibe.app.feature.agent.AgentConversationItem
import com.vibe.app.feature.agent.AgentMessageRole
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenAiResponsesInputSelectionTest {

    private val delta = listOf(AgentConversationItem(role = AgentMessageRole.TOOL, toolName = "t", toolCallId = "c1", text = "delta"))
    private val full = listOf(
        AgentConversationItem(role = AgentMessageRole.USER, text = "hi"),
        AgentConversationItem(role = AgentMessageRole.ASSISTANT, text = "compacted history"),
    )

    @Test
    fun `null previousResponseId sends full compacted conversation`() {
        assertEquals(full, selectResponsesInput(previousResponseId = null, delta = delta, full = full))
    }

    @Test
    fun `non-null previousResponseId keeps delta mode`() {
        assertEquals(delta, selectResponsesInput(previousResponseId = "resp_123", delta = delta, full = full))
    }
}
