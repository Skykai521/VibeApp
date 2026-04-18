# v2.0 Phase 1b: Native Process Runtime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** In the `:build-runtime` module, build a native-process runtime that can spawn any on-device binary with custom argv/envp/cwd and stream its stdout/stderr/exit as a `Flow<ProcessEvent>`. Phase 1b proves it by launching `/system/bin/toybox` on the emulator.

**Architecture:** Three layers. **C layer** (`process_launcher.c`) does POSIX fork/execve with three pipes + waitpid. **JNI layer** (`jni_process_launcher.c`) marshals Kotlin arrays to `char**` and returns an `int[]` of `{pid, stdoutFd, stderrFd, stdinFd}`. **Kotlin layer** (`NativeProcess`, `NativeProcessImpl`, `ProcessLauncher`, `ProcessEnvBuilder`) wraps the fds as `ParcelFileDescriptor` → `FileInputStream`, emits chunked reads over a `channelFlow`, and exposes a suspend API.

**Tech Stack:** Android NDK (CMake build integrated via AGP's `externalNativeBuild`), plain C (no STL/Boost/libc++), Kotlin coroutines + Flow, `android.os.ParcelFileDescriptor` (API 13+, public) for fd→stream conversion. No `libtermux-exec.so` yet — that lands in Phase 1c alongside the downloaded JDK's shebang handling.

---

## Spec References

- Design doc: `docs/superpowers/specs/2026-04-18-v2-gradle-compose-arch-design.md`
- This plan implements §3.6 (`NativeProcess` API) and the fork/exec substrate of §3.7. Full `libtermux-exec.so` LD_PRELOAD shebang correction is deferred to Phase 1c because it's only meaningful against downloaded binaries (Phase 1a/1b don't download binaries yet).
- Prior plans (completed): `docs/superpowers/plans/2026-04-18-v2-phase-0-foundation.md`, `docs/superpowers/plans/2026-04-18-v2-phase-1a-bootstrap-download.md`

## Working Directory

**All file operations happen in the git worktree:** `/Users/skykai/Documents/work/VibeApp/.worktrees/v2-arch`

Branch: `v2-arch`. Current HEAD at plan write time: `60ec101` (Phase 1a complete).

## File Structure

**Create (all new, under `build-runtime/`):**

| File | Responsibility |
|---|---|
| `src/main/cpp/CMakeLists.txt` | Minimal CMake project; builds `libbuildruntime.so` from all `.c` sources |
| `src/main/cpp/process_launcher.h` | C header: `pl_launch`, `pl_signal`, `pl_wait` |
| `src/main/cpp/process_launcher.c` | POSIX fork/execve/pipes/waitpid |
| `src/main/cpp/jni_process_launcher.c` | JNI-to-C marshaling (string arrays → char\*\*; int[] return) |
| `src/main/kotlin/com/vibe/build/runtime/process/NativeProcessBridge.kt` | `@JvmStatic external` declarations; loads `libbuildruntime.so` |
| `src/main/kotlin/com/vibe/build/runtime/process/ProcessEvent.kt` | `sealed interface ProcessEvent` (Stdout/Stderr/Exited) |
| `src/main/kotlin/com/vibe/build/runtime/process/NativeProcess.kt` | Public interface + `SIGTERM`/`SIGKILL` constants |
| `src/main/kotlin/com/vibe/build/runtime/process/NativeProcessImpl.kt` | Wraps `{pid, stdoutFd, stderrFd, stdinFd}` as `Flow<ProcessEvent>` |
| `src/main/kotlin/com/vibe/build/runtime/process/ProcessLauncher.kt` | Public interface: `suspend fun launch(...)` |
| `src/main/kotlin/com/vibe/build/runtime/process/NativeProcessLauncher.kt` | `ProcessLauncher` impl, injects `ProcessEnvBuilder` |
| `src/main/kotlin/com/vibe/build/runtime/process/ProcessEnvBuilder.kt` | Composes `PATH`, `HOME`, `TMPDIR`, `LD_LIBRARY_PATH`, `JAVA_HOME`, `ANDROID_HOME`, `GRADLE_USER_HOME` from `BootstrapFileSystem` |
| `src/main/kotlin/com/vibe/build/runtime/di/BuildRuntimeProcessModule.kt` | Module-internal Hilt `@Binds` for interfaces |

**Modify:**

| File | Responsibility |
|---|---|
| `build-runtime/build.gradle.kts` | Add `externalNativeBuild { cmake }` block + `ndk { abiFilters }` |
| `app/src/main/kotlin/com/vibe/app/di/BuildRuntimeModule.kt` | Provide `ProcessEnvBuilder` (needs `BootstrapFileSystem`) — binding for `ProcessLauncher` comes from `BuildRuntimeProcessModule` |

**Test files:**

| File | Scope |
|---|---|
| `build-runtime/src/test/kotlin/com/vibe/build/runtime/process/ProcessEnvBuilderTest.kt` | Pure JVM unit tests (no JNI) — 5 tests |
| `build-runtime/src/androidTest/kotlin/com/vibe/build/runtime/process/NativeProcessLauncherInstrumentedTest.kt` | Emulator-bound: launch toybox, signal, stdin write — 4 tests |

**Do NOT touch in Phase 1b:**
- Anything under `build-gradle/`, `plugin-host/`, `build-engine/`, `build-tools/`, `shadow-runtime/`
- Any existing `feature/` / agent code
- `agent-system-prompt.md`
- Any Phase 1a file (bootstrap subsystem) — they're orthogonal

## Key Constraints (ALL TASKS)

