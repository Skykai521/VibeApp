package com.vibe.app.feature.bootstrap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibe.app.data.datastore.SettingDataSource
import com.vibe.build.runtime.bootstrap.BootstrapFileSystem
import com.vibe.build.runtime.bootstrap.BootstrapStateStore
import com.vibe.build.runtime.bootstrap.RuntimeBootstrapper
import com.vibe.build.runtime.process.ProcessEvent
import com.vibe.build.runtime.process.ProcessLauncher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Named

@HiltViewModel
class BuildRuntimeDebugViewModel @Inject constructor(
    private val bootstrapper: RuntimeBootstrapper,
    private val store: BootstrapStateStore,
    private val launcher: ProcessLauncher,
    private val fs: BootstrapFileSystem,
    private val settingDataSource: SettingDataSource,
    @Named("bootstrapManifestUrl") private val manifestUrl: String,
    @Named("appCacheDir") private val cacheDir: File,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BuildRuntimeDebugState(manifestUrl = manifestUrl))
    val uiState: StateFlow<BuildRuntimeDebugState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            store.state.collectLatest { bootstrapState ->
                _uiState.update { it.copy(bootstrap = bootstrapState) }
            }
        }
        viewModelScope.launch {
            val override = settingDataSource.getDevBootstrapManifestUrl().orEmpty()
            _uiState.update { it.copy(devOverrideUrl = override) }
        }
    }

    fun triggerBootstrap() {
        viewModelScope.launch {
            bootstrapper.bootstrap(manifestUrl = manifestUrl) { /* store already updates UI */ }
        }
    }

    fun setDevOverrideUrl(newUrl: String) {
        viewModelScope.launch {
            val trimmed = newUrl.trim().ifEmpty { null }
            settingDataSource.updateDevBootstrapManifestUrl(trimmed)
            _uiState.update { it.copy(devOverrideUrl = newUrl) }
        }
    }

    fun launchTestProcess() {
        viewModelScope.launch {
            _uiState.update { it.copy(launchRunning = true, launchLog = "") }
            val log = StringBuilder()
            try {
                val proc = launcher.launch(
                    executable = "/system/bin/toybox",
                    args = listOf("echo", "debug-launch OK"),
                    cwd = cacheDir,
                )
                proc.events.collect { ev ->
                    appendEvent(log, ev)
                }
            } catch (t: Throwable) {
                log.append("[error] ${t.message}")
            }
            _uiState.update { it.copy(launchRunning = false, launchLog = log.toString()) }
        }
    }

    fun runHello() {
        viewModelScope.launch {
            _uiState.update { it.copy(launchRunning = true, launchLog = "") }
            val helloBinary = File(fs.componentInstallDir("hello"), "bin/hello")
            val log = StringBuilder()
            if (!helloBinary.isFile) {
                log.append("[error] $helloBinary not found — run Trigger bootstrap first")
            } else {
                try {
                    val proc = launcher.launch(
                        executable = helloBinary.absolutePath,
                        args = emptyList(),
                        cwd = cacheDir,
                    )
                    proc.events.collect { appendEvent(log, it) }
                } catch (t: Throwable) {
                    log.append("[error] ${t.message}")
                }
            }
            _uiState.update { it.copy(launchRunning = false, launchLog = log.toString()) }
        }
    }

    fun runJavaVersion() {
        viewModelScope.launch {
            _uiState.update { it.copy(launchRunning = true, launchLog = "") }
            val javaBinary = File(fs.componentInstallDir("jdk-17.0.13"), "bin/java")
            val log = StringBuilder()
            if (!javaBinary.isFile) {
                log.append("[error] $javaBinary not found — run Trigger bootstrap first")
            } else {
                try {
                    val proc = launcher.launch(
                        executable = javaBinary.absolutePath,
                        args = listOf("-version"),
                        cwd = cacheDir,
                    )
                    proc.events.collect { appendEvent(log, it) }
                } catch (t: Throwable) {
                    log.append("[error] ${t.message}")
                }
            }
            _uiState.update { it.copy(launchRunning = false, launchLog = log.toString()) }
        }
    }

    private fun appendEvent(log: StringBuilder, ev: ProcessEvent) {
        when (ev) {
            is ProcessEvent.Stdout -> log.append(String(ev.bytes, Charsets.UTF_8))
            is ProcessEvent.Stderr -> log.append("[stderr] ").append(String(ev.bytes, Charsets.UTF_8))
            is ProcessEvent.Exited -> log.append("\n[exit ${ev.code}]")
        }
    }
}
