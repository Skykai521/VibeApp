# v2.0 Phase 1d: libtermux-exec.so (Shebang Correction) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a small native library `libtermux-exec.so` that, when loaded via `LD_PRELOAD` into a child process, intercepts `execve()` and rewrites `#!/usr/bin/env foo` shebang lines to use absolute paths under `$PREFIX/bin/`. This is the last piece that allows VibeApp to run downloaded shell scripts — most critically the `gradle` / `gradlew` shell wrappers that Phase 2 will need.

**Architecture:** A separate shared library in `:build-runtime`'s CMake graph (coexisting with the existing `libbuildruntime.so`). Source is a minimal shebang-rewriting interceptor — written from scratch (~150 LoC of C) rather than a bulk import from termux-exec, because VibeApp's PREFIX layout is fixed and known at compile time; we don't need termux-exec's full runtime path resolution machinery. `ProcessEnvBuilder` gains a `LD_PRELOAD` entry pointing at the extracted `libtermux-exec.so`. `RuntimeBootstrapper` ensures `libtermux-exec.so` is extracted from the APK's `jniLibs` to a known on-device path at startup so the child processes can find it. An instrumented test proves a script with `#!/usr/bin/env sh` runs successfully.

**Tech Stack:** Existing NDK/CMake in `:build-runtime`. New C source under `build-runtime/src/main/cpp/termux_exec/`. No new Gradle deps. Instrumented test lands under `build-runtime/src/androidTest/kotlin/...`.

---

## Spec References

- Design doc: `docs/superpowers/specs/2026-04-18-v2-gradle-compose-arch-design.md` §3.7 "exec wrapper"
- Prior plans (completed): Phase 0, 1a, 1b, 1c.

## Working Directory

**All file operations happen in the git worktree:** `/Users/skykai/Documents/work/VibeApp/.worktrees/v2-arch`

Branch: `v2-arch`. Current HEAD at plan write time: `686b60e`.

## File Structure

**Create:**

| File | Responsibility |
|---|---|
| `build-runtime/src/main/cpp/termux_exec/termux_exec.h` | Function prototypes for the shebang-rewriting `execve` override. |
| `build-runtime/src/main/cpp/termux_exec/termux_exec.c` | `LD_PRELOAD`-loaded override of `execve()` + helper that parses a shebang line and rewrites argv to `$PREFIX/bin/<interp>`. |
| `build-runtime/src/main/kotlin/com/vibe/build/runtime/process/PreloadLibLocator.kt` | Single-method helper that returns the absolute path to `libtermux-exec.so` inside the app's `nativeLibraryDir`. Kotlin wraps `android.content.pm.ApplicationInfo.nativeLibraryDir`. |
| `build-runtime/src/androidTest/kotlin/com/vibe/build/runtime/process/ShebangInstrumentedTest.kt` | 3 on-device tests that prove `libtermux-exec.so` rewrites `#!/usr/bin/env sh` scripts to working execs. |

**Modify:**

| File | Responsibility |
|---|---|
| `build-runtime/src/main/cpp/CMakeLists.txt` | Add a second `add_library(...)` target for `libtermux-exec.so`. |
| `build-runtime/src/main/kotlin/com/vibe/build/runtime/process/ProcessEnvBuilder.kt` | Inject `LD_PRELOAD=<abs-path-to-libtermux-exec.so>` into the base env map. |
| `build-runtime/src/test/kotlin/com/vibe/build/runtime/process/ProcessEnvBuilderTest.kt` | Add 2 tests: LD_PRELOAD present by default, LD_PRELOAD overridable via `extra`. |
| `app/src/main/kotlin/com/vibe/app/di/BuildRuntimeModule.kt` | Provide a `PreloadLibLocator` (needs `Context.applicationInfo.nativeLibraryDir`). Wire it into the `ProcessEnvBuilder` provider. |

**Do NOT touch in Phase 1d:**
- Real JDK/Gradle/SDK artifact publishing (Release prep workstream; separate from this plan).
- Production key ceremony (same).
- Aliyun mirror setup (same).
- Anything under `build-gradle/`, `plugin-host/`, `build-engine/`, `build-tools/`, `shadow-runtime/`, `feature/agent/`, `feature/project/`.

## Key Constraints (ALL TASKS)