1. **NDK: use the version that AGP 9.1 bundles by default.** Do NOT pin `ndkVersion` unless a task explicitly fails without it. If AGP reports "NDK not found", STOP and ask.
2. **ABI filter:** `arm64-v8a`, `armeabi-v7a`, `x86_64`. Match the app module's existing filter.
3. **C standard:** C11. No C++. No STL. No Boost. Pure POSIX.
4. **No `dup3`, no `close_range`** — these are API-30+ only. We need to run on minSdk 29.
5. **Fork/exec with pipes** must follow the "classic" pattern (see Task 2 reference): create three pipes in parent, fork, in child dup2 to 0/1/2 then close all pipe fds + close-loop 3..1024, then chdir + execve. On execve failure, `_exit(127)`.
6. **Thread safety:** Native code is invoked from Kotlin coroutines on `Dispatchers.IO`. The C layer must be reentrant — no static/global state. Each `pl_launch` call is independent.
7. **File descriptor ownership:** The C layer returns raw int fds to Kotlin. Kotlin takes ownership via `ParcelFileDescriptor.adoptFd(int)`. Do NOT double-close in C.
8. **Hidden APIs:** Do NOT use `FileDescriptor.setInt$()` reflection. The `ParcelFileDescriptor.adoptFd` path is fully public API since API 13.
9. **Instrumented tests require an active emulator or device** with API 29+. If the implementer has none, Task 7 is blocked but Tasks 1-6 (plus unit tests for Task 4) should still complete.

---

## Task 1: CMake infrastructure

**Files:**
- Modify: `build-runtime/build.gradle.kts`
- Create: `build-runtime/src/main/cpp/CMakeLists.txt`
- Create: `build-runtime/src/main/cpp/placeholder.c`

This task wires NDK + CMake into the module with a trivial source file so `assembleDebug` produces `libbuildruntime.so`. Real code lands in Task 2.

- [ ] **Step 1: Extend `build-runtime/build.gradle.kts`**

Replace the whole `android { ... }` block:

```kotlin
android {
    namespace = "com.vibe.build.runtime"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += ""
                cFlags += listOf("-Wall", "-Wextra", "-O2", "-std=c11")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = false
            isReturnDefaultValues = true
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}
```

Keep the `dependencies { }` block below unchanged.

- [ ] **Step 2: Create `build-runtime/src/main/cpp/CMakeLists.txt`**

```cmake
cmake_minimum_required(VERSION 3.22.1)
project(buildruntime C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

add_library(
    buildruntime SHARED
    placeholder.c
)

# Android's built-in liblog for future logcat tracing (unused in Phase 1b).
find_library(log-lib log)
target_link_libraries(buildruntime ${log-lib})
```

- [ ] **Step 3: Create a trivial `build-runtime/src/main/cpp/placeholder.c`**

```c
/*
 * Phase 1b Task 1 placeholder. Replaced by process_launcher.c and
 * jni_process_launcher.c in Task 2. Keeps CMake happy in the mean-time.
 */

int vibeapp_buildruntime_placeholder(void) {
    return 0;
}
```

- [ ] **Step 4: Verify the module still assembles with native build**

```bash
cd /Users/skykai/Documents/work/VibeApp/.worktrees/v2-arch
./gradlew --no-daemon :build-runtime:assembleDebug
```

Expected: BUILD SUCCESSFUL. If this is the first CMake build on this host, AGP may download the NDK (~1GB) — this can take 5-10 min. Do NOT time out early.

- [ ] **Step 5: Verify `libbuildruntime.so` was produced for all three ABIs**

```bash
ls -la build-runtime/build/intermediates/stripped_native_libs/debug/stripDebugDebugSymbols/out/lib/
```

Expected: directories `arm64-v8a/libbuildruntime.so`, `armeabi-v7a/libbuildruntime.so`, `x86_64/libbuildruntime.so`. If any ABI is missing, STOP and report BLOCKED with the full NDK output.

- [ ] **Step 6: Commit**

```bash
git add build-runtime/build.gradle.kts \
        build-runtime/src/main/cpp/CMakeLists.txt \
        build-runtime/src/main/cpp/placeholder.c
git commit -m "build(build-runtime): add CMake/NDK wiring for native process runtime

Wires externalNativeBuild into :build-runtime with CMake 3.22.1,
C11, -O2, and the three ABIs the app module already targets
(arm64-v8a, armeabi-v7a, x86_64). libbuildruntime.so is produced
from a placeholder.c that gets replaced in Task 2.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Native process_launcher

**Files:**
- Create: `build-runtime/src/main/cpp/process_launcher.h`
- Create: `build-runtime/src/main/cpp/process_launcher.c`
- Create: `build-runtime/src/main/cpp/jni_process_launcher.c`
- Delete: `build-runtime/src/main/cpp/placeholder.c`
- Modify: `build-runtime/src/main/cpp/CMakeLists.txt`

- [ ] **Step 1: Create `build-runtime/src/main/cpp/process_launcher.h`**

```c
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
```

- [ ] **Step 2: Create `build-runtime/src/main/cpp/process_launcher.c`**

```c
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
```

- [ ] **Step 3: Create `build-runtime/src/main/cpp/jni_process_launcher.c`**

```c
/*
 * JNI bridge for process_launcher.c.
 *
 * Marshals Kotlin String + String[] to C-style char** and back. Returns
 * results as an int[4] = { pid, stdoutFd, stderrFd, stdinFd }. On
 * failure, pid == -1 and the other three entries are -1.
 *
 * All allocated C strings are freed before returning. The caller
 * (Kotlin) is responsible for closing the fds via ParcelFileDescriptor.
 */

#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include "process_launcher.h"

/*
 * Convert a Java String[] to a malloc'd NULL-terminated char**.
 * Returns NULL on allocation failure. Caller must free via free_c_strings().
 */
static char** jstring_array_to_c_strings(JNIEnv* env, jobjectArray array) {
    jsize len = (*env)->GetArrayLength(env, array);
    char** result = (char**)calloc((size_t)len + 1, sizeof(char*));
    if (result == NULL) {
        return NULL;
    }

    for (jsize i = 0; i < len; i++) {
        jstring js = (jstring)(*env)->GetObjectArrayElement(env, array, i);
        const char* chars = (*env)->GetStringUTFChars(env, js, NULL);
        if (chars != NULL) {
            result[i] = strdup(chars);
            (*env)->ReleaseStringUTFChars(env, js, chars);
        }
        (*env)->DeleteLocalRef(env, js);
    }
    result[len] = NULL;
    return result;
}

static void free_c_strings(char** arr) {
    if (arr == NULL) return;
    for (char** p = arr; *p != NULL; p++) {
        free(*p);
    }
    free(arr);
}

