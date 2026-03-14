package com.vibe.app.presentation.ui.startscreen

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.vibe.app.feature.projectinit.ProjectInitializer
import com.vibe.app.presentation.common.Route
import com.vibe.build.engine.model.BuildStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class StartViewModel @Inject constructor(
    private val projectInitializer: ProjectInitializer,
) : ViewModel() {

    private val tag = "StartViewModel"

    data class StartUiState(
        val isInitializing: Boolean = false,
        val statusMessage: String? = null,
    )

    private val _uiState = MutableStateFlow(StartUiState())
    val uiState: StateFlow<StartUiState> = _uiState.asStateFlow()

    fun initProject(navController: NavHostController) {
        if (_uiState.value.isInitializing) {
            return
        }

        viewModelScope.launch {
            Log.d(tag, "initProject button clicked")
            _uiState.update {
                it.copy(
                    isInitializing = true,
                    statusMessage = "Initializing template project and starting build…",
                )
            }

            val result = projectInitializer.initProject()
            Log.d(
                tag,
                "initProject result status=${result.status}, error=${result.errorMessage}, logs=${result.logs.size}",
            )
            _uiState.update {
                it.copy(
                    isInitializing = false,
                    statusMessage = if (result.status == BuildStatus.SUCCESS) {
                        val signedApk = result.artifacts.lastOrNull()?.path
                        if (signedApk.isNullOrBlank()) {
                            "Project initialized and build completed."
                        } else {
                            "Project initialized and build completed: $signedApk"
                        }
                    } else {
                        buildFailureMessage(result)
                    },
                )
            }
        }
    }

    private fun buildFailureMessage(result: com.vibe.build.engine.model.BuildResult): String {
        val header = result.errorMessage ?: "Project initialization failed."
        val logSummary = result.logs.takeLast(8).joinToString("\n") { entry ->
            val source = buildString {
                entry.sourcePath?.let {
                    append(" @ ")
                    append(it.substringAfterLast('/'))
                }
                entry.line?.let {
                    append(':')
                    append(it)
                }
            }
            "[${entry.stage}/${entry.level}] ${entry.message}$source"
        }
        return if (logSummary.isBlank()) {
            header
        } else {
            "$header\n\n$logSummary"
        }
    }
}
