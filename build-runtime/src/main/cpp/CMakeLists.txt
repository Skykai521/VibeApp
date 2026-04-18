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
