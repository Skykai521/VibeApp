# Debug Diagnostic Viewer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a settings-controlled debug mode that exposes a visual DiagnosticEvent viewer accessible from the chat page's overflow menu.

**Architecture:** A new `debug_mode` boolean in DataStore flows through SettingRepository to both the Settings UI (Switch toggle) and ChatViewModel (menu visibility). The DiagnosticScreen is a standalone Compose navigation destination that reads NDJSON logs via the existing `ChatDiagnosticLogger.readChatLog()` API, aggregates summary metrics, and renders an event timeline.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, Hilt, DataStore, Compose Navigation, kotlinx.serialization

---

## File Structure

### New Files

| File | Responsibility |
|------|----------------|
| `app/src/main/kotlin/com/vibe/app/presentation/ui/diagnostic/DiagnosticUiState.kt` | UI state models: `SummaryInfo`, `DiagnosticUiState` |
| `app/src/main/kotlin/com/vibe/app/presentation/ui/diagnostic/DiagnosticViewModel.kt` | Load NDJSON, parse events, aggregate summary |
| `app/src/main/kotlin/com/vibe/app/presentation/ui/diagnostic/DiagnosticScreen.kt` | Scaffold + SummaryCard + event timeline LazyColumn |

### Modified Files

| File | Change |
|------|--------|
| `app/src/main/kotlin/com/vibe/app/data/datastore/SettingDataSource.kt` | Add `updateDebugMode`/`getDebugMode` |
| `app/src/main/kotlin/com/vibe/app/data/datastore/SettingDataSourceImpl.kt` | Implement with `booleanPreferencesKey("debug_mode")` |
| `app/src/main/kotlin/com/vibe/app/data/repository/SettingRepository.kt` | Add `getDebugMode`/`updateDebugMode` |
| `app/src/main/kotlin/com/vibe/app/data/repository/SettingRepositoryImpl.kt` | Delegate to SettingDataSource |
| `app/src/main/kotlin/com/vibe/app/presentation/ui/setting/SettingViewModelV2.kt` | Add `debugMode` StateFlow + `toggleDebugMode()` |
| `app/src/main/kotlin/com/vibe/app/presentation/ui/setting/SettingScreen.kt` | Add developer options section with Switch |
| `app/src/main/kotlin/com/vibe/app/presentation/ui/chat/ChatViewModel.kt` | Add `isDebugEnabled` StateFlow |
| `app/src/main/kotlin/com/vibe/app/presentation/ui/chat/ChatScreen.kt` | Add diagnostic menu item + navigation callback |
| `app/src/main/kotlin/com/vibe/app/presentation/common/Route.kt` | Add `DIAGNOSTIC` route |
| `app/src/main/kotlin/com/vibe/app/presentation/common/NavigationGraph.kt` | Add `diagnosticNavigation()` |
| `app/src/main/res/values/strings.xml` | Add string resources |
| `app/src/main/res/values-zh-rCN/strings.xml` | Add Chinese string resources |

---

### Task 1: DataStore — debug_mode persistence

**Files:**
- Modify: `app/src/main/kotlin/com/vibe/app/data/datastore/SettingDataSource.kt:6-11`
- Modify: `app/src/main/kotlin/com/vibe/app/data/datastore/SettingDataSourceImpl.kt:13-46`

- [ ] **Step 1: Add interface methods to SettingDataSource**

In `SettingDataSource.kt`, add two new methods after the existing ones:

```kotlin
interface SettingDataSource {
    suspend fun updateDynamicTheme(theme: DynamicTheme)
    suspend fun updateThemeMode(themeMode: ThemeMode)
    suspend fun getDynamicTheme(): DynamicTheme?
    suspend fun getThemeMode(): ThemeMode?
    suspend fun updateDebugMode(enabled: Boolean)
    suspend fun getDebugMode(): Boolean
}
```

- [ ] **Step 2: Implement in SettingDataSourceImpl**

In `SettingDataSourceImpl.kt`, add the key and implementations:

