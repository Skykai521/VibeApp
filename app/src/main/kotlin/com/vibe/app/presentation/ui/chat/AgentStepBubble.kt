package com.vibe.app.presentation.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.vibe.app.feature.agent.ToolCallInfo

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
        AgentStepType.PLAN -> {
            step.plan?.let { plan ->
                PlanBubble(plan = plan, isLive = isLive, modifier = modifier)
            }
        }
    }
}

@Composable
private fun ToolCallStep(
    step: AgentStepItem,
    modifier: Modifier = Modifier,
) {
    if (step.toolCalls.isEmpty()) return

    var isExpanded by remember { mutableStateOf(false) }

    val isAnyCalling = step.toolStatus == AgentToolStatus.CALLING
    val latestCall = step.toolCalls.last()
    val latestLabel = formatToolCallLabel(latestCall)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable { isExpanded = !isExpanded }
            .padding(12.dp),
    ) {
        // Header row: icon + latest tool status + expand hint / spinner
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "\uD83D\uDD27",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = latestLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (isAnyCalling) {
                Spacer(modifier = Modifier.width(8.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
            if (step.toolCalls.size > 1 && !isExpanded) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.expand),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                )
            }
        }

        // Expanded: show all tool calls
        if (isExpanded && step.toolCalls.size > 1) {
            Column(modifier = Modifier.padding(top = 6.dp)) {
                step.toolCalls.forEach { call ->
                    val callIcon = when (call.toolStatus) {
                        AgentToolStatus.CALLING -> "\uD83D\uDD27"
                        AgentToolStatus.OK -> "\u2705"
                        AgentToolStatus.ERROR -> "\u274C"
                    }
                    val callLabel = formatToolCallLabel(call)
                    Row(
                        modifier = Modifier.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = callIcon,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = callLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun formatToolCallLabel(call: ToolCallInfo): String {
    val displayName = resolveToolDisplayName(call.toolName)
    return when (call.toolStatus) {
        AgentToolStatus.CALLING -> stringResource(R.string.tool_calling, displayName)
        AgentToolStatus.OK -> stringResource(R.string.tool_result_ok, displayName)
        AgentToolStatus.ERROR -> stringResource(R.string.tool_result_error, displayName)
    }
}

@Composable
private fun ThinkingStep(
    step: AgentStepItem,
    isLive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (step.content.isBlank()) return

    var isExpanded by remember { mutableStateOf(false) }

    val latestLine = remember(step.content) {
        step.content.lines().lastOrNull { it.isNotBlank() }?.trim().orEmpty()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .clickable { isExpanded = !isExpanded }
            .padding(12.dp),
    ) {
        // Header row
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "\uD83D\uDCAD",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(modifier = Modifier.width(6.dp))
            if (isExpanded) {
                Text(
                    text = stringResource(R.string.thinking_in_progress),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.weight(1f),
                )
            } else {
                // Show latest thinking line when collapsed
                Text(
                    text = latestLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (isLive) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "\u25CF",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.expand),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                )
            }
        }
        // Expanded: show all thinking content
        if (isExpanded) {
            Text(
                text = if (isLive) step.content + " \u25CF" else step.content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
