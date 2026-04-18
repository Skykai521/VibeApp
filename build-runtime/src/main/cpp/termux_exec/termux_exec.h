/*
 * termux_exec.h — public header for libtermux-exec.so
 *
 * libtermux-exec.so is loaded via LD_PRELOAD into child processes spawned
 * by the VibeApp build runtime. It intercepts execve() and rewrites
 * "#!/usr/bin/env <interp>" shebang lines so the interpreter is resolved
 * from VIBEAPP_PREFIX/bin rather than /usr/bin/env (which does not exist
 * on Android without a complete Termux bootstrap).
 *
 * Design constraints:
 *   - Only execve() is overridden (not execvp/execle/etc).
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
