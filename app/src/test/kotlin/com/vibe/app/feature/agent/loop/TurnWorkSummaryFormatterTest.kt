package com.vibe.app.feature.agent.loop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnWorkSummaryFormatterTest {

    @Test
    fun `repeatable tools are framed as reusable context not prohibition`() {
        val thoughts = """
            [Tool] read_project_file
            [Tool Result] read_project_file: ok
            [Tool] run_build_pipeline
            [Tool Result] run_build_pipeline: ok
            [Tool] launch_app
            [Tool Result] launch_app: ok
            [Tool] inspect_ui
            [Tool Result] inspect_ui: ok
            [Tool] close_app
            [Tool Result] close_app: ok
        """.trimIndent()

        val summary = buildTurnWorkSummary(thoughts)

        assertTrue(summary!!.contains("[Earlier turn context]"))
        assertTrue(summary.contains("Tools used earlier:"))
        assertTrue(summary.contains("You may call build/read/launch/inspect tools again whenever fresh state is needed."))
        assertFalse(summary.contains("do NOT redo"))
    }

    @Test
    fun `mutating tools are separated from repeatable tools`() {
        val thoughts = """
            [Plan] Created: Build a calculator
            [Tool] write_project_file
            [Tool Result] write_project_file: ok
            [Tool] write_project_file
            [Tool Result] write_project_file: ok
            [Tool] run_build_pipeline
            [Tool Result] run_build_pipeline: ok
        """.trimIndent()

        val summary = buildTurnWorkSummary(thoughts)

        assertTrue(summary!!.contains("Plan: Build a calculator"))
        assertTrue(summary.contains("Completed changes/actions: write_project_file(ok×2)"))
        assertTrue(summary.contains("Tools used earlier: run_build_pipeline(ok)"))
    }
}
