package com.vibe.app.presentation.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vibe.app.R
import com.vibe.app.feature.agent.AgentStepItem
import com.vibe.app.feature.agent.AgentStepType
import com.vibe.app.feature.agent.AgentToolStatus

private val TOOL_NAME_MAP_KEYS = mapOf(
    "read_project_file" to R.string.tool_name_read_project_file,
    "write_project_file" to R.string.tool_name_write_project_file,
    "edit_project_file" to R.string.tool_name_edit_project_file,
    "delete_project_file" to R.string.tool_name_delete_project_file,
    "list_project_files" to R.string.tool_name_list_project_files,
    "run_build_pipeline" to R.string.tool_name_run_build_pipeline,
    "rename_project" to R.string.tool_name_rename_project,
    "update_project_icon" to R.string.tool_name_update_project_icon,
    "read_runtime_log" to R.string.tool_name_read_runtime_log,
    "fix_crash_guide" to R.string.tool_name_fix_crash_guide,
    "inspect_ui" to R.string.tool_name_inspect_ui,
    "interact_ui" to R.string.tool_name_interact_ui,
)

/**
 * Renders a single agent step as an independent chat item.
 * Three types: THINKING (reasoning text), TOOL_CALL (tool invocation with status), OUTPUT (streamed text).
 */
@Composable
fun AgentStepBubble(
    step: AgentStepItem,
    isLive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    when (step.type) {
        AgentStepType.TOOL_CALL -> ToolCallStep(step = step, modifier = modifier)
        AgentStepType.THINKING -> ThinkingStep(step = step, isLive = isLive, modifier = modifier)
        AgentStepType.OUTPUT -> {} // Output steps are rendered by OpponentChatBubble
    }
}

@Composable
private fun ToolCallStep(
    step: AgentStepItem,
    modifier: Modifier = Modifier,
) {
    val toolNameResId = TOOL_NAME_MAP_KEYS[step.toolName]
    val displayName = if (toolNameResId != null) stringResource(toolNameResId) else step.toolName ?: "Tool"

    val (icon, label) = when (step.toolStatus) {
        AgentToolStatus.CALLING -> "\uD83D\uDD27" to stringResource(R.string.tool_calling, displayName)
        AgentToolStatus.OK -> "\u2705" to stringResource(R.string.tool_result_ok, displayName)
        AgentToolStatus.ERROR -> "\u274C" to stringResource(R.string.tool_result_error, displayName)
        null -> "\uD83D\uDD27" to displayName
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = icon,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (step.toolStatus == AgentToolStatus.CALLING) {
            Spacer(modifier = Modifier.width(8.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun ThinkingStep(
    step: AgentStepItem,
    isLive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (step.content.isBlank()) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(12.dp)
            .then(if (isLive) Modifier.animateContentSize() else Modifier),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "\uD83D\uDCAD",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.thinking_in_progress),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
        Text(
            text = if (isLive) step.content + "\u25CF" else step.content,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
