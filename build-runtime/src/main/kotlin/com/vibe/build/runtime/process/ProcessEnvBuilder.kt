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
