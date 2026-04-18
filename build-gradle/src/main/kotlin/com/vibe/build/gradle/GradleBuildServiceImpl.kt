package com.vibe.build.gradle

import com.vibe.build.runtime.bootstrap.BootstrapFileSystem
import com.vibe.build.runtime.process.ProcessEnvBuilder
import com.vibe.build.runtime.process.ProcessLauncher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.transformWhile
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GradleBuildServiceImpl @Inject constructor(
    private val launcher: ProcessLauncher,
    private val envBuilder: ProcessEnvBuilder,
    private val extractor: GradleHostExtractor,
    private val fs: BootstrapFileSystem,
) : GradleBuildService {

    private var hostProcess: GradleHostProcess? = null

    override suspend fun start(gradleDistribution: File): String {
        val existing = hostProcess
        if (existing != null) {
            // Already started — return its reported version by
            // triggering a Ping; for now, just return a sentinel.
            // Phase 2d will cache the Ready value properly.
            return "already-running"
        }

        val jar = extractor.ensureExtracted()
        val javaBinary = File(fs.componentInstallDir("jdk-17.0.13"), "bin/java")

        val host = GradleHostProcess(
            launcher = launcher,
            fs = fs,
            hostJar = jar,
            javaBinary = javaBinary,
        )
        val version = host.start(gradleDistribution)
        hostProcess = host
        return version
    }

    override fun runBuild(
        projectDirectory: File,
        tasks: List<String>,
        args: List<String>,
    ): Flow<HostEvent> {
        val host = checkNotNull(hostProcess) { "GradleBuildService not started" }
        val requestId = "build-${System.nanoTime()}"
        host.writeRequest(
            HostRequest.RunBuild(
                requestId = requestId,
                projectPath = projectDirectory.absolutePath,
                tasks = tasks,
                args = args,
            ),
        )
        return host.eventFlow
            .filter { it.requestId == requestId }
            .transformWhile { event ->
                emit(event)
                event !is HostEvent.BuildFinish && event !is HostEvent.Error
            }
    }

    override suspend fun shutdown() {
        hostProcess?.shutdown()
        hostProcess = null
    }
}
