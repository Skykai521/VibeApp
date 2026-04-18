#include "process_launcher.h"

#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>

/*
 * Close inherited file descriptors 3..1023 in the child after dup2().
 * Any fd held by the Android runtime but lacking FD_CLOEXEC would
 * otherwise leak into execve(). We use a plain loop because close_range()
 * is API 30+ and we must support minSdk 29.
 */
static void close_inherited_fds(void) {
    for (int fd = 3; fd < 1024; fd++) {
        /* ignore errors — fds may not be open */
        (void)close(fd);
    }
}

/*
 * Set FD_CLOEXEC on a file descriptor so it does not leak across execve()
 * in the parent's process space.
 */
static int set_close_on_exec(int fd) {
    int flags = fcntl(fd, F_GETFD, 0);
    if (flags < 0) {
        return -1;
    }
    return fcntl(fd, F_SETFD, flags | FD_CLOEXEC);
}

/*
 * Safe close that ignores -1 and preserves errno.
 */
static void safe_close(int fd) {
    if (fd >= 0) {
        int saved = errno;
        close(fd);
        errno = saved;
    }
}

pid_t pl_launch(
    const char* executable_path,
    const char* const* argv,
    const char* const* envp,
    const char* cwd,
    int* out_stdout_fd,
    int* out_stderr_fd,
    int* out_stdin_fd
) {
    if (out_stdout_fd) *out_stdout_fd = -1;
    if (out_stderr_fd) *out_stderr_fd = -1;
    if (out_stdin_fd)  *out_stdin_fd  = -1;

    int in_pipe[2]  = {-1, -1};  /* parent writes to [1], child reads from [0] */
    int out_pipe[2] = {-1, -1};  /* child writes to [1], parent reads from [0] */
    int err_pipe[2] = {-1, -1};

    if (pipe(in_pipe) < 0)  goto fail;
    if (pipe(out_pipe) < 0) goto fail;
    if (pipe(err_pipe) < 0) goto fail;

    /* Parent's ends must be FD_CLOEXEC so a subsequent fork/exec cycle
     * in this process doesn't leak them. */
    set_close_on_exec(in_pipe[1]);
    set_close_on_exec(out_pipe[0]);
    set_close_on_exec(err_pipe[0]);

    pid_t pid = fork();
    if (pid < 0) goto fail;

    if (pid == 0) {
        /* Child process */

        /* Redirect stdio to our pipe ends. */
        if (dup2(in_pipe[0], STDIN_FILENO)   < 0) _exit(126);
        if (dup2(out_pipe[1], STDOUT_FILENO) < 0) _exit(126);
        if (dup2(err_pipe[1], STDERR_FILENO) < 0) _exit(126);

        /* Close the raw pipe fds; they've all been duped. */
        close(in_pipe[0]);  close(in_pipe[1]);
        close(out_pipe[0]); close(out_pipe[1]);
        close(err_pipe[0]); close(err_pipe[1]);

        /* Close anything else the parent may have had open. */
        close_inherited_fds();

        if (cwd != NULL && cwd[0] != '\0') {
            if (chdir(cwd) < 0) {
                _exit(126);
            }
        }

        execve(executable_path, (char* const*)argv, (char* const*)envp);
        /* If execve returns, it failed. */
        _exit(127);
    }

    /* Parent process */
    /* Close the child's ends. */
    safe_close(in_pipe[0]);
    safe_close(out_pipe[1]);
    safe_close(err_pipe[1]);

    if (out_stdin_fd)  *out_stdin_fd  = in_pipe[1];
    if (out_stdout_fd) *out_stdout_fd = out_pipe[0];
    if (out_stderr_fd) *out_stderr_fd = err_pipe[0];

    return pid;

fail: {
    int saved = errno;
    safe_close(in_pipe[0]);  safe_close(in_pipe[1]);
    safe_close(out_pipe[0]); safe_close(out_pipe[1]);
    safe_close(err_pipe[0]); safe_close(err_pipe[1]);
    errno = saved;
    return -1;
}
}

int pl_signal(pid_t pid, int signum) {
    if (kill(pid, signum) == 0) {
        return 0;
    }
    return errno;
}

int pl_wait(pid_t pid) {
    int status = 0;
    pid_t result;

    do {
        result = waitpid(pid, &status, 0);
    } while (result < 0 && errno == EINTR);

    if (result < 0) {
        return -1;
    }

    if (WIFEXITED(status)) {
        return WEXITSTATUS(status);
    }
    if (WIFSIGNALED(status)) {
        return 128 + WTERMSIG(status);
    }
    return -1;
}
