package com.vibe.build.runtime.process

import java.io.File

/**
 * Launches native processes with bootstrap-configured env vars.
 *
 * Typical usage:
 * ```
 * val process = launcher.launch(
 *     executable = "/system/bin/toybox",
 *     args = listOf("ls", "/"),
 *     cwd = fs.usrRoot,
 * )
 * process.events.collect { event ->
 *     when (event) {
 *         is ProcessEvent.Stdout -> ...
 *         is ProcessEvent.Stderr -> ...
 *         is ProcessEvent.Exited -> println("exit=${event.code}")
 *     }
 * }
 * ```
 */
interface ProcessLauncher {

    /**
     * Launch a process.
     *
     * @param executable absolute path to the binary (not searched on PATH).
     * @param args command-line args EXCLUDING argv[0] (launcher prepends executable).
     * @param cwd working directory for the child. Must exist.
     * @param env extra/override env vars; merged on top of [ProcessEnvBuilder.build].
     * @return a live [NativeProcess]; caller should collect `events` exactly once.
     * @throws ProcessLaunchException if fork/execve failed.
     */
    suspend fun launch(
        executable: String,
        args: List<String>,
        cwd: File,
        env: Map<String, String> = emptyMap(),
    ): NativeProcess
}

class ProcessLaunchException(message: String) : RuntimeException(message)
