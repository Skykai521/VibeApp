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