```kotlin
import androidx.datastore.preferences.core.booleanPreferencesKey
```

Add a new key after `themeModeKey` (line 17):

```kotlin
private val debugModeKey = booleanPreferencesKey("debug_mode")
```

Add implementations after `getThemeMode()` (after line 45):

```kotlin
override suspend fun updateDebugMode(enabled: Boolean) {
    dataStore.edit { pref ->
        pref[debugModeKey] = enabled
    }
}

override suspend fun getDebugMode(): Boolean {
    return dataStore.data.map { pref ->
        pref[debugModeKey]
    }.first() ?: false
}
```

- [ ] **Step 3: Build to verify compilation**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/data/datastore/SettingDataSource.kt app/src/main/kotlin/com/vibe/app/data/datastore/SettingDataSourceImpl.kt
git commit -m "feat(settings): add debug_mode persistence to DataStore"
```

---

### Task 2: Repository — expose debug mode

**Files:**
- Modify: `app/src/main/kotlin/com/vibe/app/data/repository/SettingRepository.kt:6-16`
- Modify: `app/src/main/kotlin/com/vibe/app/data/repository/SettingRepositoryImpl.kt:12-44`

- [ ] **Step 1: Add interface methods to SettingRepository**

In `SettingRepository.kt`, add after the PlatformV2 CRUD section (after line 15):

```kotlin
// Debug mode
suspend fun getDebugMode(): Boolean
suspend fun updateDebugMode(enabled: Boolean)
```

- [ ] **Step 2: Implement in SettingRepositoryImpl**

In `SettingRepositoryImpl.kt`, add after `getPlatformV2ById` (after line 43):

```kotlin
override suspend fun getDebugMode(): Boolean = settingDataSource.getDebugMode()

override suspend fun updateDebugMode(enabled: Boolean) {
    settingDataSource.updateDebugMode(enabled)
}
```

- [ ] **Step 3: Build to verify compilation**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/data/repository/SettingRepository.kt app/src/main/kotlin/com/vibe/app/data/repository/SettingRepositoryImpl.kt
git commit -m "feat(settings): expose debug mode through SettingRepository"
```

---

### Task 3: Settings UI — developer options section with Switch

**Files:**
- Modify: `app/src/main/kotlin/com/vibe/app/presentation/ui/setting/SettingViewModelV2.kt:18-126`
- Modify: `app/src/main/kotlin/com/vibe/app/presentation/ui/setting/SettingScreen.kt:58-160`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh-rCN/strings.xml`

- [ ] **Step 1: Add string resources**

In `app/src/main/res/values/strings.xml`, add:

```xml
<string name="developer_options">Developer Options</string>
<string name="debug_log">Debug Log</string>
<string name="debug_log_description">Enable diagnostic log viewer in chat page</string>
```

In `app/src/main/res/values-zh-rCN/strings.xml`, add:

```xml
<string name="developer_options">开发者选项</string>
<string name="debug_log">调���日志</string>
<string name="debug_log_description">在对话页面启用诊断日志查看器</string>
```

- [ ] **Step 2: Add debugMode state to SettingViewModelV2**

In `SettingViewModelV2.kt`, add a new StateFlow after `_switchedPlatformEvent` (after line 30):

```kotlin
private val _debugMode = MutableStateFlow(false)
val debugMode: StateFlow<Boolean> = _debugMode.asStateFlow()
```

In the `init` block (line 32-34), add after `fetchPlatforms()`:

```kotlin
init {
    fetchPlatforms()
    fetchDebugMode()
}
```

Add the methods after `confirmDelete()` (before the `DialogState` data class):

```kotlin
fun toggleDebugMode() {
    val newValue = !_debugMode.value
    _debugMode.update { newValue }
    viewModelScope.launch {
        settingRepository.updateDebugMode(newValue)
    }
}