1. **`libtermux-exec.so` must be a separate shared library**, NOT linked into `libbuildruntime.so`. `LD_PRELOAD` only works on standalone `.so`s and only affects the child process's dynamic linker — loading both into the same binary would either be unnecessary (for libbuildruntime, we call it directly) or broken (LD_PRELOAD lookups fail on already-resolved symbols).
2. **`libtermux-exec.so` must export `execve`** as a symbol with the same signature as libc's, so the dynamic linker resolves to our override first. The override delegates to the real libc `execve` via `dlsym(RTLD_NEXT, "execve")`.
3. **Only `execve` is overridden.** Not `execv`, not `execvp`, not `execle`. Reasons: (a) the only codepath we need to cover is `ProcessLauncher` → `execve` via `pl_launch` in `process_launcher.c`; (b) overriding fewer functions is less risky and still covers the Gradle-wrapper case because most shell/bin interpreters end up in `execve` eventually.
4. **Shebang parsing is STRICT** — only the form `#!/usr/bin/env <interpreter>[ <args>]` is handled. Any other shebang (e.g. `#!/bin/bash` with an absolute path, or no shebang at all) falls through to the original `execve` unchanged. This minimizes surprise and matches what Gradle wrapper scripts need.
5. **The `<interpreter>` token is resolved to `$PREFIX/bin/<interpreter>`**. `$PREFIX` is baked in as a compile-time macro `VIBEAPP_PREFIX` — declared in `CMakeLists.txt` via `target_compile_definitions`. The runtime path is `/data/user/0/com.vibe.app/files/usr` — this is what `BootstrapFileSystem.usrRoot` resolves to.
6. **`LD_PRELOAD` value is the ABSOLUTE PATH to `libtermux-exec.so`** as the OS lays it out — NOT a library name. On Android, after `jniLibs` extraction the files live under `Context.applicationInfo.nativeLibraryDir/libtermux-exec.so`. `PreloadLibLocator` returns that path.
7. **Do not commit the termux-exec source wholesale.** The shebang-rewrite logic we actually need is small (~150 LoC). A minimal clean-room implementation beats a partial termux-exec port both in maintainability and in reviewer-friendly commits. (VibeApp + termux-exec are both GPLv3 so either is legally fine — this is just a quality call.)
8. **Keep existing tests green.** Phase 1b/1c instrumented tests (`NativeProcessLauncherInstrumentedTest`, `BootstrapEndToEndInstrumentedTest`, `RuntimeBootstrapperIntegrationTest`) currently use `ProcessEnvBuilder` which will now include `LD_PRELOAD`. Make sure they still pass after Task 3 wires in the preload path.

---

## Task 1: `libtermux_exec` native library

**Files:**
- Create: `build-runtime/src/main/cpp/termux_exec/termux_exec.h`
- Create: `build-runtime/src/main/cpp/termux_exec/termux_exec.c`
- Modify: `build-runtime/src/main/cpp/CMakeLists.txt`

### Step 1: Create `build-runtime/src/main/cpp/termux_exec/termux_exec.h`

```c
/*
 * libtermux-exec.so — LD_PRELOAD-loaded execve override that rewrites
 * `#!/usr/bin/env <interpreter>` shebang lines into direct execve()
 * calls against $VIBEAPP_PREFIX/bin/<interpreter>.
 *
 * Scope:
 *   - Only execve() is overridden.
 *   - Only the `#!/usr/bin/env <name>[ <args>]` shebang form is handled.
 *   - Any other shebang form, or missing shebang, falls through to the
 *     real libc execve() unchanged.
 *
 * Compile-time constant:
 *   VIBEAPP_PREFIX: absolute path to the VibeApp usr root on device.
 *                   Injected by CMakeLists.txt via -DVIBEAPP_PREFIX=...
 */

#ifndef VIBEAPP_TERMUX_EXEC_H
#define VIBEAPP_TERMUX_EXEC_H

#include <sys/types.h>

int execve(const char* path, char* const argv[], char* const envp[]);

