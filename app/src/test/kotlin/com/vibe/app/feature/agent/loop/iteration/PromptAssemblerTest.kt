package com.vibe.app.feature.agent.loop.iteration

import com.vibe.app.feature.project.memo.Intent
import com.vibe.app.feature.project.memo.Outline
import com.vibe.app.feature.project.memo.ProjectMemo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptAssemblerTest {

    private val basePrompt = "You are VibeApp's on-device build agent."
    private val appendix = "## Iteration Mode\n\nYou are continuing an existing app..."

    private val sampleMemo = ProjectMemo(
        outline = Outline(
            generatedAtEpochMs = 0,
            appName = "X",
            packageName = "com.x",
            activities = emptyList(),
            fileCount = 5,
            permissions = emptyList(),
            stringKeys = emptyList(),
            recentTurns = emptyList(),
        ),
        intent = Intent(purpose = "test", keyDecisions = emptyList(), knownLimits = emptyList()),
    )

    @Test
    fun `GREENFIELD returns base prompt unchanged`() {
        val result = PromptAssembler.assemble(
            basePrompt = basePrompt,
            iterationAppendix = appendix,
            mode = AgentMode.GREENFIELD,
            memo = null,
        )
        assertTrue(result.contains(basePrompt))
        assertFalse(result.contains("<project-memo>"))
        assertFalse(result.contains("## Iteration Mode"))
    }

    @Test
    fun `GREENFIELD ignores memo even if provided`() {
        val result = PromptAssembler.assemble(
            basePrompt = basePrompt,
            iterationAppendix = appendix,
            mode = AgentMode.GREENFIELD,
            memo = sampleMemo,
        )
        assertFalse(result.contains("<project-memo>"))
        assertFalse(result.contains("## Iteration Mode"))
    }

    @Test
    fun `ITERATE with memo prepends memo block and appends iteration appendix`() {
        val result = PromptAssembler.assemble(
            basePrompt = basePrompt,
            iterationAppendix = appendix,
            mode = AgentMode.ITERATE,
            memo = sampleMemo,
        )

        assertTrue(result.contains("<project-memo>"))
        assertTrue(result.contains("## Iteration Mode"))
        assertTrue(result.contains(basePrompt))

        // Order: memo block must come before base prompt, base prompt must come before appendix.
        val memoIdx = result.indexOf("<project-memo>")
        val baseIdx = result.indexOf(basePrompt)
        val appendixIdx = result.indexOf("## Iteration Mode")
        assertTrue("memo before base", memoIdx < baseIdx)
        assertTrue("base before appendix", baseIdx < appendixIdx)
    }

    @Test
    fun `ITERATE without memo still appends appendix but skips memo block`() {
        val result = PromptAssembler.assemble(
            basePrompt = basePrompt,
            iterationAppendix = appendix,
            mode = AgentMode.ITERATE,
            memo = null,
        )
        assertTrue(result.contains("## Iteration Mode"))
        assertFalse(result.contains("<project-memo>"))
        assertTrue(result.contains(basePrompt))
    }
}