private fun fetchDebugMode() {
    viewModelScope.launch {
        _debugMode.update { settingRepository.getDebugMode() }
    }
}
```

- [ ] **Step 3: Add developer options section to SettingScreen**

In `SettingScreen.kt`, add the import:

```kotlin
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material3.Switch
```

In the `Column` inside `Scaffold`, add the developer options section after the About item (after line 149, before the dialog blocks):

```kotlin
// About
AboutPageItem(onItemClick = onNavigateToAboutPage)

HorizontalDivider(
    modifier = Modifier.padding(horizontal = 16.dp),
    color = MaterialTheme.colorScheme.outlineVariant
)

// Developer Options
DebugModeSetting(
    isEnabled = settingViewModel.debugMode.collectAsStateWithLifecycle().value,
    onToggle = settingViewModel::toggleDebugMode
)
```

Add the composable function at the bottom of the file (after the existing setting item composables):

```kotlin
@Composable
private fun DebugModeSetting(
    isEnabled: Boolean,
    onToggle: () -> Unit
) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp),
        headlineContent = { Text(stringResource(R.string.debug_log)) },
        supportingContent = { Text(stringResource(R.string.debug_log_description)) },
        leadingContent = {
            Icon(
                imageVector = Icons.Outlined.BugReport,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        trailingContent = {
            Switch(
                checked = isEnabled,
                onCheckedChange = { onToggle() }
            )
        }
    )
}
```

Note: Add these imports at the top of `SettingScreen.kt`:

```kotlin
import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
```

- [ ] **Step 4: Build to verify compilation**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/presentation/ui/setting/SettingViewModelV2.kt app/src/main/kotlin/com/vibe/app/presentation/ui/setting/SettingScreen.kt app/src/main/res/values/strings.xml app/src/main/res/values-zh-rCN/strings.xml
git commit -m "feat(settings): add developer options section with debug log toggle"
```

---

### Task 4: DiagnosticUiState — UI state models

**Files:**
- Create: `app/src/main/kotlin/com/vibe/app/presentation/ui/diagnostic/DiagnosticUiState.kt`

- [ ] **Step 1: Create DiagnosticUiState.kt**

```kotlin
package com.vibe.app.presentation.ui.diagnostic

import com.vibe.app.feature.diagnostic.DiagnosticEvent

data class SummaryInfo(
    val estimatedContextTokens: Int? = null,
    val hasCompaction: Boolean = false,
    val lastCompactionStrategy: String? = null,
    val totalEvents: Int = 0,
    val errorCount: Int = 0,
    val warnCount: Int = 0,
    val logSizeBytes: Long = 0,
)

sealed class DiagnosticUiState {
    data object Loading : DiagnosticUiState()
    data class Loaded(
        val summary: SummaryInfo,
        val events: List<DiagnosticEvent>,
    ) : DiagnosticUiState()
    data class Error(val message: String) : DiagnosticUiState()
}
```

- [ ] **Step 2: Build to verify compilation**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/presentation/ui/diagnostic/DiagnosticUiState.kt
git commit -m "feat(diagnostic): add UI state models for diagnostic viewer"
```

---

### Task 5: DiagnosticViewModel — log parsing and summary aggregation

**Files:**
- Create: `app/src/main/kotlin/com/vibe/app/presentation/ui/diagnostic/DiagnosticViewModel.kt`

- [ ] **Step 1: Create DiagnosticViewModel.kt**

```kotlin
package com.vibe.app.presentation.ui.diagnostic

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.vibe.app.feature.diagnostic.ChatDiagnosticLogger
import com.vibe.app.feature.diagnostic.DiagnosticCategories
import com.vibe.app.feature.diagnostic.DiagnosticEvent
import com.vibe.app.feature.diagnostic.DiagnosticLevels
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

