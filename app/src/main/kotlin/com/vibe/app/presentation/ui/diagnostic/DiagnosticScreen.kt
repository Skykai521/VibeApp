package com.vibe.app.presentation.ui.diagnostic

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import android.widget.Toast
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vibe.app.R
import com.vibe.app.feature.diagnostic.DiagnosticCategories
import com.vibe.app.feature.diagnostic.DiagnosticEvent
import com.vibe.app.feature.diagnostic.DiagnosticLevels
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticScreen(
    viewModel: DiagnosticViewModel = hiltViewModel(),
    onBackAction: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.debug_log),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackAction) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.go_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
    ) { innerPadding ->
        when (val state = uiState) {
            is DiagnosticUiState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            is DiagnosticUiState.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            is DiagnosticUiState.Loaded -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    item {
                        SummaryCard(summary = state.summary)
                    }
                    item {
                        Text(
                            text = stringResource(R.string.diagnostic_events),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    items(state.events, key = { it.id }) { event ->
                        DiagnosticEventCard(event = event)
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(summary: SummaryInfo) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.diagnostic_summary),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(12.dp))

            SummaryRow(
                label = stringResource(R.string.diagnostic_context_size),
                value = if (summary.estimatedContextTokens != null) {
                    "${formatNumber(summary.estimatedContextTokens)} tokens"
                } else {
                    "—"
                },
            )

            SummaryRow(
                label = stringResource(R.string.diagnostic_compaction),
                value = if (summary.hasCompaction) {
                    "Yes (${summary.lastCompactionStrategy ?: "unknown"})"
                } else {
                    "No"
                },
            )

            SummaryRow(
                label = stringResource(R.string.diagnostic_total_events),
                value = summary.totalEvents.toString(),
            )

            SummaryRow(
                label = stringResource(R.string.diagnostic_errors),
                value = summary.errorCount.toString(),
                valueColor = if (summary.errorCount > 0) MaterialTheme.colorScheme.error else null,
            )

            SummaryRow(
                label = stringResource(R.string.diagnostic_warnings),
                value = summary.warnCount.toString(),
                valueColor = if (summary.warnCount > 0) WarningColor else null,
            )

            SummaryRow(
                label = stringResource(R.string.diagnostic_log_size),
                value = formatFileSize(summary.logSizeBytes),
            )
        }
    }
}

@Composable
private fun SummaryRow(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    valueColor: Color? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DiagnosticEventCard(event: DiagnosticEvent) {
    var isExpanded by remember { mutableStateOf(false) }
    val levelColor = getLevelColor(event.level)
    val categoryIcon = getCategoryIcon(event.category)
    val categoryColor = getCategoryColor(event.category)
    val clipboardManager = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val copiedText = stringResource(R.string.diagnostic_event_copied)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { isExpanded = !isExpanded },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    val text = buildString {
                        appendLine("[${event.category}/${event.level}] ${event.summary}")
                        appendLine("Time: ${formatTimestamp(event.timestamp)}")
                        appendLine("Payload: ${formatJson(event.payload)}")
                    }
                    clipboardManager.setText(AnnotatedString(text))
                    Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                },
            )
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(4.dp, 36.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(levelColor),
            )

            Spacer(Modifier.width(8.dp))

            Icon(
                imageVector = categoryIcon,
                contentDescription = event.category,
                modifier = Modifier.size(20.dp),
                tint = categoryColor,
            )

            Spacer(Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = event.summary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = if (isExpanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatTimestamp(event.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        AnimatedVisibility(visible = isExpanded) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                shape = RoundedCornerShape(8.dp),
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    CategoryHighlights(event)

                    Text(
                        text = formatJson(event.payload),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        ),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    )
                }
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(start = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )
    }
}

