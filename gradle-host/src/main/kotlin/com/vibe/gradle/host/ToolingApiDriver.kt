package com.vibe.gradle.host

import org.gradle.tooling.BuildException
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.events.OperationType
import org.gradle.tooling.events.ProgressListener
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

/**
 * Runs a single Gradle build via Tooling API and reports the lifecycle as
 * structured HostEvent instances.
 *
 * Caller supplies the path to the bootstrapped Gradle distribution
 * (typically `$PREFIX/opt/gradle-8.10.2`). This class does not manage
 * daemon lifecycle explicitly — Tooling API handles that.
 */
internal class ToolingApiDriver(
    private val gradleDistribution: File,
) {
    init {
        require(gradleDistribution.isDirectory) {
            "Gradle distribution not found: $gradleDistribution"
        }
    }

    fun runBuild(request: HostRequest.RunBuild, emit: (HostEvent) -> Unit) {
        val projectDir = File(request.projectPath)
        require(projectDir.isDirectory) {
            "project path not a directory: $projectDir"
        }

        val started = System.currentTimeMillis()
        emit(HostEvent.BuildStart(request.requestId, started))

        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()

        val connector = GradleConnector.newConnector()
            .forProjectDirectory(projectDir)
            .useInstallation(gradleDistribution)

        try {
            connector.connect().use { connection ->
                val launcher = connection.newBuild()
                    .forTasks(*request.tasks.toTypedArray())
                    .withArguments(request.args)
                    .setStandardOutput(PrintStream(stdout))
                    .setStandardError(PrintStream(stderr))

                launcher.addProgressListener(
                    ProgressListener { event ->
                        emit(HostEvent.BuildProgress(request.requestId, event.displayName))
                    },
                    setOf(OperationType.TASK, OperationType.GENERIC),
                )
                launcher.run()
            }

            // Emit captured output as Log events
            emitCapturedStream(stdout.toByteArray(), "LIFECYCLE", request.requestId, emit)
            emitCapturedStream(stderr.toByteArray(), "ERROR", request.requestId, emit)

            val durationMs = System.currentTimeMillis() - started
            emit(HostEvent.BuildFinish(request.requestId, success = true, durationMs = durationMs, failureSummary = null))
        } catch (t: BuildException) {
            emitCapturedStream(stdout.toByteArray(), "LIFECYCLE", request.requestId, emit)
            emitCapturedStream(stderr.toByteArray(), "ERROR", request.requestId, emit)
            val durationMs = System.currentTimeMillis() - started
            emit(
                HostEvent.BuildFinish(
                    requestId = request.requestId,
                    success = false,
                    durationMs = durationMs,
                    failureSummary = t.message ?: t.javaClass.name,
                ),
            )
        } catch (t: Throwable) {
            emit(
                HostEvent.Error(
                    requestId = request.requestId,
                    exceptionClass = t.javaClass.name,
                    message = t.message ?: "(no message)",
                ),
            )
        }
    }

    private fun emitCapturedStream(
        bytes: ByteArray,
        level: String,
        requestId: String,
        emit: (HostEvent) -> Unit,
    ) {
        if (bytes.isEmpty()) return
        val text = String(bytes, Charsets.UTF_8)
        text.lineSequence().filter { it.isNotEmpty() }.forEach { line ->
            emit(HostEvent.Log(requestId = requestId, level = level, text = line))
        }
    }
}
