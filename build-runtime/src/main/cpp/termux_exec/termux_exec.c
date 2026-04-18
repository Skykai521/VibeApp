#define _GNU_SOURCE
#include "termux_exec.h"

#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#ifndef VIBEAPP_PREFIX
#define VIBEAPP_PREFIX "/data/user/0/com.vibe.app/files/usr"
#endif

typedef int (*execve_t)(const char*, char* const[], char* const[]);

/*
 * Resolve real libc execve via dlsym(RTLD_NEXT, ...). Cached in a
 * static var; initialized lazily the first time our override runs.
 */
static execve_t real_execve(void) {
    static execve_t cached = NULL;
    if (cached == NULL) {
        cached = (execve_t) dlsym(RTLD_NEXT, "execve");
    }
    return cached;
}

/*
 * Read the first line of `path` into `buf` (up to `buf_size - 1` bytes,
 * including newline). On success, returns the number of bytes read and
 * null-terminates `buf`. On failure, returns -1 and errno.
 *
 * This is safer than fopen()/fgets() because it avoids FILE* buffers
 * that may not survive across a fork() into our override context.
 */
static ssize_t read_first_line(const char* path, char* buf, size_t buf_size) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        return -1;
    }
    ssize_t n = read(fd, buf, buf_size - 1);
    int saved_errno = errno;
    close(fd);
    if (n <= 0) {
        errno = saved_errno;
        return -1;
    }
    buf[n] = '\0';
    char* nl = memchr(buf, '\n', n);
    if (nl != NULL) {
        *nl = '\0';
        return nl - buf;
    }
    return n;
}

/*
 * Parse a shebang line of the form "#!/usr/bin/env <interp>[ <arg>]".
 * On match, returns 1 and writes into *out_interp_name a pointer to
 * the interpreter name within the line buffer (null-terminated), and
 * into *out_extra_arg either NULL or a pointer to the extra argument.
 * On no match, returns 0.
 *
 * NOTE: mutates the buffer pointed to by `line` (null-terminates the
 * interpreter name). Caller must pass a mutable copy.
 */
static int parse_env_shebang(char* line, char** out_interp_name, char** out_extra_arg) {
    static const char kPrefix[] = "#!/usr/bin/env ";
    static const size_t kPrefixLen = sizeof(kPrefix) - 1;

    if (strncmp(line, kPrefix, kPrefixLen) != 0) {
        return 0;
    }

    char* interp = line + kPrefixLen;
    while (*interp == ' ' || *interp == '\t') interp++;
    if (*interp == '\0') {
        return 0;
    }

    *out_interp_name = interp;
    *out_extra_arg = NULL;

    char* space = strpbrk(interp, " \t");
    if (space != NULL) {
        *space = '\0';
        char* extra = space + 1;
        while (*extra == ' ' || *extra == '\t') extra++;
        if (*extra != '\0') {
            *out_extra_arg = extra;
        }
    }
    return 1;
}

/*
 * Build an argv that executes `<VIBEAPP_PREFIX>/bin/<interp>` with
 * `original_path` as the first arg (so the interpreter knows which
 * script to run), followed by the original argv[1..] entries.
 *
 * Returns a NULL-terminated heap-allocated char**, or NULL on OOM.
 * Caller must free each element plus the outer array.
 */
static char** build_rewritten_argv(
    const char* interp_bin_path,
    const char* original_path,
    char* extra_arg,
    char* const original_argv[]
) {
    size_t orig_count = 0;
    while (original_argv[orig_count] != NULL) orig_count++;

    /*
     * New argv layout:
     *   [0] interp_bin_path   (e.g. "$PREFIX/bin/sh")
     *   [1] extra_arg         (if present, e.g. "-eu" from "#!/usr/bin/env sh -eu")
     *   [n] original_path     (the script)
     *   [n+1..] original_argv[1..]
     *   [trailing] NULL
     */
    size_t new_count = orig_count + 1 + (extra_arg != NULL ? 1 : 0);
    char** new_argv = (char**)calloc(new_count + 1, sizeof(char*));
    if (new_argv == NULL) return NULL;

    size_t idx = 0;
    new_argv[idx++] = strdup(interp_bin_path);
    if (extra_arg != NULL) {
        new_argv[idx++] = strdup(extra_arg);
    }
    new_argv[idx++] = strdup(original_path);
    for (size_t i = 1; i < orig_count; i++) {
        new_argv[idx++] = strdup(original_argv[i]);
    }
    new_argv[idx] = NULL;

    /* Check allocation failures */
    for (size_t i = 0; i < idx; i++) {
        if (new_argv[i] == NULL) {
            for (size_t j = 0; j < idx; j++) free(new_argv[j]);
            free(new_argv);
            return NULL;
        }
    }
    return new_argv;
}

static void free_argv(char** argv) {
    if (argv == NULL) return;
    for (char** p = argv; *p != NULL; p++) {
        free(*p);
    }
    free(argv);
}

/*
 * execve() override. If `path` refers to a file whose first line is
 * "#!/usr/bin/env <interp>", rewrite argv to exec
 * "$VIBEAPP_PREFIX/bin/<interp>" with the script path appended, and
 * delegate to real_execve(). Otherwise, pass through unchanged.
 */
int execve(const char* path, char* const argv[], char* const envp[]) {
    execve_t real = real_execve();
    if (real == NULL) {
        errno = ENOSYS;
        return -1;
    }

    char line_buf[256];
    if (read_first_line(path, line_buf, sizeof(line_buf)) < 0) {
        return real(path, argv, envp);
    }

    char* interp = NULL;
    char* extra_arg = NULL;
    if (!parse_env_shebang(line_buf, &interp, &extra_arg)) {
        return real(path, argv, envp);
    }

    char interp_bin_path[1024];
    int n = snprintf(
        interp_bin_path, sizeof(interp_bin_path),
        "%s/bin/%s", VIBEAPP_PREFIX, interp
    );
    if (n < 0 || (size_t)n >= sizeof(interp_bin_path)) {
        /* path too long; fall back to original exec */
        return real(path, argv, envp);
    }

    if (access(interp_bin_path, X_OK) != 0) {
        /* interpreter not installed; fall through so caller gets the
         * real error from attempting to exec the script */
        return real(path, argv, envp);
    }

    char** new_argv = build_rewritten_argv(
        interp_bin_path, path, extra_arg, argv
    );
    if (new_argv == NULL) {
        errno = ENOMEM;
        return -1;
    }

    int rc = real(interp_bin_path, new_argv, envp);
    int saved_errno = errno;
    free_argv(new_argv);
    errno = saved_errno;
    return rc;
}
