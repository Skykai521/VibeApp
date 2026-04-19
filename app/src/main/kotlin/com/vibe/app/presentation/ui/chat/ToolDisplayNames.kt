package com.vibe.app.presentation.ui.chat

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vibe.app.R
import java.util.Locale

internal val TOOL_NAME_RES_IDS: Map<String, Int> = mapOf(
    "close_app" to R.string.tool_name_close_app,
    "create_plan" to R.string.tool_name_create_plan,
    "delete_project_file" to R.string.tool_name_delete_project_file,
    "edit_project_file" to R.string.tool_name_edit_project_file,
    "fetch_web_page" to R.string.tool_name_fetch_web_page,
    "fix_crash_guide" to R.string.tool_name_fix_crash_guide,
    "get_project_memo" to R.string.tool_name_get_project_memo,
    "grep_project_files" to R.string.tool_name_grep_project_files,
    "inspect_ui" to R.string.tool_name_inspect_ui,
    "interact_ui" to R.string.tool_name_interact_ui,
    "launch_app" to R.string.tool_name_launch_app,
    "list_project_files" to R.string.tool_name_list_project_files,
    "read_project_file" to R.string.tool_name_read_project_file,
    "read_runtime_log" to R.string.tool_name_read_runtime_log,
    "rename_project" to R.string.tool_name_rename_project,
    "run_build_pipeline" to R.string.tool_name_run_build_pipeline,
    "search_icon" to R.string.tool_name_search_icon,
    "update_plan_step" to R.string.tool_name_update_plan_step,
    "update_project_icon" to R.string.tool_name_update_project_icon,
    "update_project_icon_custom" to R.string.tool_name_update_project_icon_custom,
    "update_project_intent" to R.string.tool_name_update_project_intent,
    "web_search" to R.string.tool_name_web_search,
    "write_project_file" to R.string.tool_name_write_project_file,
)

@Composable
internal fun resolveToolDisplayName(toolName: String): String {
    val resId = toolNameResId(toolName)
    return if (resId != null) stringResource(resId) else fallbackToolDisplayName(toolName)
}

@StringRes
internal fun toolNameResId(toolName: String): Int? = TOOL_NAME_RES_IDS[toolName]

internal fun fallbackToolDisplayName(toolName: String): String =
    toolName
        .split('_')
        .filter { it.isNotBlank() }
        .joinToString(" ") { token ->
            token.replaceFirstChar { ch ->
                if (ch.isLowerCase()) ch.titlecase(Locale.ROOT) else ch.toString()
            }
        }
