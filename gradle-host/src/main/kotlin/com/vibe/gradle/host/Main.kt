package com.vibe.gradle.host

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintStream

/**
 * GradleHost entry point.
 *
 * Reads JSON-line HostRequest objects from stdin and writes JSON-line
 * HostEvent objects to stdout. The very first line written is a
 * `Ready` event announcing versions. On `Shutdown` or stdin EOF, the
 * process exits 0.
 *
 * Usage (typically from VibeApp's GradleHostProcess):
 *   java -jar vibeapp-gradle-host-all.jar --gradle-distribution <path>
 */
private const val HOST_VERSION = "2.0.0-phase-2c"

fun main(args: Array<String>) {
    val gradleDistPath = parseGradleDistArg(args)
        ?: die("missing --gradle-distribution <path>; run with --help for usage")
    val gradleDist = File(gradleDistPath)
    if (!gradleDist.isDirectory) {
        die("Gradle distribution not found or not a directory: $gradleDist")
    }

    // Important: all stdout is the IPC channel. Don't println() anywhere
    // else. Use stderr for any internal logging.
    val out = PrintStream(System.out.buffered(), /* autoFlush = */ true, Charsets.UTF_8)
    val err = System.err

    val emit: (HostEvent) -> Unit = { ev ->
        out.println(IpcProtocol.encodeEvent(ev))
    }

    // Initial Ready signal
    emit(
        HostEvent.Ready(
            requestId = "",
            hostVersion = HOST_VERSION,
            toolingApiVersion = toolingApiVersion(),
        ),
    )

    val driver = ToolingApiDriver(gradleDistribution = gradleDist)

    BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8)).use { reader ->
        while (true) {
            val line = reader.readLine() ?: break  // stdin closed → exit
            if (line.isBlank()) continue

            val request = try {
                IpcProtocol.decodeRequest(line)
            } catch (t: Throwable) {
                err.println("[gradle-host] malformed request dropped: ${t.message}")
                continue
            }

            when (request) {
                is HostRequest.Ping -> emit(HostEvent.Pong(request.requestId))
                is HostRequest.Shutdown -> {
                    emit(HostEvent.Log(request.requestId, "LIFECYCLE", "shutting down"))
                    return
                }
                is HostRequest.RunBuild -> driver.runBuild(request, emit)
            }
        }
    }
}

private fun parseGradleDistArg(args: Array<String>): String? {
    var i = 0
    while (i < args.size) {
        if (args[i] == "--gradle-distribution" && i + 1 < args.size) {
            return args[i + 1]
        }
        if (args[i] == "--help") {
            println("Usage: vibeapp-gradle-host --gradle-distribution <path>")
            kotlin.system.exitProcess(0)
        }
        i++
    }
    return null
}

private fun toolingApiVersion(): String {
    // Prefer the Implementation-Version from GradleConnector's package
    // manifest — accurate, no hardcoding. But shaded fat JARs often
    // strip Package-level manifest entries, so fall back to the value
    // pinned in :gradle-host/build.gradle.kts.
    return org.gradle.tooling.GradleConnector::class.java.`package`?.implementationVersion
        ?: "9.3.1"
}

private fun die(message: String): Nothing {
    System.err.println("[gradle-host] error: $message")
    kotlin.system.exitProcess(2)
}
