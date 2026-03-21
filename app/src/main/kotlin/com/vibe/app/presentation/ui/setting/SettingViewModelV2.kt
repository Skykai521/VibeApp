package com.vibe.app.presentation.ui.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.vibe.app.data.database.entity.PlatformV2
import com.vibe.app.data.repository.SettingRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class SettingViewModelV2 @Inject constructor(
    private val settingRepository: SettingRepository
) : ViewModel() {

    private val _platformState = MutableStateFlow(listOf<PlatformV2>())
    val platformState: StateFlow<List<PlatformV2>> = _platformState.asStateFlow()

    private val _dialogState = MutableStateFlow(DialogState())
    val dialogState: StateFlow<DialogState> = _dialogState.asStateFlow()

    private val _switchedPlatformEvent = MutableSharedFlow<String>()
    val switchedPlatformEvent: SharedFlow<String> = _switchedPlatformEvent.asSharedFlow()

    init {
        fetchPlatforms()
    }

    fun fetchPlatforms() {
        viewModelScope.launch {
            val platforms = settingRepository.fetchPlatformV2s()
            _platformState.update { platforms }
        }
    }

    fun addPlatform(platform: PlatformV2) {
        viewModelScope.launch {
            if (platform.enabled) {
                // Disable all currently enabled platforms before adding new one
                val allPlatforms = settingRepository.fetchPlatformV2s()
                val othersEnabled = allPlatforms.filter { it.enabled }
                othersEnabled.forEach { other ->
                    settingRepository.updatePlatformV2(other.copy(enabled = false))
                }
                if (othersEnabled.isNotEmpty()) {
                    _switchedPlatformEvent.emit(platform.name)
                }
            }
            settingRepository.addPlatformV2(platform)
            fetchPlatforms()
        }
    }

    fun updatePlatform(platform: PlatformV2) {
        viewModelScope.launch {
            settingRepository.updatePlatformV2(platform)
            fetchPlatforms()
        }
    }

    fun deletePlatform(platform: PlatformV2) {
        viewModelScope.launch {
            settingRepository.deletePlatformV2(platform)
            fetchPlatforms()
        }
    }

    fun togglePlatformEnabled(platformId: Int) {
        val platform = _platformState.value.find { it.id == platformId } ?: return
        val willEnable = !platform.enabled
        if (willEnable) {
            viewModelScope.launch {
                val othersEnabled = _platformState.value.filter { it.enabled && it.id != platformId }
                othersEnabled.forEach { other ->
                    settingRepository.updatePlatformV2(other.copy(enabled = false))
                }
                settingRepository.updatePlatformV2(platform.copy(enabled = true))
                if (othersEnabled.isNotEmpty()) {
                    _switchedPlatformEvent.emit(platform.name)
                }
                fetchPlatforms()
            }
        } else {
            updatePlatform(platform.copy(enabled = false))
        }
    }

    fun openThemeDialog() = _dialogState.update { it.copy(isThemeDialogOpen = true) }

    fun closeThemeDialog() = _dialogState.update { it.copy(isThemeDialogOpen = false) }

    fun openDeleteDialog(platformId: Int) = _dialogState.update {
        it.copy(
            isDeleteDialogOpen = true,
            platformToDelete = platformId
        )
    }

    fun closeDeleteDialog() = _dialogState.update {
        it.copy(
            isDeleteDialogOpen = false,
            platformToDelete = null
        )
    }

    fun confirmDelete() {
        _dialogState.value.platformToDelete?.let { platformId ->
            val platform = _platformState.value.find { it.id == platformId }
            platform?.let { deletePlatform(it) }
        }
        closeDeleteDialog()
    }

    data class DialogState(
        val isThemeDialogOpen: Boolean = false,
        val isDeleteDialogOpen: Boolean = false,
        val platformToDelete: Int? = null
    )
}