@HiltViewModel
class DiagnosticViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val diagnosticLogger: ChatDiagnosticLogger,
) : ViewModel() {

    private val chatRoomId: Int = checkNotNull(savedStateHandle["chatRoomId"])

    private val _uiState = MutableStateFlow<DiagnosticUiState>(DiagnosticUiState.Loading)
    val uiState: StateFlow<DiagnosticUiState> = _uiState.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    init {
        loadDiagnosticLog()
    }

    fun loadDiagnosticLog() {
        _uiState.value = DiagnosticUiState.Loading
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                diagnosticLogger.readChatLog(chatRoomId)
            }
            if (result == null) {
                _uiState.value = DiagnosticUiState.Error("No diagnostic log found for this chat.")
                return@launch
            }
            val events = withContext(Dispatchers.Default) {
                parseEvents(result.content)
            }
            val summary = withContext(Dispatchers.Default) {
                aggregateSummary(events, result.content.length.toLong())
            }
            _uiState.value = DiagnosticUiState.Loaded(
                summary = summary,
                events = events.sortedByDescending { it.timestamp },
            )
        }
    }

    private fun parseEvents(ndjson: String): List<DiagnosticEvent> {
        return ndjson.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                runCatching { json.decodeFromString<DiagnosticEvent>(line) }.getOrNull()
            }
            .toList()
    }

    private fun aggregateSummary(events: List<DiagnosticEvent>, logSizeBytes: Long): SummaryInfo {
        var estimatedContextTokens: Int? = null
        var hasCompaction = false
        var lastCompactionStrategy: String? = null
        var errorCount = 0
        var warnCount = 0

        for (event in events) {
            when (event.level) {
                DiagnosticLevels.ERROR -> errorCount++
                DiagnosticLevels.WARN -> warnCount++
            }
            if (event.category == DiagnosticCategories.AGENT_LOOP) {
                val action = event.payload["action"]?.jsonPrimitive?.content
                if (action == "conversation_compaction") {
                    hasCompaction = true
                    val strategy = event.payload["strategy"]?.jsonPrimitive?.content
                    lastCompactionStrategy = strategy
                    val tokens = runCatching {
                        event.payload["estimatedTokens"]?.jsonPrimitive?.int
                    }.getOrNull()
                    if (tokens != null) estimatedContextTokens = tokens
                }
            }
            if (event.category == DiagnosticCategories.MODEL_REQUEST) {
                val tokens = runCatching {
                    event.payload["estimatedTokens"]?.jsonPrimitive?.int
                }.getOrNull()
                if (tokens != null) estimatedContextTokens = tokens
            }
        }

        return SummaryInfo(
            estimatedContextTokens = estimatedContextTokens,
            hasCompaction = hasCompaction,
            lastCompactionStrategy = lastCompactionStrategy,
            totalEvents = events.size,
            errorCount = errorCount,
            warnCount = warnCount,
            logSizeBytes = logSizeBytes,
        )
    }
}
```

- [ ] **Step 2: Build to verify compilation**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/presentation/ui/diagnostic/DiagnosticViewModel.kt
git commit -m "feat(diagnostic): add ViewModel with NDJSON parsing and summary aggregation"
```

---

### Task 6: DiagnosticScreen — Composable UI

**Files:**
- Create: `app/src/main/kotlin/com/vibe/app/presentation/ui/diagnostic/DiagnosticScreen.kt`

- [ ] **Step 1: Create DiagnosticScreen.kt**

