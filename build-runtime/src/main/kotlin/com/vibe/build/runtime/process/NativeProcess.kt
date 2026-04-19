package com.vibe.build.runtime.process

import kotlinx.coroutines.flow.Flow

/** POSIX signal numbers we care about. */
const val SIGTERM: Int = 15
const val SIGKILL: Int = 9
const val SIGHUP: Int = 1
const val SIGINT: Int = 2

/**
 * A running child process spawned via [ProcessLauncher.launch].
 *
 * Exactly ONE consumer should collect [events]. The flow emits
 * stdout/stderr chunks as they arrive and exactly one terminal
 * [ProcessEvent.Exited] before closing.
 */
interface NativeProcess {

    val pid: Int

    /**
     * Merged stream of stdout, stderr, and the terminal exit event.
     * The flow is a cold flow backed by background read threads; each
     * `collect` starts its own reader and must only be called once per
     * instance.
     */
    val events: Flow<ProcessEvent>

    /**
     * Blocks the calling coroutine until the process exits, returning
     * the exit status (0-255) or 128+signal for signaled exits, or -1
     * if waitpid failed.
     */
    suspend fun awaitExit(): Int

    /**
     * Send a POSIX signal to the process. Returns 0 on success,
     * non-zero errno on failure. Default is [SIGTERM].
     */
    fun signal(signum: Int = SIGTERM): Int

    /**
     * Write bytes to the child's stdin. Blocks if the child's pipe
     * buffer is full. Flushes after every call.
     */
    fun writeStdin(bytes: ByteArray)

    /**
     * Closes the stdin pipe (EOF to child) without affecting the child
     * process itself. Use this after a final [writeStdin] to signal
     * that no more input is coming.
     */
    fun closeStdin()
}
