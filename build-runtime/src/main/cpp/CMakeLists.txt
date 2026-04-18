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