```kotlin
package com.vibe.app.presentation.ui.diagnostic

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
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
                            text = "Events",
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

            // Context size
            SummaryRow(
                label = stringResource(R.string.diagnostic_context_size),
                value = if (summary.estimatedContextTokens != null) {
                    "${formatNumber(summary.estimatedContextTokens)} tokens"
                } else {
                    "—"
                },
            )

            // Compaction status
            SummaryRow(
                label = stringResource(R.string.diagnostic_compaction),
                value = if (summary.hasCompaction) {
                    "Yes (${summary.lastCompactionStrategy ?: "unknown"})"
                } else {
                    "No"
                },
            )

            // Event counts
            SummaryRow(
                label = stringResource(R.string.diagnostic_total_events),
                value = summary.totalEvents.toString(),
            )

            // Error / warn
            Row(modifier = Modifier.fillMaxWidth()) {
                SummaryRow(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.diagnostic_errors),
                    value = summary.errorCount.toString(),
                    valueColor = if (summary.errorCount > 0) MaterialTheme.colorScheme.error else null,
                )
                SummaryRow(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.diagnostic_warnings),
                    value = summary.warnCount.toString(),
                    valueColor = if (summary.warnCount > 0) WarningColor else null,
                )
            }

            // Log size
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

@Composable
private fun DiagnosticEventCard(event: DiagnosticEvent) {
    var isExpanded by remember { mutableStateOf(false) }
    val levelColor = getLevelColor(event.level)
    val categoryIcon = getCategoryIcon(event.category)
    val categoryColor = getCategoryColor(event.category)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { isExpanded = !isExpanded }
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Level color indicator
            Box(
                modifier = Modifier
                    .size(4.dp, 36.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(levelColor),
            )

            Spacer(Modifier.width(8.dp))

            // Category icon
            Icon(
                imageVector = categoryIcon,
                contentDescription = event.category,
                modifier = Modifier.size(20.dp),
                tint = categoryColor,
            )

            Spacer(Modifier.width(8.dp))

            // Summary + timestamp
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

        // Expanded payload
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
                    // Category-specific highlights
                    CategoryHighlights(event)

                    // JSON payload
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
                    text = "Compaction: $strategy ($before → $after items)",
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

// --- Helpers ---

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
    return if (n >= 1000) "${n / 1000}k" else n.toString()
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
```

- [ ] **Step 2: Add string resources for summary card**

In `app/src/main/res/values/strings.xml`, add:

```xml
<string name="diagnostic_summary">Summary</string>
<string name="diagnostic_context_size">Context Size</string>
<string name="diagnostic_compaction">Context Compaction</string>
<string name="diagnostic_total_events">Total Events</string>
<string name="diagnostic_errors">Errors</string>
<string name="diagnostic_warnings">Warnings</string>
<string name="diagnostic_log_size">Log Size</string>
```

In `app/src/main/res/values-zh-rCN/strings.xml`, add:

```xml
<string name="diagnostic_summary">摘要</string>
<string name="diagnostic_context_size">上下文大小</string>
<string name="diagnostic_compaction">上下文压缩</string>
<string name="diagnostic_total_events">总事件数</string>
<string name="diagnostic_errors">错误</string>
<string name="diagnostic_warnings">警告</string>
<string name="diagnostic_log_size">日志大小</string>
```

- [ ] **Step 3: Build to verify compilation**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/presentation/ui/diagnostic/DiagnosticScreen.kt app/src/main/res/values/strings.xml app/src/main/res/values-zh-rCN/strings.xml
git commit -m "feat(diagnostic): add DiagnosticScreen with summary card and event timeline"
```

---

### Task 7: Navigation — route and graph wiring

**Files:**
- Modify: `app/src/main/kotlin/com/vibe/app/presentation/common/Route.kt:3-17`
- Modify: `app/src/main/kotlin/com/vibe/app/presentation/common/NavigationGraph.kt:29-179`

- [ ] **Step 1: Add DIAGNOSTIC route**

In `Route.kt`, add after the `CHAT_ROOM` line (after line 10):

```kotlin
const val DIAGNOSTIC = "diagnostic/{chatRoomId}"
```

- [ ] **Step 2: Add diagnosticNavigation to NavigationGraph**

In `NavigationGraph.kt`, add the import:

```kotlin
import com.vibe.app.presentation.ui.diagnostic.DiagnosticScreen
```

In `SetupNavGraph`, add the new destination call after `chatScreenNavigation(navController)` (after line 41):

```kotlin
homeScreenNavigation(navController)
setupNavigation(navController)
settingNavigation(navController)
chatScreenNavigation(navController)
diagnosticNavigation(navController)
```

Add the new function at the bottom of the file (after `settingNavigation`):

```kotlin
fun NavGraphBuilder.diagnosticNavigation(navController: NavHostController) {
    composable(
        Route.DIAGNOSTIC,
        arguments = listOf(navArgument("chatRoomId") { type = NavType.IntType })
    ) {
        DiagnosticScreen(
            onBackAction = { navController.navigateUp() }
        )
    }
}
```

- [ ] **Step 3: Build to verify compilation**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/presentation/common/Route.kt app/src/main/kotlin/com/vibe/app/presentation/common/NavigationGraph.kt
git commit -m "feat(diagnostic): add navigation route and graph wiring"
```