#endif
```

### Step 2: Create `build-runtime/src/main/cpp/termux_exec/termux_exec.c`

```c
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
```

### Step 3: Update `build-runtime/src/main/cpp/CMakeLists.txt`

Replace the existing file with:

```cmake
cmake_minimum_required(VERSION 3.22.1)
project(buildruntime C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

# -------- libbuildruntime.so --------
add_library(
    buildruntime SHARED
    process_launcher.c
    jni_process_launcher.c
)

find_library(log-lib log)
target_link_libraries(buildruntime ${log-lib})

# -------- libtermux-exec.so --------
# Loaded via LD_PRELOAD into child processes to rewrite
# "#!/usr/bin/env <interp>" shebangs to absolute paths under VibeApp's
# on-device PREFIX. See termux_exec/termux_exec.c for details.
add_library(
    termux-exec SHARED
    termux_exec/termux_exec.c
)

# PREFIX is the absolute path on device where VibeApp's bootstrap
# artifacts live (JDK, Gradle, toybox, etc.). Matches
# BootstrapFileSystem.usrRoot at runtime.
target_compile_definitions(termux-exec PRIVATE
    VIBEAPP_PREFIX="/data/user/0/com.vibe.app/files/usr"
)

target_link_libraries(termux-exec ${log-lib} dl)
```

### Step 4: Verify `:build-runtime:assembleDebug` produces both `.so` files

```bash
cd /Users/skykai/Documents/work/VibeApp/.worktrees/v2-arch
./gradlew --no-daemon :build-runtime:assembleDebug
find build-runtime/build -name "libtermux-exec.so" -type f
find build-runtime/build -name "libbuildruntime.so" -type f
```

Expected: both libraries present in `arm64-v8a`, `armeabi-v7a`, `x86_64` subdirectories of `merged_native_libs/debug/out/lib/`.

If CMake reports "unrecognized option '-std=c11'" or similar, verify the `cFlags` set in `build-runtime/build.gradle.kts` still include `-std=c11` from Phase 1b Task 1.

### Step 5: Commit

```bash
git add build-runtime/src/main/cpp/termux_exec/ \
        build-runtime/src/main/cpp/CMakeLists.txt
git commit -m "feat(build-runtime): libtermux-exec.so for shebang correction

Adds a minimal LD_PRELOAD-loaded execve() override that rewrites
'#!/usr/bin/env <interp>' shebangs into direct execve calls against
\$VIBEAPP_PREFIX/bin/<interp>. Scope intentionally narrow:

  - Only execve() is overridden (not execvp/execle/etc).
  - Only the #!/usr/bin/env form is rewritten.
  - Interpreter is resolved via compile-time VIBEAPP_PREFIX macro.
  - dlsym(RTLD_NEXT, 'execve') delegates to real libc when falling
    through.

~150 LoC clean-room implementation (not an import from termux-exec)
because VibeApp's PREFIX layout is fixed and we don't need the wider
runtime path resolution that termux-exec provides.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: `PreloadLibLocator` — Kotlin helper for the .so path

**Files:**
- Create: `build-runtime/src/main/kotlin/com/vibe/build/runtime/process/PreloadLibLocator.kt`

This tiny helper wraps the Android-specific lookup of the extracted native library path. The ProcessEnvBuilder uses it to construct the `LD_PRELOAD` value.

### Step 1: Create `PreloadLibLocator.kt`

```kotlin
package com.vibe.build.runtime.process

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Locates the absolute on-device path to `libtermux-exec.so` — the
 * preload library that rewrites `#!/usr/bin/env` shebangs inside
 * spawned child processes.
 *
 * Android extracts `.so` files from the APK's `jniLibs` directory into
 * the app's `nativeLibraryDir` at install time. We pass the app's
 * nativeLibraryDir in at construction (via Hilt) and expose a single
 * method that returns the absolute path of the preload library.
 */
@Singleton
class PreloadLibLocator @Inject constructor(
    private val nativeLibraryDir: File,
) {
    /**
     * Returns the absolute path to `libtermux-exec.so`.
     * The file is expected to exist on first use; if the platform has
     * not extracted it for some reason (very old Android + extractNativeLibs=false)
     * the returned path will still be well-formed but [java.io.File.isFile] will
     * return false. Callers that care should check existence.
     */
    fun termuxExecLibPath(): String = File(nativeLibraryDir, "libtermux-exec.so").absolutePath
}
```

### Step 2: Verify compilation

```bash
./gradlew --no-daemon :build-runtime:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

### Step 3: Commit

```bash
git add build-runtime/src/main/kotlin/com/vibe/build/runtime/process/PreloadLibLocator.kt
git commit -m "feat(build-runtime): PreloadLibLocator helper for LD_PRELOAD path

Resolves the absolute on-device path to libtermux-exec.so inside the
app's nativeLibraryDir. ProcessEnvBuilder will inject this as the
LD_PRELOAD entry in Task 3.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: Wire `LD_PRELOAD` into `ProcessEnvBuilder`

**Files:**
- Modify: `build-runtime/src/main/kotlin/com/vibe/build/runtime/process/ProcessEnvBuilder.kt`
- Modify: `build-runtime/src/test/kotlin/com/vibe/build/runtime/process/ProcessEnvBuilderTest.kt`
- Modify: `app/src/main/kotlin/com/vibe/app/di/BuildRuntimeModule.kt`

### Step 1: Update `ProcessEnvBuilder.kt`

Read the current file. Locate the `build()` function. Add `PreloadLibLocator` as a constructor dependency and emit `LD_PRELOAD` in the base map.

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
 *   LD_PRELOAD       <nativeLibraryDir>/libtermux-exec.so  (shebang correction)
 *   JAVA_HOME        usr/opt/jdk-17.0.13
 *   ANDROID_HOME     usr/opt/android-sdk
 *   GRADLE_USER_HOME filesDir/.gradle
 *   HOME             cwd
 *   TMPDIR           usr/tmp
 *
 * [extra] overrides any of the above.
 */
@Singleton
class ProcessEnvBuilder @Inject constructor(
    private val fs: BootstrapFileSystem,
    private val preloadLib: PreloadLibLocator,
) {

    fun build(cwd: File, extra: Map<String, String> = emptyMap()): Map<String, String> {
        val filesDir = fs.usrRoot.parentFile
            ?: error("BootstrapFileSystem.usrRoot has no parent; expected filesDir/usr")

        val base = mapOf(
            "PATH" to buildPath(),
            "LD_LIBRARY_PATH" to File(fs.usrRoot, "lib").absolutePath,
            "LD_PRELOAD" to preloadLib.termuxExecLibPath(),
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
        const val JDK_DIR_NAME = "jdk-17.0.13"
        const val ANDROID_SDK_DIR_NAME = "android-sdk"
        const val GRADLE_USER_HOME_DIR_NAME = ".gradle"
    }
}
```

### Step 2: Update `ProcessEnvBuilderTest.kt` (add 2 tests, fix existing)

All existing tests in this file construct `ProcessEnvBuilder(fs)` — that no longer compiles. Update every constructor call to pass a fake `PreloadLibLocator`. Add 2 new tests for the `LD_PRELOAD` entry.

Read the current file. Add this helper at the top of the class body (after `private fun newFs()`):

```kotlin
    private fun fakePreload(libPath: String = "/fake/native-lib-dir/libtermux-exec.so"): PreloadLibLocator =
        object : PreloadLibLocator(File("/fake/native-lib-dir")) {
            override fun termuxExecLibPath(): String = libPath
        }
```

Then for **every** existing test, update the `ProcessEnvBuilder(fs)` call to `ProcessEnvBuilder(fs, fakePreload())`. There are **7** existing tests to update.

Finally, append 2 new tests:

```kotlin
    @Test
    fun `build sets LD_PRELOAD to the preload lib path`() {
        val fs = newFs()
        val env = ProcessEnvBuilder(fs, fakePreload("/custom/libtermux-exec.so"))
            .build(cwd = temp.root, extra = emptyMap())
        assertEquals("/custom/libtermux-exec.so", env["LD_PRELOAD"])
    }

    @Test
    fun `extra map can override LD_PRELOAD`() {
        val fs = newFs()
        val env = ProcessEnvBuilder(fs, fakePreload("/base/libtermux-exec.so")).build(
            cwd = temp.root,
            extra = mapOf("LD_PRELOAD" to ""),   // consumer disables preload
        )
        assertEquals("", env["LD_PRELOAD"])
    }
```

Note: the default visibility of `PreloadLibLocator.termuxExecLibPath()` must allow the test subclass to override it. Make the class `open` and the method `open`:

Go back to `PreloadLibLocator.kt` and change `class PreloadLibLocator` → `open class PreloadLibLocator` and `fun termuxExecLibPath()` → `open fun termuxExecLibPath()`.

### Step 3: Run unit tests

```bash
./gradlew --no-daemon :build-runtime:testDebugUnitTest
```

Expected: 57 + 2 = **59 tests**, 0 failures.

### Step 4: Update `BuildRuntimeModule.kt` to provide `PreloadLibLocator`

Read `app/src/main/kotlin/com/vibe/app/di/BuildRuntimeModule.kt`. Locate `provideProcessEnvBuilder` (added in Phase 1b Task 6). Add a new provider for `PreloadLibLocator` and extend `provideProcessEnvBuilder`.

```kotlin
    @Provides
    @Singleton
    fun providePreloadLibLocator(
        @ApplicationContext context: Context,
    ): com.vibe.build.runtime.process.PreloadLibLocator =
        com.vibe.build.runtime.process.PreloadLibLocator(
            nativeLibraryDir = java.io.File(context.applicationInfo.nativeLibraryDir),
        )

    @Provides
    @Singleton
    fun provideProcessEnvBuilder(
        fs: BootstrapFileSystem,
        preloadLib: com.vibe.build.runtime.process.PreloadLibLocator,
    ): ProcessEnvBuilder = ProcessEnvBuilder(fs, preloadLib)
```

(Note: the existing `provideProcessEnvBuilder` returns `ProcessEnvBuilder(fs)` — update its signature + body. Clean up the fully-qualified `com.vibe.build.runtime.process...` by adding imports at the top of the file if you prefer.)

### Step 5: Verify the full Hilt graph compiles

```bash
./gradlew --no-daemon :app:kspDebugKotlin :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

### Step 6: Commit

```bash
git add build-runtime/src/main/kotlin/com/vibe/build/runtime/process/ProcessEnvBuilder.kt \
        build-runtime/src/main/kotlin/com/vibe/build/runtime/process/PreloadLibLocator.kt \
        build-runtime/src/test/kotlin/com/vibe/build/runtime/process/ProcessEnvBuilderTest.kt \
        app/src/main/kotlin/com/vibe/app/di/BuildRuntimeModule.kt
git commit -m "feat(build-runtime): inject LD_PRELOAD into child process env

ProcessEnvBuilder now emits LD_PRELOAD=<path-to-libtermux-exec.so> by
default. PreloadLibLocator resolves the path from the app's
nativeLibraryDir (where Android extracts jniLibs at install time).
Consumers can override by passing LD_PRELOAD in the extra map (e.g.
to disable shebang rewriting for a specific launch).

Phase 1d Task 3. 59 unit tests pass.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Instrumented test for shebang rewriting

**Files:**
- Create: `build-runtime/src/androidTest/kotlin/com/vibe/build/runtime/process/ShebangInstrumentedTest.kt`

This test proves Phase 1d acceptance: a shell script with `#!/usr/bin/env sh` runs correctly when invoked via `NativeProcessLauncher`.

### Step 1: Create `ShebangInstrumentedTest.kt`

```kotlin
package com.vibe.build.runtime.process

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vibe.build.runtime.bootstrap.BootstrapFileSystem
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Proves libtermux-exec.so rewrites '#!/usr/bin/env sh' correctly when
 * loaded via LD_PRELOAD in ProcessEnvBuilder. Requires API 29+
 * emulator or physical device.
 */
@RunWith(AndroidJUnit4::class)
class ShebangInstrumentedTest {

    private lateinit var ctx: Context
    private lateinit var fs: BootstrapFileSystem
    private lateinit var scratchDir: File
    private lateinit var launcher: NativeProcessLauncher

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        scratchDir = File(ctx.cacheDir, "shebang-test-${System.nanoTime()}")
        require(scratchDir.mkdirs())

        // BootstrapFileSystem is rooted at the app filesDir — the same place
        // $VIBEAPP_PREFIX resolves to at runtime in libtermux-exec.so's
        // compile-time constant. This means usr/bin/sh is the effective
        // interpreter path our shebang rewrites to.
        fs = BootstrapFileSystem(filesDir = ctx.filesDir)
        fs.ensureDirectories()

        // Stage a symlink to /system/bin/toybox as $PREFIX/bin/sh so that
        // "#!/usr/bin/env sh" rewrites to a working interpreter.
        val usrBin = File(fs.usrRoot, "bin").also { it.mkdirs() }
        val prefixedSh = File(usrBin, "sh")
        if (prefixedSh.exists()) prefixedSh.delete()
        // java.nio.file.Files.createSymbolicLink requires API 26+; we're
        // on minSdk 29. Fine.
        java.nio.file.Files.createSymbolicLink(
            prefixedSh.toPath(),
            File("/system/bin/toybox").toPath(),
        )

        val preloadLib = PreloadLibLocator(
            File(ctx.applicationInfo.nativeLibraryDir),
        )
        val envBuilder = ProcessEnvBuilder(fs, preloadLib)
        launcher = NativeProcessLauncher(envBuilder)
    }

    @After
    fun tearDown() {
        scratchDir.deleteRecursively()
        // Remove the symlink we created so other instrumented tests
        // that reuse filesDir aren't affected.
        File(fs.usrRoot, "bin/sh").delete()
    }

    @Test
    fun shebang_envSh_script_runs_and_produces_expected_stdout() = runBlocking {
        val script = File(scratchDir, "hello.sh").apply {
            writeText("#!/usr/bin/env sh\necho shebang-ok\n")
            setExecutable(true)
        }

        val process = launcher.launch(
            executable = script.absolutePath,
            args = emptyList(),
            cwd = scratchDir,
        )
        val events = withTimeout(10_000) { process.events.toList() }

        val stdout = events.filterIsInstance<ProcessEvent.Stdout>()
            .fold(ByteArray(0)) { acc, e -> acc + e.bytes }
        val exit = events.filterIsInstance<ProcessEvent.Exited>().first()

        assertEquals(0, exit.code)
        assertEquals("shebang-ok\n", String(stdout, Charsets.UTF_8))
    }

    @Test
    fun script_with_absolute_shebang_still_runs_unchanged() = runBlocking {
        // "#!/system/bin/toybox sh" is an absolute path — libtermux-exec
        // should NOT rewrite; the kernel's own shebang handling takes
        // over. Proves our override falls through cleanly.
        val script = File(scratchDir, "abs.sh").apply {
            writeText("#!/system/bin/toybox sh\necho abs-ok\n")
            setExecutable(true)
        }

        val process = launcher.launch(
            executable = script.absolutePath,
            args = emptyList(),
            cwd = scratchDir,
        )
        val events = withTimeout(10_000) { process.events.toList() }

        val stdout = events.filterIsInstance<ProcessEvent.Stdout>()
            .fold(ByteArray(0)) { acc, e -> acc + e.bytes }
        val exit = events.filterIsInstance<ProcessEvent.Exited>().first()

        assertEquals(0, exit.code)
        assertTrue(
            "expected 'abs-ok' in stdout, got: ${String(stdout, Charsets.UTF_8)}",
            String(stdout, Charsets.UTF_8).contains("abs-ok"),
        )
    }

    @Test
    fun direct_binary_exec_unaffected_by_preload() = runBlocking {
        // Execing a real binary directly should be indistinguishable from
        // the no-preload case. This guards against the override breaking
        // non-script launches.
        val process = launcher.launch(
            executable = "/system/bin/toybox",
            args = listOf("echo", "direct-ok"),
            cwd = scratchDir,
        )
        val events = withTimeout(10_000) { process.events.toList() }

        val stdout = events.filterIsInstance<ProcessEvent.Stdout>()
            .fold(ByteArray(0)) { acc, e -> acc + e.bytes }
        val exit = events.filterIsInstance<ProcessEvent.Exited>().first()

        assertEquals(0, exit.code)
        assertEquals("direct-ok\n", String(stdout, Charsets.UTF_8))
    }
}
```

### Step 2: Run the instrumented test on an emulator

```bash
./gradlew --no-daemon :build-runtime:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.vibe.build.runtime.process.ShebangInstrumentedTest
```

Expected: 3 tests, 0 failures. If `libtermux-exec.so` isn't found, double-check:
- `adb -s <emulator-id> shell ls /data/app/...==/com.vibe.app-*/lib/arm64/` should list `libbuildruntime.so` AND `libtermux-exec.so`. If only the former is present, the APK packaging didn't pick up the second `.so` — check the `assembleDebug` artifact.

If a test fails:
- Test 1 exit != 0 → libtermux-exec override didn't rewrite. Check logcat for any dyld messages. Check `adb shell run-as com.vibe.app file /data/user/0/com.vibe.app/files/usr/bin/sh` (should be a symlink).
- Test 2 stdout missing "abs-ok" → override incorrectly rewrote an absolute shebang. Check `parse_env_shebang` — it should return 0 for anything that doesn't start with `#!/usr/bin/env `.
- Test 3 fails → the override is affecting direct-binary execs (bug in the fall-through logic). Check `read_first_line` error handling.

### Step 3: Run the full instrumented test suite to confirm no regression

```bash
./gradlew --no-daemon :build-runtime:connectedDebugAndroidTest
```

Expected: 1 (Phase 1a) + 4 (Phase 1b) + 1 (Phase 1c) + 3 (Phase 1d) = **9 instrumented tests**, 0 failures.

**IMPORTANT**: the Phase 1b `NativeProcessLauncherInstrumentedTest` and the Phase 1c `BootstrapEndToEndInstrumentedTest` both use `ProcessEnvBuilder`, which now emits `LD_PRELOAD`. Those tests launch `/system/bin/toybox` (not a script), so they should be unaffected. If ANY of those tests now fail, it means `libtermux-exec.so` is misbehaving on direct binary execs — the override must be transparent for non-shebang files. STOP and report BLOCKED.

### Step 4: Commit

```bash
git add build-runtime/src/androidTest/kotlin/com/vibe/build/runtime/process/ShebangInstrumentedTest.kt
git commit -m "test(build-runtime): instrumented tests for libtermux-exec shebang rewriting

Three tests on an API 29+ emulator:
 1. '#!/usr/bin/env sh' rewrites to \$PREFIX/bin/sh and runs correctly
 2. '#!/system/bin/toybox sh' (absolute shebang) passes through unchanged
 3. Direct binary exec (no shebang) is unaffected by LD_PRELOAD

Phase 1d Task 4. 9 instrumented tests total across Phase 1.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: Documentation + Phase 1d wrap-up

**Files:**
- Modify: `docs/superpowers/specs/2026-04-18-v2-gradle-compose-arch-design.md` §3.7 (add a note that libtermux-exec.so is in place)
- Create: `docs/bootstrap/release-prep-checklist.md` (the deferred Thread B of the original Phase 1d — now a standalone maintainer checklist)

### Step 1: Update the design doc §3.7

Read `docs/superpowers/specs/2026-04-18-v2-gradle-compose-arch-design.md`. Find §3.7 "exec wrapper". Add an implementation-status note at the end of that subsection (before §3.8):

```markdown
**Implementation status (as of Phase 1d):** `libtermux-exec.so` is a
~150 LoC clean-room C implementation under
`build-runtime/src/main/cpp/termux_exec/`. It overrides only
`execve()` and rewrites only the `#!/usr/bin/env <interp>` shebang
form. Interpreter is resolved to a compile-time-baked `VIBEAPP_PREFIX`
(`/data/user/0/com.vibe.app/files/usr`). LD_PRELOAD is set by
`ProcessEnvBuilder` on every child process launch. Covered by
`ShebangInstrumentedTest` (3 tests on API 29+ emulator).
```

### Step 2: Create `docs/bootstrap/release-prep-checklist.md`

```markdown
# VibeApp v2.0 Release Preparation Checklist

> Prerequisite for shipping v2.0 to users. Phase 1 (code side) is
> complete after Phase 1d; this checklist covers the DevOps side —
> producing the real 180MB bootstrap artifacts, generating a
> production signing key, and publishing to GitHub Release +
> mirror.
>
> None of this blocks further Phase 2-7 development on the code
> side; do these tasks on whatever schedule fits the release
> timeline.

## 1. Prepare the real Tier-1 artifacts (~90 min)

For each of the three ABIs (arm64-v8a, armeabi-v7a, x86_64):

1. Download **Adoptium Temurin 17.0.13 JDK** for the ABI. Source:
   https://adoptium.net/temurin/releases/?version=17
2. Unpack and strip:
   ```bash
   cd <jdk-unpack-dir>
   rm -rf demo man sample src.zip legal
   find . -name "*.diz" -delete
   ```
3. Repack as zstd-compressed tar:
   ```bash
   tar -cf - . | zstd -19 -o /tmp/jdk-17.0.13-${ABI}.tar.zst
   ```

For Gradle 8.10.2:
1. Download `gradle-8.10.2-bin.zip` from https://gradle.org/releases/
2. Unpack, then repack as tar.zst (no stripping needed).

For the minimal Android SDK:
1. Use `sdkmanager` to fetch only `platforms;android-34` and
   `build-tools;34.0.0`.
2. Pre-accept licenses: copy accepted license files from
   `$ANDROID_HOME/licenses/` into the sdk tree being packed.
3. Repack as tar.zst.

Compute SHA-256 for each artifact:
```bash
sha256sum /tmp/*.tar.zst > /tmp/artifact-hashes.txt
```

## 2. Production Ed25519 key ceremony (~30 min)

**Do this on an air-gapped machine if possible.**

```bash
# Generate on a secure host
mkdir -p /secure/vibeapp-keys
cd /secure/vibeapp-keys

# Generate Ed25519 keypair (any Ed25519-capable tool; use whatever
# the release engineer trusts — openssl, ssh-keygen, tink, or a
# small Kotlin program)

# Result should be two files:
#   vibeapp-prod-ed25519.priv  (32-byte seed, hex-encoded)
#   vibeapp-prod-ed25519.pub   (32-byte public key, hex-encoded)

# Store the private key in password-protected storage (1Password,
# HSM, etc.). NEVER commit it. NEVER store it unencrypted on a
# networked machine.
```

## 3. Inject production pubkey via CI secret

In the VibeApp GitHub repo settings, add a **repository secret**:

  Name:  `BOOTSTRAP_PRODUCTION_PUBKEY_HEX`
  Value: <64-char hex from vibeapp-prod-ed25519.pub>

Update `.github/workflows/release-build.yml` to wire it:

```yaml
- name: Build release APK
  run: ./gradlew :app:assembleRelease
  env:
    BOOTSTRAP_PUBKEY_HEX: ${{ secrets.BOOTSTRAP_PRODUCTION_PUBKEY_HEX }}
```

And in `app/build.gradle.kts`:

```kotlin
android {
    defaultConfig {
        buildConfigField(
            "String",
            "BOOTSTRAP_PUBKEY_HEX",
            "\"${System.getenv("BOOTSTRAP_PUBKEY_HEX") ?:
                com.vibe.app.di.BuildRuntimeModule.BOOTSTRAP_PUBKEY_HEX}\"",
        )
    }
    buildFeatures { buildConfig = true }
}
```

Then change `BuildRuntimeModule.provideManifestSignature` to prefer
`BuildConfig.BOOTSTRAP_PUBKEY_HEX` over the dev const:

```kotlin
@Provides
@Singleton
fun provideManifestSignature(): ManifestSignature =
    ManifestSignature(publicKeyHex = com.vibe.app.BuildConfig.BOOTSTRAP_PUBKEY_HEX)
```

(Debug builds will fall back to the dev const if the env var is unset.)

## 4. Build and sign the manifest (~15 min)

Construct `manifest.json` with the 5 artifact entries + their
SHA-256 hashes from Step 1. Example:

```json
{
  "schemaVersion": 1,
  "manifestVersion": "v2.0.0",
  "components": [
    {
      "id": "jdk-17.0.13",
      "version": "17.0.13",
      "artifacts": {
        "arm64-v8a": {
          "fileName": "jdk-17.0.13-arm64-v8a.tar.zst",
          "sizeBytes": 83_000_000,
          "sha256": "<hex>"
        },
        ...
      }
    },
    ...
  ]
}
```

Sign with the production private key:

```bash
# Pseudocode — use whatever Ed25519 signer you trust, as long as
# the signature format matches net.i2p.crypto:eddsa's output format
# (64-byte raw signature).
ed25519-signer --key vibeapp-prod-ed25519.priv \
    --in manifest.json \
    --out manifest.json.sig
```

## 5. Upload to GitHub Release

1. Create release tag `v2.0.0` (push the tag from dev branch).
2. Upload all 6 files to the release:
   - `manifest.json`
   - `manifest.json.sig`
   - `jdk-17.0.13-{arm64-v8a,armeabi-v7a,x86_64}.tar.zst`
   - `gradle-8.10.2-noarch.tar.zst`
   - `android-sdk-34-minimal.tar.zst`
3. Set release notes describing v2.0 features.

## 6. Aliyun mirror

Create an Aliyun OSS bucket `vibeapp-cdn` in `oss-cn-hangzhou`.
Sync the 6 release files:

```bash
ossutil cp -r /path/to/release-files/ oss://vibeapp-cdn/releases/v2.0.0/
```

The `MirrorSelector` in `BuildRuntimeModule.kt` already points at
`https://vibeapp-cdn.oss-cn-hangzhou.aliyuncs.com/releases/v2.0.0`
as the fallback.

## 7. Final validation

On a clean device:
1. Install the release APK.
2. Open Settings → Build Runtime (debug).
3. Tap "Trigger bootstrap" — watch state cycle through Downloading
   → Verifying → Unpacking → Installing → Ready.
4. Tap "Launch toybox echo" — confirm stdout "debug-launch OK".
5. Via `adb shell`, verify `/data/user/0/com.vibe.app/files/usr/opt/jdk-17.0.13/bin/java -version` returns `17.0.13`.

If any step fails, tear down and debug before publishing.
```

### Step 3: Verify everything still green

```bash
cd /Users/skykai/Documents/work/VibeApp/.worktrees/v2-arch
./gradlew --no-daemon :build-runtime:testDebugUnitTest \
    :build-runtime:lintDebug \
    :app:kspDebugKotlin \
    :app:assembleDebug \
    :build-runtime:connectedDebugAndroidTest
```

Expected:
- 59 unit tests, 0 failures.
- 9 instrumented tests, 0 failures.
- `:app:assembleDebug` BUILD SUCCESSFUL.

### Step 4: Commit

```bash
git add docs/superpowers/specs/2026-04-18-v2-gradle-compose-arch-design.md \
        docs/bootstrap/release-prep-checklist.md
git commit -m "docs: Phase 1d wrap-up + release-prep checklist

- Design doc §3.7 updated with libtermux-exec.so implementation status
- New release-prep-checklist.md covers the deferred Thread B work
  (real 180MB artifact build, production key ceremony, GitHub Release
  + Aliyun mirror upload). Standalone maintainer checklist; does NOT
  block Phases 2-7 code development.

Phase 1d complete.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Phase 1d Exit Criteria

- [ ] `./gradlew :build-runtime:testDebugUnitTest` passes with 59 tests, 0 failures.
- [ ] `./gradlew :build-runtime:assembleDebug` produces BOTH `libbuildruntime.so` AND `libtermux-exec.so` for all 3 ABIs.
- [ ] `./gradlew :app:kspDebugKotlin` compiles with the extended Hilt graph.
- [ ] `./gradlew :app:assembleDebug` produces a debug APK.
- [ ] `./gradlew :build-runtime:connectedDebugAndroidTest` passes 9 instrumented tests on an API 29+ emulator.
- [ ] `/system/bin/toybox` direct-exec path from Phase 1b is unaffected by `LD_PRELOAD` (tested by the Phase 1b instrumented test; regression-checked in Task 4).
- [ ] `docs/bootstrap/release-prep-checklist.md` exists and describes the real-artifact thread.
- [ ] No changes to `:build-engine`, `:build-tools:*`, `:shadow-runtime`, `:build-gradle`, `:plugin-host`.

When these boxes are checked, Phase 1d (code thread) is complete. Phase 2 (GradleHost + Tooling API + first APK) is now unblocked — the Gradle wrapper's `#!/usr/bin/env sh` shebang will resolve correctly on device.

The release-prep thread (real 180MB artifacts + production key + GitHub Release + Aliyun mirror) runs independently on the maintainer's schedule per `docs/bootstrap/release-prep-checklist.md`.

---

## Self-Review Notes

**Spec coverage against design doc §3.7:**
- `libtermux-exec.so` exists, loaded via `LD_PRELOAD`, handles `#!/usr/bin/env <interp>` rewriting → Task 1.
- `ProcessEnvBuilder` emits `LD_PRELOAD` → Task 3.
- Unit tests for `ProcessEnvBuilder` cover default preload + override → Task 3.
- Instrumented tests cover positive (shebang script), pass-through (absolute shebang), and regression (direct binary) cases → Task 4.

**Placeholders / gaps:**
- `VIBEAPP_PREFIX` is baked at compile time as `/data/user/0/com.vibe.app/files/usr`. If the app's `applicationId` changes in the future (unlikely — it's `com.vibe.app` since v1), the CMake constant must update. Documented in `termux_exec.c`'s header comment.
- No coverage of multi-user Android where `user/0` isn't the right uid. Practical exposure is near-zero (VibeApp is not a system app) but worth a Phase 5+ review if multi-user support is ever added.
- Real 180MB artifact publishing is explicitly deferred — a checklist is left for the maintainer.

**Type consistency:**
- `PreloadLibLocator(nativeLibraryDir: File)` constructor matches the DI provider's `File(context.applicationInfo.nativeLibraryDir)` construction.
- `ProcessEnvBuilder(fs, preloadLib)` matches the DI provider's parameters and every test's invocation (all 9 tests).
- `ShebangInstrumentedTest` constructs `ProcessEnvBuilder(fs, PreloadLibLocator(File(...)))` — matches.
- `LD_PRELOAD` map key identical across `ProcessEnvBuilder.build()`, the unit tests, and child-process behavior.
- CMake target name `termux-exec` → output `libtermux-exec.so` — referenced consistently in `PreloadLibLocator.termuxExecLibPath()`.

No "TBD", "TODO", "similar to Task N", or "add error handling" placeholders remain. Where future-phase items are referenced (BuildConfig prod pubkey, real artifact hosting, production key ceremony), they are clearly marked as belonging to the release-prep thread.
