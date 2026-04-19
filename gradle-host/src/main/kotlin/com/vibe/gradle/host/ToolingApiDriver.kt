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
 * (typically `$PREFIX/opt/gradle-9.3.1`). This class does not manage
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
                // Gradle Daemon JVM inherits hardcoded Termux paths
                // (java.io.tmpdir = /data/data/com.termux/files/usr/tmp,
                // user.home = /data/data/com.termux/files/home) that don't
                // exist on our device. Propagate valid values from this JVM,
                // which Main.kt already sets before spawning.
                val daemonJvmArgs = buildList {
                    System.getProperty("java.io.tmpdir")?.let { add("-Djava.io.tmpdir=$it") }
                    System.getProperty("user.home")?.let { add("-Duser.home=$it") }
                }
                val launcher = connection.newBuild()
                    .forTasks(*request.tasks.toTypedArray())
                    .withArguments(request.args)
                    .setJvmArguments(daemonJvmArgs)
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
                    failureSummary = buildCauseChain(t),
                ),
            )
        } catch (t: Throwable) {
            emit(
                HostEvent.Error(
                    requestId = request.requestId,
                    exceptionClass = t.javaClass.name,
                    message = buildCauseChain(t),
                ),
            )
        }
    }

    private fun buildCauseChain(t: Throwable): String {
        val sb = StringBuilder()
        var current: Throwable? = t
        var depth = 0
        while (current != null && depth < 10) {
            if (sb.isNotEmpty()) sb.append(" | caused by ")
            sb.append(current.javaClass.name).append(": ").append(current.message ?: "(no message)")
            // Include the first 3 frames from packages we care about —
            // com.tencent.shadow.* for Shadow plugin issues,
            // com.android.build.* for AGP DSL issues. Frames from
            // org.gradle.* / kotlin reflection / java.* are noise.
            val interesting = current.stackTrace
                .filter { frame ->
                    frame.className.startsWith("com.tencent.shadow") ||
                        frame.className.startsWith("com.android.build")
                }
                .take(3)
            for (frame in interesting) {
                sb.append("\n    at ").append(frame.className)
                    .append('.').append(frame.methodName)
                    .append('(').append(frame.fileName ?: "?")
                    .append(':').append(frame.lineNumber).append(')')
            }
            current = current.cause
            depth++
        }
        return sb.toString()
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