@Composable
private fun CategoryHighlights(event: DiagnosticEvent) {
    val payload = event.payload
    when (event.category) {
        DiagnosticCategories.MODEL_REQUEST, DiagnosticCategories.MODEL_RESPONSE -> {
            val provider = payload["providerType"]?.let { jsonPrimitiveContent(it) }
            val model = payload["model"]?.let { jsonPrimitiveContent(it) }
            if (provider != null || model != null) {
                Text(
                    text = listOfNotNull(provider, model).joinToString(" / "),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
        DiagnosticCategories.AGENT_LOOP -> {
            val action = payload["action"]?.let { jsonPrimitiveContent(it) }
            if (action == "conversation_compaction") {
                val strategy = payload["strategy"]?.let { jsonPrimitiveContent(it) } ?: "?"
                val before = payload["itemsBefore"]?.let { jsonPrimitiveContent(it) } ?: "?"
                val after = payload["itemsAfter"]?.let { jsonPrimitiveContent(it) } ?: "?"
                Text(
                    text = "Compaction: $strategy ($before \u2192 $after items)",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
        DiagnosticCategories.LATENCY_BREAKDOWN -> {
            val phases = listOf(
                "prepToStartMs", "startToFirstByteMs", "firstByteToSemanticMs",
                "semanticToCompletedMs", "totalMs",
            )
            val found = phases.mapNotNull { key ->
                payload[key]?.let { jsonPrimitiveContent(it) }?.let { key.removeSuffix("Ms") to it }
            }
            if (found.isNotEmpty()) {
                Column(modifier = Modifier.padding(bottom = 4.dp)) {
                    found.forEach { (label, value) ->
                        Text(
                            text = "$label: ${value}ms",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun getLevelColor(level: String): Color = when (level) {
    DiagnosticLevels.ERROR -> MaterialTheme.colorScheme.error
    DiagnosticLevels.WARN -> WarningColor
    else -> MaterialTheme.colorScheme.outline
}

private fun getCategoryIcon(category: String): ImageVector = when (category) {
    DiagnosticCategories.CHAT_TURN -> Icons.AutoMirrored.Filled.Chat
    DiagnosticCategories.MODEL_REQUEST -> Icons.Filled.Upload
    DiagnosticCategories.MODEL_RESPONSE -> Icons.Filled.Download
    DiagnosticCategories.LATENCY_BREAKDOWN -> Icons.Filled.Timer
    DiagnosticCategories.BUILD_RESULT -> Icons.Filled.Build
    DiagnosticCategories.AGENT_TOOL -> Icons.Filled.Construction
    DiagnosticCategories.AGENT_LOOP -> Icons.Filled.Loop
    else -> Icons.Filled.Loop
}

@Composable
private fun getCategoryColor(category: String): Color = when (category) {
    DiagnosticCategories.CHAT_TURN -> MaterialTheme.colorScheme.primary
    DiagnosticCategories.MODEL_REQUEST,
    DiagnosticCategories.MODEL_RESPONSE -> MaterialTheme.colorScheme.secondary
    DiagnosticCategories.LATENCY_BREAKDOWN,
    DiagnosticCategories.AGENT_TOOL -> MaterialTheme.colorScheme.tertiary
    DiagnosticCategories.BUILD_RESULT -> MaterialTheme.colorScheme.primary
    DiagnosticCategories.AGENT_LOOP -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.outline
}

private val WarningColor = Color(0xFFFFA000)

private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

private fun formatNumber(n: Int): String {
    return if (n >= 1000) "${"%.1f".format(n / 1000.0)}k" else n.toString()
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
}

private fun jsonPrimitiveContent(element: JsonElement): String? = when (element) {
    is JsonPrimitive -> element.content
    else -> null
}

private fun formatJson(json: JsonObject, indent: Int = 0): String {
    val sb = StringBuilder()
    val prefix = "  ".repeat(indent)
    sb.appendLine("{")
    val entries = json.entries.toList()
    for ((i, entry) in entries.withIndex()) {
        val comma = if (i < entries.size - 1) "," else ""
        sb.append("$prefix  \"${entry.key}\": ")
        sb.appendLine("${formatJsonValue(entry.value, indent + 1)}$comma")
    }
    sb.append("$prefix}")
    return sb.toString()
}

private fun formatJsonValue(element: JsonElement, indent: Int): String = when (element) {
    is JsonNull -> "null"
    is JsonPrimitive -> if (element.isString) "\"${element.content}\"" else element.content
    is JsonObject -> formatJson(element, indent)
    is JsonArray -> {
        if (element.isEmpty()) "[]"
        else {
            val prefix = "  ".repeat(indent)
            val items = element.joinToString(",\n$prefix  ") { formatJsonValue(it, indent + 1) }
            "[\n$prefix  $items\n$prefix]"
        }
    }
}
