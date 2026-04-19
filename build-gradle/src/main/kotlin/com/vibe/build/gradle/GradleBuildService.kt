package com.vibe.build.gradle

import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * App-facing entry point for running builds via the GradleHost
 * JVM process. Implementations are responsible for spawning +
 * tearing down the host and relaying IPC.
 */
interface GradleBuildService {
    /**
     * Spawn GradleHost (if not already running) and await the
     * initial [HostEvent.Ready] event. Returns the Tooling API
     * version reported by the host.
     */
    suspend fun start(gradleDistribution: File): String

    /**
     * Run the given Gradle tasks on the given project directory.
     * Emits a stream of [HostEvent]s ending with [HostEvent.BuildFinish]
     * or [HostEvent.Error]. The caller must collect the flow to
     * completion for the request to fully drain.
     */
    fun runBuild(
        projectDirectory: File,
        tasks: List<String>,
        args: List<String> = emptyList(),
    ): Flow<HostEvent>

    /**
     * Graceful shutdown: sends a Shutdown request + waits for the
     * host process to exit. Idempotent.
     */
    suspend fun shutdown()
}