JNIEXPORT jintArray JNICALL
Java_com_vibe_build_runtime_process_NativeProcessBridge_nativeLaunch(
    JNIEnv* env,
    jclass clazz,
    jstring jExecutable,
    jobjectArray jArgv,
    jobjectArray jEnvp,
    jstring jCwd
) {
    (void)clazz;

    const char* executable = (*env)->GetStringUTFChars(env, jExecutable, NULL);
    char** argv = jstring_array_to_c_strings(env, jArgv);
    char** envp = jstring_array_to_c_strings(env, jEnvp);
    const char* cwd = (jCwd != NULL) ? (*env)->GetStringUTFChars(env, jCwd, NULL) : NULL;

    int stdin_fd = -1, stdout_fd = -1, stderr_fd = -1;
    pid_t pid = pl_launch(
        executable,
        (const char* const*)argv,
        (const char* const*)envp,
        cwd,
        &stdout_fd,
        &stderr_fd,
        &stdin_fd
    );

    /* Release JNI strings + temp C arrays. */
    (*env)->ReleaseStringUTFChars(env, jExecutable, executable);
    if (cwd != NULL) {
        (*env)->ReleaseStringUTFChars(env, jCwd, cwd);
    }
    free_c_strings(argv);
    free_c_strings(envp);

    jintArray result = (*env)->NewIntArray(env, 4);
    if (result == NULL) {
        /* Clean up fds if allocation failed */
        if (pid > 0) {
            pl_signal(pid, 9 /* SIGKILL */);
            pl_wait(pid);
        }
        return NULL;
    }
    jint values[4] = { (jint)pid, (jint)stdout_fd, (jint)stderr_fd, (jint)stdin_fd };
    (*env)->SetIntArrayRegion(env, result, 0, 4, values);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_vibe_build_runtime_process_NativeProcessBridge_nativeSignal(
    JNIEnv* env,
    jclass clazz,
    jint pid,
    jint signum
) {
    (void)env;
    (void)clazz;
    return (jint)pl_signal((pid_t)pid, (int)signum);
}

JNIEXPORT jint JNICALL
Java_com_vibe_build_runtime_process_NativeProcessBridge_nativeWaitFor(
    JNIEnv* env,
    jclass clazz,
    jint pid
) {
    (void)env;
    (void)clazz;
    return (jint)pl_wait((pid_t)pid);
}
```

- [ ] **Step 4: Delete `placeholder.c`**

```bash
rm build-runtime/src/main/cpp/placeholder.c
```

- [ ] **Step 5: Update `build-runtime/src/main/cpp/CMakeLists.txt`**

Replace the `add_library` call:

```cmake
cmake_minimum_required(VERSION 3.22.1)
project(buildruntime C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

add_library(
    buildruntime SHARED
    process_launcher.c
    jni_process_launcher.c
)

find_library(log-lib log)
target_link_libraries(buildruntime ${log-lib})
```

- [ ] **Step 6: Verify clean native build**

```bash
./gradlew --no-daemon :build-runtime:clean :build-runtime:assembleDebug
```

Expected: BUILD SUCCESSFUL. `libbuildruntime.so` produced for all three ABIs with `process_launcher.c` + `jni_process_launcher.c` linked in.

If CMake reports an unresolved symbol (e.g. `pl_launch not declared`), check `process_launcher.h` is at the right path and CMakeLists.txt has all three files.

- [ ] **Step 7: Commit**

```bash
git add build-runtime/src/main/cpp/CMakeLists.txt \
        build-runtime/src/main/cpp/process_launcher.h \
        build-runtime/src/main/cpp/process_launcher.c \
        build-runtime/src/main/cpp/jni_process_launcher.c
git add -u build-runtime/src/main/cpp/placeholder.c
git commit -m "feat(build-runtime): native process launcher (fork/execve/pipes)

C layer: process_launcher.c does POSIX fork() + 3-pipe plumbing +
execve(), returning a pid and three pipe fds. pl_wait() blocks and
returns exit status (or 128+sig). pl_signal() wraps kill().

JNI layer: jni_process_launcher.c marshals Kotlin String/String[]/
nullable String to C and returns an int[4] of {pid,out,err,in}.
Fd lifetime transfers to Kotlin via ParcelFileDescriptor in a later
task.

Build wiring: libbuildruntime.so now compiles process_launcher.c +
jni_process_launcher.c for arm64-v8a, armeabi-v7a, x86_64.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: Kotlin bridge — `NativeProcessBridge`, `ProcessEvent`, `NativeProcess`

**Files:**
- Create: `build-runtime/src/main/kotlin/com/vibe/build/runtime/process/NativeProcessBridge.kt`
- Create: `build-runtime/src/main/kotlin/com/vibe/build/runtime/process/ProcessEvent.kt`
- Create: `build-runtime/src/main/kotlin/com/vibe/build/runtime/process/NativeProcess.kt`

These are compile-only — no runtime logic yet and no tests. The JNI bridge can't be unit-tested on the JVM anyway (no `libbuildruntime.so` for the host OS).

- [ ] **Step 1: Create `NativeProcessBridge.kt`**

```kotlin
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
```

- [ ] **Step 2: Create `ProcessEvent.kt`**

```kotlin
package com.vibe.build.runtime.process

/**
 * Stream event from a running native process.
 *
 * `Stdout`/`Stderr` payloads are byte-safe (no charset assumption).
 * The `equals`/`hashCode` implementations compare content, not array
 * identity, so tests can assert on specific bytes.
 */
sealed interface ProcessEvent {

    data class Stdout(val bytes: ByteArray) : ProcessEvent {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Stdout && bytes.contentEquals(other.bytes))
        override fun hashCode(): Int = bytes.contentHashCode()
    }

    data class Stderr(val bytes: ByteArray) : ProcessEvent {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Stderr && bytes.contentEquals(other.bytes))
        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /**
     * Terminal event. No further events follow.
     *   - 0-255: normal exit
     *   - 128+signal: killed by a signal
     *   - -1: waitpid failure
     */
    data class Exited(val code: Int) : ProcessEvent
}
```

- [ ] **Step 3: Create `NativeProcess.kt`**

```kotlin
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
```

- [ ] **Step 4: Verify the module compiles**

```bash
./gradlew --no-daemon :build-runtime:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL. (Compile-only step; no JNI loading occurs on a JVM unit-test path.)

- [ ] **Step 5: Commit**

```bash
git add build-runtime/src/main/kotlin/com/vibe/build/runtime/process/NativeProcessBridge.kt \
        build-runtime/src/main/kotlin/com/vibe/build/runtime/process/ProcessEvent.kt \
        build-runtime/src/main/kotlin/com/vibe/build/runtime/process/NativeProcess.kt
git commit -m "feat(build-runtime): Kotlin API for native processes

- NativeProcessBridge: internal JNI bridge (three external functions,
  loads libbuildruntime.so at class init).
- ProcessEvent: sealed interface (Stdout/Stderr/Exited) with
  content-based equals for test assertions.
- NativeProcess: public interface that NativeProcessImpl (Task 5)
  will realize. SIGTERM/SIGKILL/SIGHUP/SIGINT constants exported.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: `ProcessEnvBuilder` + unit tests

**Files:**
- Create: `build-runtime/src/main/kotlin/com/vibe/build/runtime/process/ProcessEnvBuilder.kt`
- Create: `build-runtime/src/test/kotlin/com/vibe/build/runtime/process/ProcessEnvBuilderTest.kt`

`ProcessEnvBuilder` is pure Kotlin — takes a `BootstrapFileSystem` and produces a `Map<String, String>` of environment variables for every process launch. It has no JNI dependency, so it's fully JVM-testable.

- [ ] **Step 1: Write the failing test `ProcessEnvBuilderTest.kt`**

```kotlin
package com.vibe.build.runtime.process

import com.vibe.build.runtime.bootstrap.BootstrapFileSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProcessEnvBuilderTest {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    private fun newFs(): BootstrapFileSystem = BootstrapFileSystem(filesDir = temp.root)

    @Test
    fun `build produces PATH rooted at usr bin plus Android system paths`() {
        val fs = newFs()
        val env = ProcessEnvBuilder(fs).build(cwd = temp.root, extra = emptyMap())

        val path = env["PATH"]!!
        assertTrue("PATH should start with usr/bin: $path",
            path.startsWith(File(fs.usrRoot, "bin").absolutePath))
        assertTrue("PATH should include /system/bin: $path", path.contains("/system/bin"))
    }

    @Test
    fun `build sets HOME to cwd`() {
        val fs = newFs()
        val home = File(temp.root, "projects/p1")
        home.mkdirs()
        val env = ProcessEnvBuilder(fs).build(cwd = home, extra = emptyMap())

        assertEquals(home.absolutePath, env["HOME"])
    }

    @Test
    fun `build sets TMPDIR under usr tmp`() {
        val fs = newFs()
        val env = ProcessEnvBuilder(fs).build(cwd = temp.root, extra = emptyMap())

        assertEquals(File(fs.usrRoot, "tmp").absolutePath, env["TMPDIR"])
    }

    @Test
    fun `build sets JAVA_HOME and ANDROID_HOME under usr opt`() {
        val fs = newFs()
        val env = ProcessEnvBuilder(fs).build(cwd = temp.root, extra = emptyMap())

        assertEquals(File(fs.optRoot, "jdk-17.0.13").absolutePath, env["JAVA_HOME"])
        assertEquals(File(fs.optRoot, "android-sdk").absolutePath, env["ANDROID_HOME"])
    }

    @Test
    fun `extra env entries override base entries`() {
        val fs = newFs()
        val env = ProcessEnvBuilder(fs).build(
            cwd = temp.root,
            extra = mapOf(
                "PATH" to "/custom/path",
                "MY_VAR" to "hello",
            ),
        )

        assertEquals("/custom/path", env["PATH"])
        assertEquals("hello", env["MY_VAR"])
        // Unrelated base entries preserved
        assertEquals(File(fs.usrRoot, "tmp").absolutePath, env["TMPDIR"])
    }

    @Test
    fun `build sets LD_LIBRARY_PATH under usr lib`() {
        val fs = newFs()
        val env = ProcessEnvBuilder(fs).build(cwd = temp.root, extra = emptyMap())
        assertEquals(File(fs.usrRoot, "lib").absolutePath, env["LD_LIBRARY_PATH"])
    }

    @Test
    fun `build sets GRADLE_USER_HOME under filesDir gradle`() {
        val fs = newFs()
        val env = ProcessEnvBuilder(fs).build(cwd = temp.root, extra = emptyMap())
        // filesDir/.gradle — one level up from usr/
        val expected = File(temp.root, ".gradle").absolutePath
        assertEquals(expected, env["GRADLE_USER_HOME"])
    }
}
```

- [ ] **Step 2: Run → fail**

```bash
./gradlew --no-daemon :build-runtime:testDebugUnitTest --tests ProcessEnvBuilderTest
```

Expected: FAIL (`ProcessEnvBuilder` class not found).

- [ ] **Step 3: Implement `ProcessEnvBuilder.kt`**

```kotlin
package com.vibe.build.runtime.process

import com.vibe.build.runtime.bootstrap.BootstrapFileSystem
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Composes the environment variable map passed to every native process.
 *
 * Variables set:
 *   PATH             usr/bin:/system/bin:/system/xbin
 *   LD_LIBRARY_PATH  usr/lib
 *   JAVA_HOME        usr/opt/jdk-17.0.13      (Phase 1c: populated by bootstrap)
 *   ANDROID_HOME     usr/opt/android-sdk      (Phase 1c: populated by bootstrap)
 *   GRADLE_USER_HOME filesDir/.gradle         (shared across projects)
 *   HOME             cwd
 *   TMPDIR           usr/tmp
 *
 * [extra] overrides any of the above.
 *
 * NOTE: `LD_PRELOAD` for libtermux-exec.so will be added in Phase 1c when
 * downloaded binaries with shebangs need correction.
 */
@Singleton
class ProcessEnvBuilder @Inject constructor(
    private val fs: BootstrapFileSystem,
) {

    fun build(cwd: File, extra: Map<String, String> = emptyMap()): Map<String, String> {
        val filesDir = fs.usrRoot.parentFile
            ?: error("BootstrapFileSystem.usrRoot has no parent; expected filesDir/usr")

        val base = mapOf(
            "PATH" to buildPath(),
            "LD_LIBRARY_PATH" to File(fs.usrRoot, "lib").absolutePath,
            "JAVA_HOME" to File(fs.optRoot, JDK_DIR_NAME).absolutePath,
            "ANDROID_HOME" to File(fs.optRoot, ANDROID_SDK_DIR_NAME).absolutePath,
            "GRADLE_USER_HOME" to File(filesDir, GRADLE_USER_HOME_DIR_NAME).absolutePath,
            "HOME" to cwd.absolutePath,
            "TMPDIR" to File(fs.usrRoot, "tmp").absolutePath,
        )

        return base + extra
    }

    private fun buildPath(): String = listOf(
        File(fs.usrRoot, "bin").absolutePath,
        "/system/bin",
        "/system/xbin",
    ).joinToString(separator = ":")

    companion object {
        /** Component install directory names. Must match bootstrap manifest IDs in Phase 1c. */
        const val JDK_DIR_NAME = "jdk-17.0.13"
        const val ANDROID_SDK_DIR_NAME = "android-sdk"
        const val GRADLE_USER_HOME_DIR_NAME = ".gradle"
    }
}
```

- [ ] **Step 4: Run → pass**

```bash
./gradlew --no-daemon :build-runtime:testDebugUnitTest --tests ProcessEnvBuilderTest
```

Expected: 7 tests run, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add build-runtime/src/main/kotlin/com/vibe/build/runtime/process/ProcessEnvBuilder.kt \
        build-runtime/src/test/kotlin/com/vibe/build/runtime/process/ProcessEnvBuilderTest.kt
git commit -m "feat(build-runtime): ProcessEnvBuilder composes base env vars

Produces PATH, LD_LIBRARY_PATH, JAVA_HOME, ANDROID_HOME, GRADLE_USER_HOME,
HOME, TMPDIR from a BootstrapFileSystem and caller-supplied overrides.
JDK / Android SDK directory names held in constants — must match the
Phase 1c bootstrap manifest component IDs. LD_PRELOAD for
libtermux-exec.so is intentionally NOT set yet (Phase 1c).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: `NativeProcessImpl` + `ProcessLauncher` + `NativeProcessLauncher`

**Files:**
- Create: `build-runtime/src/main/kotlin/com/vibe/build/runtime/process/NativeProcessImpl.kt`
- Create: `build-runtime/src/main/kotlin/com/vibe/build/runtime/process/ProcessLauncher.kt`
- Create: `build-runtime/src/main/kotlin/com/vibe/build/runtime/process/NativeProcessLauncher.kt`

No unit tests here — these depend on JNI + ParcelFileDescriptor (which is Android-only). Coverage comes from Task 7's instrumented tests.

- [ ] **Step 1: Create `NativeProcessImpl.kt`**

```kotlin
package com.vibe.build.runtime.process

import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Private implementation of [NativeProcess]. Constructed by
 * [NativeProcessLauncher.launch]. Takes ownership of the three fds
 * returned by [NativeProcessBridge.nativeLaunch] and wraps them as
 * [ParcelFileDescriptor] instances.
 */
internal class NativeProcessImpl(
    override val pid: Int,
    stdoutFd: Int,
    stderrFd: Int,
    stdinFd: Int,
) : NativeProcess {

    private val stdoutPfd: ParcelFileDescriptor = ParcelFileDescriptor.adoptFd(stdoutFd)
    private val stderrPfd: ParcelFileDescriptor = ParcelFileDescriptor.adoptFd(stderrFd)
    private val stdinPfd: ParcelFileDescriptor = ParcelFileDescriptor.adoptFd(stdinFd)

    private val stdoutStream: FileInputStream = FileInputStream(stdoutPfd.fileDescriptor)
    private val stderrStream: FileInputStream = FileInputStream(stderrPfd.fileDescriptor)
    private val stdinStream: FileOutputStream = FileOutputStream(stdinPfd.fileDescriptor)

    override val events: Flow<ProcessEvent> = channelFlow {
        val outJob = launch(Dispatchers.IO) {
            try {
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val n = stdoutStream.read(buffer)
                    if (n <= 0) break
                    send(ProcessEvent.Stdout(buffer.copyOfRange(0, n)))
                }
            } catch (_: Throwable) {
                // pipe closed or process terminated
            }
        }
        val errJob = launch(Dispatchers.IO) {
            try {
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val n = stderrStream.read(buffer)
                    if (n <= 0) break
                    send(ProcessEvent.Stderr(buffer.copyOfRange(0, n)))
                }
            } catch (_: Throwable) {
                // pipe closed or process terminated
            }
        }

        outJob.join()
        errJob.join()

        val exitCode = withContext(Dispatchers.IO) {
            NativeProcessBridge.nativeWaitFor(pid)
        }
        send(ProcessEvent.Exited(exitCode))

        // Close streams. PFDs close their wrapped fds.
        try { stdoutPfd.close() } catch (_: Throwable) {}
        try { stderrPfd.close() } catch (_: Throwable) {}
        try { stdinPfd.close() } catch (_: Throwable) {}
    }.flowOn(Dispatchers.IO)

    override suspend fun awaitExit(): Int = withContext(Dispatchers.IO) {
        NativeProcessBridge.nativeWaitFor(pid)
    }

    override fun signal(signum: Int): Int =
        NativeProcessBridge.nativeSignal(pid, signum)

    override fun writeStdin(bytes: ByteArray) {
        stdinStream.write(bytes)
        stdinStream.flush()
    }

    override fun closeStdin() {
        try { stdinStream.close() } catch (_: Throwable) {}
        try { stdinPfd.close() } catch (_: Throwable) {}
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
    }
}
```

- [ ] **Step 2: Create `ProcessLauncher.kt`**

```kotlin
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
```

- [ ] **Step 3: Create `NativeProcessLauncher.kt`**

```kotlin
package com.vibe.build.runtime.process

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NativeProcessLauncher @Inject constructor(
    private val envBuilder: ProcessEnvBuilder,
) : ProcessLauncher {

    override suspend fun launch(
        executable: String,
        args: List<String>,
        cwd: File,
        env: Map<String, String>,
    ): NativeProcess = withContext(Dispatchers.IO) {
        require(cwd.isDirectory) { "cwd must be an existing directory: $cwd" }

        val argv = (listOf(executable) + args).toTypedArray()
        val envMap = envBuilder.build(cwd = cwd, extra = env)
        val envp = envMap.entries.map { (k, v) -> "$k=$v" }.toTypedArray()

        val result = NativeProcessBridge.nativeLaunch(
            executable = executable,
            argv = argv,
            envp = envp,
            cwd = cwd.absolutePath,
        )

        val pid = result[0]
        if (pid <= 0) {
            throw ProcessLaunchException(
                "nativeLaunch failed for executable=$executable argv=${argv.joinToString(" ")}",
            )
        }

        NativeProcessImpl(
            pid = pid,
            stdoutFd = result[1],
            stderrFd = result[2],
            stdinFd = result[3],
        )
    }
}
```

- [ ] **Step 4: Verify the module still compiles**

```bash
./gradlew --no-daemon :build-runtime:assembleDebug :build-runtime:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL, 52 total unit tests pass (45 from Phase 1a + 7 from Task 4).

- [ ] **Step 5: Commit**

```bash
git add build-runtime/src/main/kotlin/com/vibe/build/runtime/process/NativeProcessImpl.kt \
        build-runtime/src/main/kotlin/com/vibe/build/runtime/process/ProcessLauncher.kt \
        build-runtime/src/main/kotlin/com/vibe/build/runtime/process/NativeProcessLauncher.kt
git commit -m "feat(build-runtime): NativeProcessImpl + NativeProcessLauncher

NativeProcessImpl wraps raw fds via ParcelFileDescriptor.adoptFd and
exposes a merged stdout/stderr/exit flow via channelFlow on
Dispatchers.IO. Two background reader coroutines drain each pipe;
the flow closes once both readers hit EOF and waitpid returns.

NativeProcessLauncher is the injectable facade — composes env via
ProcessEnvBuilder, prepends executable to argv, and bridges to the
native layer via NativeProcessBridge.

Tests defer to Task 7 instrumented tests (can't exec a binary on the
JVM host).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: Hilt wiring

**Files:**
- Create: `build-runtime/src/main/kotlin/com/vibe/build/runtime/di/BuildRuntimeProcessModule.kt`
- Modify: `app/src/main/kotlin/com/vibe/app/di/BuildRuntimeModule.kt`

- [ ] **Step 1: Create `BuildRuntimeProcessModule.kt`**

```kotlin
package com.vibe.build.runtime.di

import com.vibe.build.runtime.process.NativeProcessLauncher
import com.vibe.build.runtime.process.ProcessLauncher
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Module-internal Hilt bindings for the process runtime subsystem.
 * ProcessEnvBuilder is provided by the app-layer BuildRuntimeModule
 * because it needs BootstrapFileSystem which is app-provided.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class BuildRuntimeProcessModule {

    @Binds
    @Singleton
    abstract fun bindProcessLauncher(
        impl: NativeProcessLauncher,
    ): ProcessLauncher
}
```

- [ ] **Step 2: Read current `BuildRuntimeModule.kt`**

The file currently provides bootstrap subsystem collaborators from Phase 1a. We extend it to provide `ProcessEnvBuilder` (since it needs `BootstrapFileSystem`, which we already provide here).

- [ ] **Step 3: Replace `BuildRuntimeModule.kt` contents**

```kotlin
package com.vibe.app.di

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.vibe.build.runtime.BuildRuntime
import com.vibe.build.runtime.bootstrap.Abi
import com.vibe.build.runtime.bootstrap.BootstrapDownloader
import com.vibe.build.runtime.bootstrap.BootstrapFileSystem
import com.vibe.build.runtime.bootstrap.BootstrapStateStore
import com.vibe.build.runtime.bootstrap.ManifestParser
import com.vibe.build.runtime.bootstrap.ManifestSignature
import com.vibe.build.runtime.bootstrap.MirrorSelector
import com.vibe.build.runtime.bootstrap.RuntimeBootstrapper
import com.vibe.build.runtime.bootstrap.ZstdExtractor
import com.vibe.build.runtime.process.ProcessEnvBuilder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the `:build-runtime` services to the application graph.
 *
 * Phase 0: BuildRuntime placeholder.
 * Phase 1a: bootstrap subsystem (downloader, extractor, state machine).
 * Phase 1b: ProcessEnvBuilder (binding for ProcessLauncher comes from
 *   BuildRuntimeProcessModule in the :build-runtime module).
 */
@Module
@InstallIn(SingletonComponent::class)
object BuildRuntimeModule {

    @Provides
    @Singleton
    fun provideBuildRuntime(): BuildRuntime = BuildRuntime()

    @Provides
    @Singleton
    fun provideBootstrapFileSystem(
        @ApplicationContext context: Context,
    ): BootstrapFileSystem = BootstrapFileSystem(filesDir = context.filesDir)

    @Provides
    @Singleton
    fun provideAbi(): Abi =
        Abi.pickPreferred(Build.SUPPORTED_ABIS)
            ?: error(
                "Unsupported ABI set: ${Build.SUPPORTED_ABIS.joinToString()}. " +
                "VibeApp supports arm64-v8a, armeabi-v7a, x86_64 only.",
            )

    @Provides
    @Singleton
    fun provideMirrorSelector(): MirrorSelector = MirrorSelector(
        primaryBase = "https://github.com/Skykai521/VibeApp/releases/download/v2.0.0",
        fallbackBase = "https://vibeapp-cdn.oss-cn-hangzhou.aliyuncs.com/releases/v2.0.0",
    )

    @Provides
    @Singleton
    fun provideManifestSignature(): ManifestSignature =
        ManifestSignature(publicKeyHex = "00".repeat(32))

    @Provides
    @Singleton
    fun provideRuntimeBootstrapper(
        fs: BootstrapFileSystem,
        store: BootstrapStateStore,
        parser: ManifestParser,
        signature: ManifestSignature,
        mirrors: MirrorSelector,
        downloader: BootstrapDownloader,
        extractor: ZstdExtractor,
        abi: Abi,
    ): RuntimeBootstrapper = RuntimeBootstrapper(
        fs = fs,
        store = store,
        parser = parser,
        signature = signature,
        mirrors = mirrors,
        downloader = downloader,
        extractor = extractor,
        abi = abi,
    )

    @Provides
    @Singleton
    fun provideProcessEnvBuilder(
        fs: BootstrapFileSystem,
    ): ProcessEnvBuilder = ProcessEnvBuilder(fs)
}
```

- [ ] **Step 4: Verify the Hilt graph compiles**

```bash
./gradlew --no-daemon :app:kspDebugKotlin
```

Expected: BUILD SUCCESSFUL. If a Dagger "cannot be provided" error mentions `ProcessLauncher`, check that `:app` depends on `:build-runtime` (it should — Phase 0 Task 7 wired this).

- [ ] **Step 5: Full assemble**

```bash
./gradlew --no-daemon :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add build-runtime/src/main/kotlin/com/vibe/build/runtime/di/BuildRuntimeProcessModule.kt \
        app/src/main/kotlin/com/vibe/app/di/BuildRuntimeModule.kt
git commit -m "feat(v2): wire process runtime into Hilt graph

BuildRuntimeProcessModule (in :build-runtime) binds ProcessLauncher
to NativeProcessLauncher. BuildRuntimeModule (in :app) adds a
ProcessEnvBuilder provider that pulls BootstrapFileSystem from the
already-wired Phase 1a bindings.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: Instrumented test — launch toybox

**Files:**
- Modify: `build-runtime/build.gradle.kts` (if missing `androidTestImplementation(libs.kotlinx.coroutines.test)`; Phase 1a Task 12 added it already — verify and skip if present)
- Create: `build-runtime/src/androidTest/kotlin/com/vibe/build/runtime/process/NativeProcessLauncherInstrumentedTest.kt`

This is the Phase 1b acceptance test. Requires an active emulator or connected device at API 29+.

- [ ] **Step 1: Verify androidTestImplementation has coroutines-test**

```bash
grep -E "androidTestImplementation.*coroutines-test|androidTestImplementation.*mockwebserver" build-runtime/build.gradle.kts
```

If `kotlinx-coroutines-test` is not under `androidTestImplementation`, add it. Phase 1a Task 12 should have added it; if absent, insert:

```kotlin
    androidTestImplementation(libs.kotlinx.coroutines.test)
```

Into the `dependencies { }` block alongside the existing `androidTestImplementation(libs.androidx.junit)`.

- [ ] **Step 2: Create `NativeProcessLauncherInstrumentedTest.kt`**

```kotlin
package com.vibe.build.runtime.process

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vibe.build.runtime.bootstrap.BootstrapFileSystem
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NativeProcessLauncherInstrumentedTest {

    private lateinit var scratchDir: File
    private lateinit var launcher: NativeProcessLauncher

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        scratchDir = File(ctx.cacheDir, "nativeprocess-test-${System.nanoTime()}")
        require(scratchDir.mkdirs())

        val fs = BootstrapFileSystem(filesDir = scratchDir)
        fs.ensureDirectories()

        launcher = NativeProcessLauncher(ProcessEnvBuilder(fs))
    }

    @After
    fun tearDown() {
        scratchDir.deleteRecursively()
    }

    private val toyboxPath: String = "/system/bin/toybox"

    @Test
    fun `launch toybox echo produces stdout and exit 0`() = runTest {
        val process = launcher.launch(
            executable = toyboxPath,
            args = listOf("echo", "hello", "world"),
            cwd = scratchDir,
        )

        val events = withTimeout(10_000) { process.events.toList() }

        val stdoutBytes = events.filterIsInstance<ProcessEvent.Stdout>()
            .fold(ByteArray(0)) { acc, e -> acc + e.bytes }
        val exit = events.filterIsInstance<ProcessEvent.Exited>().first()

        assertEquals(0, exit.code)
        assertEquals("hello world\n", String(stdoutBytes, Charsets.UTF_8))
    }

    @Test
    fun `launch toybox ls slash produces nontrivial stdout and exit 0`() = runTest {
        val process = launcher.launch(
            executable = toyboxPath,
            args = listOf("ls", "/"),
            cwd = scratchDir,
        )

        val events = withTimeout(10_000) { process.events.toList() }

        val stdout = events.filterIsInstance<ProcessEvent.Stdout>()
            .fold(ByteArray(0)) { acc, e -> acc + e.bytes }
        val exit = events.filterIsInstance<ProcessEvent.Exited>().first()

        assertEquals(0, exit.code)
        assertTrue(
            "ls / should at least list 'system': ${String(stdout, Charsets.UTF_8)}",
            String(stdout, Charsets.UTF_8).contains("system"),
        )
    }

    @Test
    fun `launch with nonexistent executable exits with code 127`() = runTest {
        // The launcher returns pid > 0 (fork succeeds) but the child's
        // execve fails inside the child and _exits(127).
        val process = launcher.launch(
            executable = "/system/bin/toybox-does-not-exist-${System.nanoTime()}",
            args = listOf("ls"),
            cwd = scratchDir,
        )

        val events = withTimeout(5_000) { process.events.toList() }
        val exit = events.filterIsInstance<ProcessEvent.Exited>().first()
        assertEquals(127, exit.code)
    }

    @Test
    fun `signal SIGTERM to a long-running process produces signaled exit`() = runTest {
        // Launch toybox sleep 30; signal SIGTERM; expect exit 128+15 = 143.
        val process = launcher.launch(
            executable = toyboxPath,
            args = listOf("sleep", "30"),
            cwd = scratchDir,
        )

        // Give the child a moment to actually start waiting.
        withContext(kotlinx.coroutines.Dispatchers.IO) { Thread.sleep(300) }

        val rc = process.signal(SIGTERM)
        assertEquals("signal() returned errno=$rc", 0, rc)

        val exit = withTimeout(5_000) {
            process.events.filterIsInstance<ProcessEvent.Exited>().first()
        }
        // Processes terminated by SIGTERM return 128+15 = 143.
        assertEquals(143, exit.code)
    }
}
```

- [ ] **Step 3: Start an emulator or connect a device, then run the test**

```bash
./gradlew --no-daemon :build-runtime:connectedDebugAndroidTest
```

Expected: 4 tests, 0 failures. If any test fails, read the exit code + stdout/stderr carefully — most likely causes:

- `pid == -1`: fork itself failed (check errno in logcat).
- Exit code 126: dup2/chdir failed in child.
- Exit code 127: execve failed (wrong path? binary not executable?).
- Timeout: pipe read is stuck; verify `channelFlow` is completing both reader jobs before emitting `Exited`.

- [ ] **Step 4: Commit**

```bash
git add build-runtime/src/androidTest/kotlin/com/vibe/build/runtime/process/NativeProcessLauncherInstrumentedTest.kt
# only if build.gradle.kts was actually modified in Step 1:
# git add -u build-runtime/build.gradle.kts
git commit -m "test(build-runtime): instrumented tests for native process launch

Four tests against /system/bin/toybox on an API 29+ emulator:
 - echo hello world → stdout + exit 0
 - ls / → stdout contains 'system' + exit 0
 - launch nonexistent binary → exit 127 (execve failure inside child)
 - signal SIGTERM to sleep 30 → exit 143 (128 + SIGTERM=15)

Acceptance criterion for Phase 1b: NativeProcessLauncher can spawn a
process, stream its stdout/stderr as Flow<ProcessEvent>, handle
signals, and report accurate exit codes.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

- [ ] **Step 5: Final regression (optional sanity check)**

```bash
./gradlew --no-daemon :build-runtime:testDebugUnitTest \
    :build-runtime:lintDebug \
    :app:kspDebugKotlin \
    :app:assembleDebug
```

Expected: BUILD SUCCESSFUL across all four. 52 unit tests pass (45 Phase 1a + 7 Phase 1b Task 4).

---

## Phase 1b Exit Criteria

All of the following must hold:

- [ ] `./gradlew :build-runtime:testDebugUnitTest` passes with ~52 tests, 0 failures.
- [ ] `./gradlew :build-runtime:assembleDebug` succeeds and produces `libbuildruntime.so` in all three ABIs.
- [ ] `./gradlew :build-runtime:lintDebug` is clean.
- [ ] `./gradlew :app:kspDebugKotlin` compiles (Hilt graph valid with ProcessLauncher binding).
- [ ] `./gradlew :app:assembleDebug` produces a debug APK.
- [ ] `./gradlew :build-runtime:connectedDebugAndroidTest` passes 4 native-process tests on an API 29+ emulator, OR all 4 compile successfully even if the emulator is unavailable for running.
- [ ] No modifications to any `build-engine`, `shadow-runtime`, `build-tools`, `build-gradle`, `plugin-host` source.
- [ ] All existing v1 flows (Java+XML generation, `build-engine`) build unchanged.
- [ ] `libtermux-exec.so` and LD_PRELOAD handling remain **not** added — deferred to Phase 1c.

When these boxes are checked, Phase 1b is complete. Phase 1c (integration: real bootstrap manifest publishing, debug UI, real `java -version` end-to-end) begins next.

---

## Self-Review Notes

**Spec coverage against design doc §3.6 and §3.7:**

- §3.6 `NativeProcess` API: pid, events Flow, awaitExit, signal, writeStdin → Task 3 (interface) + Task 5 (impl).
- §3.6 `ProcessLauncher.launch(executable, args, cwd, env, stdin)`: implemented in Task 5. Minor deviation: the `stdin: Flow<ByteArray>?` parameter from the design doc is NOT in Phase 1b's `ProcessLauncher.launch` signature — we expose `NativeProcess.writeStdin(bytes)` + `closeStdin()` instead, which is simpler and sufficient for Phase 1c's Gradle/java child processes (they don't need streaming stdin). Flow-based stdin can be re-added in a later phase if agent tooling requires it.
- §3.6 `ProcessEnvBuilder`: implemented in Task 4. LD_PRELOAD intentionally deferred.
- §3.7 exec wrapper (libtermux-exec.so): intentionally deferred to Phase 1c.
- §3.8 Gradle daemon lifecycle: deferred to Phase 2+.

**Placeholders / gaps:**

- `NativeProcess.writeStdin()` is synchronous + blocking — fine for Phase 1b. If Phase 1c + Phase 2 want streaming stdin for Gradle Tooling API IPC, we'll add a Flow-based overload. Called out explicitly in Task 5's `writeStdin` Kotlin doc.
- `ProcessLauncher.launch()` resolves `executable` as an absolute path; it does NOT search PATH. Phase 1c's shell scripts (invoked via libtermux-exec) will handle shebang resolution, but callers of `ProcessLauncher` must always pass an absolute path for the executable. Documented in `ProcessLauncher` kdoc.
- Instrumented test Task 7 requires `/system/bin/toybox`. On very old Android builds this may be symlinked differently; the plan targets API 29+ where `/system/bin/toybox` exists.

**Type consistency:**

- `NativeProcess.pid: Int` consistent from `NativeProcessBridge.nativeLaunch` return int[], `NativeProcessImpl` constructor, `NativeProcessLauncher.launch` result unpacking, and tests.
- `ProcessEvent` sealed interface subtypes (Stdout/Stderr/Exited) identical across Tasks 3, 5, 7.
- `SIGTERM = 15`, `SIGKILL = 9` as `Int` constants in `NativeProcess.kt` referenced consistently in Task 7 test.
- `ProcessEnvBuilder.build(cwd, extra)` signature identical across Tasks 4 test, Task 5 impl use, and Task 6 provider injection.
- `NativeProcessBridge.nativeLaunch` return type `IntArray` of 4 elements (pid, stdoutFd, stderrFd, stdinFd) consistent between JNI C signature (`jintArray` of 4), Kotlin declaration, and `NativeProcessLauncher` unpacking.

No "TBD", "TODO", "similar to Task N", or "add error handling" placeholders remain. Where a future-phase item is referenced, it is clearly marked (Phase 1c).
