package com.vibe.app.presentation.ui.chat.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vibe.app.feature.project.snapshot.Snapshot
import com.vibe.app.feature.project.snapshot.SnapshotType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bottom-sheet content showing the full snapshot history for the current
 * project. Most recent first. Each row has a "回到" button that restores
 * the workspace to that snapshot (via a backup snapshot first, so the
 * restore itself is undoable).
 */
@Composable
fun SnapshotHistoryPanel(
    snapshots: List<Snapshot>,
    onRestoreClick: (Snapshot) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp)) {
        Text(
            text = "历史版本",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 16.dp, bottom = 12.dp),
        )
        if (snapshots.isEmpty()) {
            Text(
                text = "暂无快照",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
            )
            return
        }
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(snapshots) { snap ->
                ListItem(
                    headlineContent = {
                        Text(
                            text = when (snap.type) {
                                SnapshotType.TURN -> "⦿ 第 ${snap.turnIndex ?: "?"} 轮 · ${snap.label}"
                                SnapshotType.MANUAL -> "★ ${snap.label}"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    supportingContent = {
                        Text(
                            text = "${formatRelativeTime(snap.createdAtEpochMs)} · ${snap.affectedFiles.size} 个文件",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    trailingContent = {
                        TextButton(onClick = { onRestoreClick(snap) }) { Text("回到") }
                    },
                )
                HorizontalDivider()
            }
        }
    }
}

private fun formatRelativeTime(epochMs: Long): String {
    val now = System.currentTimeMillis()
    val diffMs = now - epochMs
    return when {
        diffMs < 60_000 -> "刚刚"
        diffMs < 3_600_000 -> "${diffMs / 60_000} 分钟前"
        diffMs < 86_400_000 -> "${diffMs / 3_600_000} 小时前"
        else -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))
    }
}
