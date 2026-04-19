package com.vibe.app.feature.bootstrap

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibe.app.data.datastore.SettingDataSource
import com.vibe.app.plugin.v2.ShadowPluginRepoExtractor
import com.vibe.build.gradle.ApkInstaller
import com.vibe.build.gradle.GradleBuildService
import com.vibe.build.gradle.GradleProjectInitializer
import com.vibe.build.gradle.HostEvent
import com.vibe.build.runtime.bootstrap.BootstrapFileSystem
import com.vibe.build.runtime.bootstrap.BootstrapStateStore
import com.vibe.build.runtime.bootstrap.RuntimeBootstrapper
import com.vibe.build.runtime.process.ProcessEvent
import com.vibe.build.runtime.process.ProcessLauncher
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
    @ApplicationContext private val appContext: Context,
    private val bootstrapper: RuntimeBootstrapper,
    private val store: BootstrapStateStore,
    private val launcher: ProcessLauncher,
    private val fs: BootstrapFileSystem,
    private val settingDataSource: SettingDataSource,
    private val gradleBuildService: GradleBuildService,
    private val projectInitializer: GradleProjectInitializer,
    private val shadowPluginRepoExtractor: ShadowPluginRepoExtractor,
    private val apkInstaller: ApkInstaller,
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

    fun runGradleVersion() {
        viewModelScope.launch {
            _uiState.update { it.copy(launchRunning = true, launchLog = "") }
            val javaBinary = File(fs.componentInstallDir("jdk-17.0.13"), "bin/java")
            val launcherJar = File(
                fs.componentInstallDir("gradle-9.3.1"),
                "lib/gradle-launcher-9.3.1.jar",
            )
            val log = StringBuilder()
            if (!javaBinary.isFile) {
                log.append("[error] $javaBinary not found — run Trigger bootstrap first")
            } else if (!launcherJar.isFile) {
                log.append("[error] $launcherJar not found — run Trigger bootstrap first")
            } else {
                // Gradle wants GRADLE_USER_HOME to exist.
                val gradleUserHome = File(fs.usrRoot.parentFile, ".gradle")
                gradleUserHome.mkdirs()
                try {
                    val proc = launcher.launch(
                        executable = javaBinary.absolutePath,
                        args = listOf(
                            "-cp",
                            launcherJar.absolutePath,
                            "org.gradle.launcher.GradleMain",
                            "--version",
                            "--no-daemon",
                        ),
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

    fun installCounterApk() {
        viewModelScope.launch {
            val log = StringBuilder()
            try {
                val projectDir = File(fs.usrRoot.parentFile, "projects/counter")
                val apk = File(projectDir, "app/build/outputs/apk/debug/app-debug.apk")
                if (!apk.exists()) {
                    log.append("[error] $apk not found — run Generate Counter project first")
                } else {
                    log.append("[install] firing system installer for ${apk.absolutePath} (${apk.length()} bytes)\n")
                    apkInstaller.install(apk)
                    log.append("[install] system installer launched")
                }
            } catch (t: Throwable) {
                log.append("\n[throw] ${t.javaClass.simpleName}: ${t.message}")
            }
            _uiState.update { it.copy(launchLog = log.toString()) }
        }
    }

    fun runProbeAssembleDebug() {
        viewModelScope.launch {
            _uiState.update { it.copy(launchRunning = true, launchLog = "") }
            val log = StringBuilder()
            try {
                val sdkDir = fs.componentInstallDir("android-sdk-36.0.0")
                val gradleDist = fs.componentInstallDir("gradle-9.3.1")
                val gradleUserHome = File(fs.usrRoot.parentFile, ".gradle").also { it.mkdirs() }
                val projectDir = File(fs.usrRoot.parentFile, "projects/counter")
                projectDir.parentFile?.mkdirs()

                val shadowPluginRepo = shadowPluginRepoExtractor.extractIfNeeded()
                projectInitializer.initialize(
                    GradleProjectInitializer.Input(
                        templateName = "KotlinComposeApp",
                        projectName = "Counter",
                        packageName = "com.vibe.counter",
                        sdkDir = sdkDir,
                        gradleUserHome = gradleUserHome,
                        destinationDir = projectDir,
                        shadowPluginRepo = shadowPluginRepo,
                    ),
                )

                gradleBuildService.start(gradleDist)
                gradleBuildService.runBuild(
                    projectDirectory = projectDir,
                    tasks = listOf(":app:assembleDebug"),
                    args = emptyList(),
                ).collect { event ->
                    when (event) {
                        is HostEvent.BuildProgress -> log.append("[progress] ${event.message}\n")
                        is HostEvent.Log -> log.append("[${event.level}] ${event.text}\n")
                        is HostEvent.BuildFinish -> {
                            log.append("\n[finish] success=${event.success} durationMs=${event.durationMs}")
                            event.failureSummary?.let { log.append("\n[failureSummary] $it") }
                        }
                        is HostEvent.Error -> log.append("\n[error] ${event.exceptionClass}: ${event.message}")
                        else -> {}
                    }
                }
                val apk = File(projectDir, "app/build/outputs/apk/debug/app-debug.apk")
                log.append("\n[apk] ${apk.absolutePath} exists=${apk.exists()} size=${apk.length()}")
            } catch (t: Throwable) {
                log.append("\n[throw] ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                _uiState.update { it.copy(launchRunning = false, launchLog = log.toString()) }
            }
        }
    }
}
