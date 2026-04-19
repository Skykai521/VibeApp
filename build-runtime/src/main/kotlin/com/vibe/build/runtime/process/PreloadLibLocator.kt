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
open class PreloadLibLocator @Inject constructor(
    private val nativeLibraryDir: File,
) {
    /**
     * Returns the absolute path to `libtermux-exec.so`.
     * The file is expected to exist on first use; if the platform has
     * not extracted it for some reason (very old Android + extractNativeLibs=false)
     * the returned path will still be well-formed but [java.io.File.isFile] will
     * return false. Callers that care should check existence.
     */
    open fun termuxExecLibPath(): String = File(nativeLibraryDir, "libtermux-exec.so").absolutePath
}
