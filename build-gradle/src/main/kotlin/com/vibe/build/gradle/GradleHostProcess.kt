package com.vibe.build.gradle

import com.vibe.build.runtime.bootstrap.BootstrapFileSystem
import com.vibe.build.runtime.process.ProcessEvent
import com.vibe.build.runtime.process.ProcessLauncher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Wraps the live GradleHost JVM subprocess.
 *
 * One instance = one host process. Requests are serialized (one at
 * a time) — for now. Phase 2d may add multiplexing.
 */
internal class GradleHostProcess(
    private val launcher: ProcessLauncher,
    private val fs: BootstrapFileSystem,
    private val hostJar: File,
    private val javaBinary: File,
) {
    private val events = MutableSharedFlow<HostEvent>(extraBufferCapacity = 1024)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var process: com.vibe.build.runtime.process.NativeProcess
    private var readerJob: Job? = null

    val eventFlow: Flow<HostEvent> = events.asSharedFlow()

    /**
     * Start the host JVM and wait for its Ready event.
     * Returns the reported Tooling API version.
     */
    suspend fun start(gradleDistribution: File): String = withContext(Dispatchers.IO) {
        check(!::process.isInitialized) { "GradleHostProcess.start called twice" }

        val cwd = fs.componentInstallDir("vibeapp-gradle-host")
        cwd.mkdirs()

        process = launcher.launch(
            executable = javaBinary.absolutePath,
            args = listOf(
                "-jar", hostJar.absolutePath,
                "--gradle-distribution", gradleDistribution.absolutePath,
            ),
            cwd = cwd,
        )

        val readyDeferred = CompletableDeferred<String>()
        val lineBuffer = StringBuilder()

        readerJob = scope.launch {
            process.events.collect { ev ->
                when (ev) {
                    is ProcessEvent.Stdout -> {
                        val chunk = String(ev.bytes, Charsets.UTF_8)
                        lineBuffer.append(chunk)
                        while (true) {
                            val newlineIdx = lineBuffer.indexOf('\n')
                            if (newlineIdx < 0) break
                            val line = lineBuffer.substring(0, newlineIdx)
                            lineBuffer.delete(0, newlineIdx + 1)
                            if (line.isBlank()) continue
                            try {
                                val hostEvent = IpcProtocol.decodeEvent(line)
                                if (hostEvent is HostEvent.Ready && !readyDeferred.isCompleted) {
                                    readyDeferred.complete(hostEvent.toolingApiVersion)
                                }
                                events.emit(hostEvent)
                            } catch (t: Throwable) {
                                // Ignore malformed lines; log to stderr-equivalent
                                // (no Android Log.e here — pure JVM + Android
                                // compatibility; see diagnostic pipeline Phase 2d).
                            }
                        }
                    }
                    is ProcessEvent.Stderr -> {
                        // stderr is informational; surface via Log events if
                        // multiple lines. For now we silently ignore.
                    }
                    is ProcessEvent.Exited -> {
                        if (!readyDeferred.isCompleted) {
                            readyDeferred.completeExceptionally(
                                IllegalStateException("GradleHost exited before Ready (code=${ev.code})"),
                            )
                        }
                    }
                }
            }
        }

        readyDeferred.await()
    }

    /**
     * Write a request to the host's stdin. Caller gets the event
     * stream from [eventFlow] and filters on [HostRequest.requestId].
     */
    fun writeRequest(request: HostRequest) {
        val line = IpcProtocol.encodeRequest(request) + "\n"
        process.writeStdin(line.toByteArray(Charsets.UTF_8))
    }

    suspend fun shutdown() {
        val id = "shutdown-${System.nanoTime()}"
        writeRequest(HostRequest.Shutdown(id))
        process.closeStdin()
        process.awaitExit()
        readerJob?.cancel()
    }
}