---

### Task 8: Chat integration — menu entry and debug mode flow

**Files:**
- Modify: `app/src/main/kotlin/com/vibe/app/presentation/ui/chat/ChatViewModel.kt:54-179`
- Modify: `app/src/main/kotlin/com/vibe/app/presentation/ui/chat/ChatScreen.kt:121-150,310-350,718-806,808-860`
- Modify: `app/src/main/kotlin/com/vibe/app/presentation/common/NavigationGraph.kt:125-140`

- [ ] **Step 1: Add isDebugEnabled to ChatViewModel**

In `ChatViewModel.kt`, add a new StateFlow after `pendingUnsavedDiagnosticChatId` (after line 177):

```kotlin
private val _isDebugEnabled = MutableStateFlow(false)
val isDebugEnabled = _isDebugEnabled.asStateFlow()
```

In the `init` block of ChatViewModel (find it by searching for the existing init), add:

```kotlin
viewModelScope.launch {
    _isDebugEnabled.value = settingRepository.getDebugMode()
}
```

- [ ] **Step 2: Add debug log menu item to ChatDropdownMenu**

In `ChatScreen.kt`, update the `ChatDropdownMenu` function signature to add the new callback (around line 809):

```kotlin
@Composable
fun ChatDropdownMenu(
    isDropdownMenuExpanded: Boolean,
    isChatMenuEnabled: Boolean,
    isProjectMenuEnabled: Boolean,
    isDebugEnabled: Boolean,
    onDismissRequest: () -> Unit,
    onUpdateProjectNameClick: () -> Unit,
    onInstallApkClick: () -> Unit,
    onExportChatItemClick: () -> Unit,
    onExportSourceCodeItemClick: () -> Unit,
    onExportApkItemClick: () -> Unit,
    onClearChatHistoryClick: () -> Unit,
    onDiagnosticClick: () -> Unit,
)
```

Inside the `DropdownMenu` composable, add the diagnostic item as the first entry (before the "Update Project Name" item):

```kotlin
if (isDebugEnabled) {
    DropdownMenuItem(
        text = { Text(text = stringResource(R.string.debug_log)) },
        onClick = {
            onDiagnosticClick()
            onDismissRequest()
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Outlined.BugReport,
                contentDescription = null,
            )
        },
    )
}
```

Add the import at the top of `ChatScreen.kt`:

```kotlin
import androidx.compose.material.icons.outlined.BugReport
```

- [ ] **Step 3: Wire ChatDropdownMenu call in ChatTopBar**

Update the `ChatTopBar` function signature to add the new parameters (around line 718):

```kotlin
@Composable
private fun ChatTopBar(
    title: String,
    isChatMenuEnabled: Boolean,
    isRunEnabled: Boolean,
    isMoreOptionsEnabled: Boolean,
    isProjectMenuEnabled: Boolean,
    isDebugEnabled: Boolean,
    buildProgress: Float,
    isBuildProgressVisible: Boolean,
    onBackAction: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
    onUpdateProjectNameClick: () -> Unit,
    onRunClick: () -> Unit,
    onInstallApkClick: () -> Unit,
    onExportChatItemClick: () -> Unit,
    onExportSourceCodeItemClick: () -> Unit,
    onExportApkItemClick: () -> Unit,
    onClearChatHistoryClick: () -> Unit,
    onDiagnosticClick: () -> Unit,
)
```

In the `ChatDropdownMenu(...)` call inside `ChatTopBar` (around line 765), add the new parameters:

