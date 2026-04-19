/*
 * Low-level process launcher: fork + execve with three pipes.
 * Used by jni_process_launcher.c. No JNI types here — keep the C
 * interface testable standalone if needed.
 */

#ifndef VIBEAPP_PROCESS_LAUNCHER_H
#define VIBEAPP_PROCESS_LAUNCHER_H

#include <sys/types.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Spawn a child process.
 *
 * argv and envp are NULL-terminated arrays of C strings.
 *   argv[0] is the program name (conventional).
 *   envp entries are "KEY=VALUE" strings.
 * cwd may be NULL to inherit the parent's working directory.
 *
 * On success:
 *   - Returns the child PID.
 *   - Writes pipe fds to *out_stdout_fd (read), *out_stderr_fd (read),
 *     *out_stdin_fd (write). Caller owns these fds and must close them.
 *
 * On failure:
 *   - Returns -1.
 *   - errno is set.
 *   - All pipe fds are closed. Output parameters are set to -1.
 */
pid_t pl_launch(
    const char* executable_path,
    const char* const* argv,
    const char* const* envp,
    const char* cwd,
    int* out_stdout_fd,
    int* out_stderr_fd,
    int* out_stdin_fd
);

/*
 * Send a signal to a child process.
 * Returns 0 on success, errno on failure.
 */
int pl_signal(pid_t pid, int signum);

/*
 * Block until the child identified by `pid` exits.
 *
 * Returns:
 *   exit status (0-255) for normal exits
 *   128 + signal number for signal-terminated exits
 *   -1 on failure (errno set)
 */
int pl_wait(pid_t pid);

#ifdef __cplusplus
}
#endif

#endif /* VIBEAPP_PROCESS_LAUNCHER_H */
