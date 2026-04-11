package com.vibe.app.presentation.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.vibe.app.R
import com.vibe.app.presentation.theme.VibeAppTheme

@Composable
fun ThinkingBlock(
    modifier: Modifier = Modifier,
    thoughts: String,
    isLoading: Boolean = false
) {
    if (thoughts.isBlank()) return

    var isExpanded by remember { mutableStateOf(false) }
    val rotationAngle by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        label = "rotation"
    )

    val toolCallingFmt = stringResource(R.string.tool_calling)
    val toolOkFmt = stringResource(R.string.tool_result_ok)
    val toolErrorFmt = stringResource(R.string.tool_result_error)
    val resolvedToolNames = buildMap {
        TOOL_NAME_RES_IDS.forEach { (name, resId) ->
            put(name, stringResource(resId))
        }
    }
    val formattedThoughts = remember(thoughts, toolCallingFmt, toolOkFmt, toolErrorFmt, resolvedToolNames) {
        formatToolLines(thoughts, toolCallingFmt, toolOkFmt, toolErrorFmt, resolvedToolNames)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { isExpanded = !isExpanded }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "\uD83D\uDCAD",
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isExpanded) {
                    stringResource(R.string.hide_thinking)
                } else {
                    stringResource(R.string.view_thinking)
                },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (isLoading) {
                Text(
                    text = stringResource(R.string.thinking_in_progress),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = if (isExpanded) {
                    stringResource(R.string.collapse)
                } else {
                    stringResource(R.string.expand)
                },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(rotationAngle)
            )
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            val displayText = if (isLoading) formattedThoughts.trimIndent() + "\u25CF" else formattedThoughts.trimIndent()

            Markdown(
                content = displayText,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                colors = markdownColor(
                    codeBackground = MaterialTheme.colorScheme.surfaceVariant,
                ),
                typography = chatMarkdownTypography(),
                padding = chatMarkdownPadding(),
                components = chatMarkdownComponents(),
            )
        }

        if (!isExpanded && formattedThoughts.isNotBlank()) {
            val latestToolLine = remember(formattedThoughts) {
                findLatestToolLine(formattedThoughts)
            }
            AnimatedContent(
                targetState = latestToolLine,
                transitionSpec = {
                    slideInVertically { it } togetherWith slideOutVertically { -it }
                },
                label = "tool_line_transition",
            ) { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                )
            }
        }
    }
}

private val FORMATTED_TOOL_LINE_PREFIX = listOf("\uD83D\uDD27 ", "\u2705 ", "\u274C ")

private fun findLatestToolLine(formattedThoughts: String): String {
    return formattedThoughts.lines()
        .lastOrNull { line ->
            val trimmed = line.trim()
            FORMATTED_TOOL_LINE_PREFIX.any { trimmed.startsWith(it) }
        }
        ?.trim()
        ?: formattedThoughts.lines().lastOrNull { it.isNotBlank() }?.trim().orEmpty()
}

private val TOOL_LINE_REGEX = Regex("""\[Tool]\s+(\S+)""")
private val TOOL_RESULT_LINE_REGEX = Regex("""\[Tool Result]\s+(\S+):\s*(ok|error|fail)""")

private fun formatToolLines(
    thoughts: String,
    toolCallingFmt: String,
    toolOkFmt: String,
    toolErrorFmt: String,
    toolNameMap: Map<String, String> = emptyMap(),
): String = thoughts.lines().joinToString("\n") { line ->
    val trimmed = line.trim()
    val toolMatch = TOOL_LINE_REGEX.matchEntire(trimmed)
    val resultMatch = TOOL_RESULT_LINE_REGEX.matchEntire(trimmed)
    when {
        resultMatch != null -> {
            val name = resultMatch.groupValues[1]
            val displayName = toolNameMap[name] ?: name
            val status = resultMatch.groupValues[2]
            if (status == "ok") {
                "\u2705 ${toolOkFmt.format(displayName)}"
            } else {
                "\u274C ${toolErrorFmt.format(displayName)}"
            }
        }
        toolMatch != null -> {
            val name = toolMatch.groupValues[1]
            val displayName = toolNameMap[name] ?: name
            "\uD83D\uDD27 ${toolCallingFmt.format(displayName)}"
        }
        else -> line
    }
}

@Preview
@Composable
private fun ThinkingBlockPreview() {
    val sampleThoughts = """
        Let me think about this step by step:
        
        1. First, I need to understand the problem
        2. Then, I'll analyze the requirements
        3. Finally, I'll provide a solution
        
        This is a longer thinking process that shows how the AI reasons through the problem.
    """.trimIndent()

    VibeAppTheme {
        ThinkingBlock(
            thoughts = sampleThoughts,
            isLoading = false
        )
    }
}

@Preview
@Composable
private fun ThinkingBlockLoadingPreview() {
    VibeAppTheme {
        ThinkingBlock(
            thoughts = "Analyzing the problem...",
            isLoading = true
        )
    }
}
