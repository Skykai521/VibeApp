package com.vibe.app.presentation.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolDisplayNamesTest {

    @Test
    fun `display map covers all registered tool names`() {
        val expected = setOf(
            "close_app",
            "create_plan",
            "delete_project_file",
            "edit_project_file",
            "fetch_web_page",
            "fix_crash_guide",
            "get_design_guide",
            "get_project_memo",
            "get_ui_pattern",
            "grep_project_files",
            "inspect_ui",
            "interact_ui",
            "launch_app",
            "list_project_files",
            "read_project_file",
            "read_runtime_log",
            "rename_project",
            "run_build_pipeline",
            "search_icon",
            "search_ui_pattern",
            "update_plan_step",
            "update_project_icon",
            "update_project_icon_custom",
            "update_project_intent",
            "web_search",
            "write_project_file",
        )

        assertEquals(expected, TOOL_NAME_RES_IDS.keys)
    }

    @Test
    fun `fallback humanizes unknown tool names`() {
        assertEquals("Custom Tool Name", fallbackToolDisplayName("custom_tool_name"))
        assertTrue(fallbackToolDisplayName("one").startsWith("One"))
    }
}
