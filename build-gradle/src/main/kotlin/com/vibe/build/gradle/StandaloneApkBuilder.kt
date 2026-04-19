package com.vibe.build.gradle

import com.vibe.build.runtime.bootstrap.BootstrapFileSystem
import com.vibe.build.runtime.bootstrap.BootstrapState
import com.vibe.build.runtime.bootstrap.RuntimeBootstrapper
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.toList

/**
 * Builds a Shadow-template project's **standalone** (non-plugin) APK
 * by running `:app:assembleNormalDebug` in the on-device Gradle daemon.
 *
 * Two distinct callers need this same path:
 *   - [com.vibe.app.feature.agent.tool.InstallApkV2Tool] — the agent's
 *     install path; the plugin APK produced by `assemble_debug_v2` would
 *     crash standalone (Shadow-transformed Activities need the host
 *     runtime loaded), so install has to build the `normal` flavor
 *     itself.
 *   - ChatViewModel.installProjectApk() — the top-bar Install APK menu
 *     item; same reasoning.
 *
 * Keeping the logic here (rather than a private helper duplicated on
 * each caller) prevents drift: the flavor name, task name, and the
 * post-build APK path all have to stay in sync with the template's
 * AGP flavor config, and one place is easier to keep correct.
 */
@Singleton
class StandaloneApkBuilder @Inject constructor(
    private val gradleBuildService: GradleBuildService,
    private val bootstrapper: RuntimeBootstrapper,
    private val bootstrapFs: BootstrapFileSystem,
) {

    /**
     * Ensure the on-device toolchain is extracted, then run
     * `:app:assembleNormalDebug` against the given workspace.
     *
     * Returns the absolute path to the built `app-normal-debug.apk`.
     *
     * @throws IllegalStateException if the bootstrap fails, Gradle
     *     doesn't emit a terminal event, the build itself fails, or
     *     the expected APK is missing after a successful build.
     */
    suspend fun buildStandaloneApk(workspaceDir: File): File {
        val gradleDist = bootstrapFs.componentInstallDir("gradle-9.3.1")
        if (!gradleDist.isDirectory) {
            var lastState: BootstrapState? = null
            bootstrapper.bootstrap { state -> lastState = state }
            (lastState as? BootstrapState.Failed)?.let { failed ->
                throw IllegalStateException("Bootstrap failed: ${failed.reason}")
            }
            if (!gradleDist.isDirectory) {
                throw IllegalStateException("Bootstrap completed but $gradleDist still missing")
            }
        }
        gradleBuildService.start(gradleDist)
        val events = gradleBuildService.runBuild(
            projectDirectory = workspaceDir,
            tasks = listOf(":app:assembleNormalDebug"),
            args = listOf("--stacktrace"),
        ).toList()
        val finish = events.filterIsInstance<HostEvent.BuildFinish>().firstOrNull()
            ?: throw IllegalStateException("Gradle returned no BuildFinish event")
        if (!finish.success) {
            throw IllegalStateException(
                finish.failureSummary?.lineSequence()?.firstOrNull()?.take(300)
                    ?: "Gradle build failed",
            )
        }
        val apk = File(workspaceDir, "app/build/outputs/apk/normal/debug/app-normal-debug.apk")
        if (!apk.isFile) {
            throw IllegalStateException("Built normal-debug APK missing at $apk")
        }
        return apk
    }
}
