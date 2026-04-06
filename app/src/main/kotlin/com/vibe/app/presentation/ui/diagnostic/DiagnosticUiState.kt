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