```kotlin
ChatDropdownMenu(
    isDropdownMenuExpanded = isDropDownMenuExpanded,
    isChatMenuEnabled = isChatMenuEnabled,
    isProjectMenuEnabled = isProjectMenuEnabled,
    isDebugEnabled = isDebugEnabled,
    onDismissRequest = { isDropDownMenuExpanded = false },
    // ... existing params ...
    onClearChatHistoryClick = {
        onClearChatHistoryClick()
        isDropDownMenuExpanded = false
    },
    onDiagnosticClick = {
        onDiagnosticClick()
        isDropDownMenuExpanded = false
    },
)
```

- [ ] **Step 4: Wire ChatScreen composable to ChatTopBar**

In the `ChatScreen` composable, collect the new state (around line 150, after other collectAsStateWithLifecycle calls):

```kotlin
val isDebugEnabled by chatViewModel.isDebugEnabled.collectAsStateWithLifecycle()
```

Update the `ChatTopBar(...)` call in the Scaffold's `topBar` (around line 318) to pass the new parameters:

```kotlin
ChatTopBar(
    projectName ?: chatRoom.title,
    isChatMenuEnabled,
    runButtonEnabled,
    isMoreOptionsEnabled = isIdle,
    isProjectMenuEnabled,
    isDebugEnabled = isDebugEnabled,
    buildProgress = buildProgress.progress,
    isBuildProgressVisible = buildProgress.isVisible,
    onBackAction,
    scrollBehavior,
    chatViewModel::openProjectNameDialog,
    chatViewModel::runBuild,
    onInstallApkClick = { chatViewModel.installBuild() },
    // ... existing callbacks ...
    onClearChatHistoryClick = { isClearChatDialogOpen = true },
    onDiagnosticClick = onNavigateToDiagnostic,
)
```

- [ ] **Step 5: Add navigation callback to ChatScreen function signature**

Update `ChatScreen` (line 121) to add the navigation callback:

```kotlin
fun ChatScreen(
    chatViewModel: ChatViewModel = hiltViewModel(),
    onNavigateToAddPlatform: () -> Unit,
    onNavigateToDiagnostic: () -> Unit,
    onBackAction: () -> Unit
)
```

- [ ] **Step 6: Wire navigation in NavigationGraph**

In `NavigationGraph.kt`, update `chatScreenNavigation` (line 125-140) to pass the diagnostic navigation:

```kotlin
fun NavGraphBuilder.chatScreenNavigation(navController: NavHostController) {
    composable(
        Route.CHAT_ROOM,
        arguments = listOf(
            navArgument("chatRoomId") { type = NavType.IntType },
            navArgument("enabledPlatforms") { defaultValue = "" }
        )
    ) { backStackEntry ->
        val chatRoomId = backStackEntry.arguments?.getInt("chatRoomId") ?: return@composable
        ChatScreen(
            onNavigateToAddPlatform = {
                navController.navigate(Route.SETUP_ROUTE) { launchSingleTop = true }
            },
            onNavigateToDiagnostic = {
                navController.navigate(
                    Route.DIAGNOSTIC.replace("{chatRoomId}", "$chatRoomId")
                )
            },
            onBackAction = { navController.navigateUp() }
        )
    }
}
```

- [ ] **Step 7: Build to verify full compilation**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/vibe/app/presentation/ui/chat/ChatViewModel.kt app/src/main/kotlin/com/vibe/app/presentation/ui/chat/ChatScreen.kt app/src/main/kotlin/com/vibe/app/presentation/common/NavigationGraph.kt
git commit -m "feat(diagnostic): wire debug log menu entry in chat with navigation"
```

---

### Task 9: Final build verification

- [ ] **Step 1: Full assembleDebug**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Verify no lint errors on new files**

Run: `./gradlew :app:lintDebug 2>&1 | tail -20`
Expected: No new errors introduced

- [ ] **Step 3: Final commit if any adjustments were needed**

If any fixes were needed during build verification, commit them:

```bash
git add -A
git commit -m "fix(diagnostic): address build/lint issues in diagnostic viewer"
```
