package com.vibe.app.presentation.ui.chat.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vibe.app.R

/**
 * Bottom-sheet content for viewing and editing the project's intent memo.
 * The memo is a short markdown file (`.vibe/memo/intent.md`) that captures
 * the app's purpose, key decisions, and known limits — maintained by the
 * AI agent but also user-editable.
 */
@Composable
fun ProjectMemoPanel(
    intentMarkdown: String?,
    onSave: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember(intentMarkdown) { mutableStateOf(intentMarkdown.orEmpty()) }

    Column(modifier = modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.project_memo_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (editing) {
                TextButton(onClick = {
                    editing = false
                    draft = intentMarkdown.orEmpty()
                }) { Text(stringResource(R.string.cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    onSave(draft)
                    editing = false
                }) { Text(stringResource(R.string.project_memo_save)) }
            } else {
                TextButton(onClick = { editing = true }) { Text(stringResource(R.string.edit)) }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (editing) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth().height(320.dp),
                label = { Text("intent.md") },
            )
        } else {
            Box(modifier = Modifier.fillMaxWidth().height(320.dp).verticalScroll(rememberScrollState())) {
                Text(
                    text = intentMarkdown ?: stringResource(R.string.project_memo_empty),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
