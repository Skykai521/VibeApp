package com.vibe.build.runtime.process

/**
 * Thin JNI bridge to `libbuildruntime.so`. Used internally by
 * [NativeProcessImpl] and [NativeProcessLauncher]. Not intended for
 * direct consumption outside the module.
 */
internal object NativeProcessBridge {

    init {
        System.loadLibrary("buildruntime")
    }

    /**
     * Spawn a child process.
     *
     * @return int[4] = {pid, stdoutFd, stderrFd, stdinFd}.
     *   pid == -1 on failure; other fds are -1 on failure.
     *
     * Ownership of fds transfers to the JVM. Callers MUST close them
     * via `ParcelFileDescriptor.adoptFd(fd).close()` or the equivalent.
     */
    @JvmStatic
    external fun nativeLaunch(
        executable: String,
        argv: Array<String>,
        envp: Array<String>,
        cwd: String?,
    ): IntArray

    /**
     * Send a signal to `pid`. Returns 0 on success, non-zero errno on failure.
     */
    @JvmStatic
    external fun nativeSignal(pid: Int, signum: Int): Int

    /**
     * Block until `pid` exits. Returns:
     *   - 0-255: normal exit status
     *   - 128+signal: terminated by signal
     *   - -1: waitpid failed (pid missing, orphaned, etc.)
     */
    @JvmStatic
    external fun nativeWaitFor(pid: Int): Int
}
