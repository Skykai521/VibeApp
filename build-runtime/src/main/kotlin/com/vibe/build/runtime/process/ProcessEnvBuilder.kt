package com.vibe.build.runtime.process

import com.vibe.build.runtime.bootstrap.BootstrapFileSystem
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Composes the environment variable map passed to every native process.
 *
 * Variables set:
 *   PATH                usr/bin:/system/bin:/system/xbin
 *   LD_LIBRARY_PATH     <JAVA_HOME>/lib/server:<JAVA_HOME>/lib:usr/lib:<ANDROID_HOME>/build-tools/36.0.0
 *   LD_PRELOAD          <nativeLibraryDir>/libtermux-exec.so  (shebang correction)
 *   VIBEAPP_USR_PREFIX  usr                                   (read by libtermux-exec.so)
 *   JAVA_HOME           usr/opt/jdk-17.0.13
 *   ANDROID_HOME        usr/opt/android-sdk-36.0.0
 *   GRADLE_USER_HOME    filesDir/.gradle
 *   HOME                cwd
 *   TMPDIR              usr/tmp
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
        val javaHome = File(fs.optRoot, JDK_DIR_NAME)
        val androidHome = File(fs.optRoot, ANDROID_SDK_DIR_NAME)

        val base = mapOf(
            "PATH" to buildPath(),
            "LD_LIBRARY_PATH" to buildLdLibraryPath(javaHome, androidHome),
            "LD_PRELOAD" to preloadLib.termuxExecLibPath(),
            "VIBEAPP_USR_PREFIX" to fs.usrRoot.absolutePath,
            "JAVA_HOME" to javaHome.absolutePath,
            "ANDROID_HOME" to androidHome.absolutePath,
            "GRADLE_USER_HOME" to File(filesDir, GRADLE_USER_HOME_DIR_NAME).absolutePath,
            "HOME" to cwd.absolutePath,
            "TMPDIR" to File(fs.usrRoot, "tmp").absolutePath,
        )

        return base + extra
    }

    private fun buildLdLibraryPath(javaHome: File, androidHome: File): String = listOf(
        // JDK's own lib dirs first so libjvm.so (no $ORIGIN in its
        // RUNPATH) can still find co-bundled runtime deps
        // (libandroid-shmem.so, libz.so, etc.).
        File(javaHome, "lib/server").absolutePath,
        File(javaHome, "lib").absolutePath,
        // Shared usr/lib for any future cross-component libs.
        File(fs.usrRoot, "lib").absolutePath,
        // aapt2's runtime dep closure (libabsl_*.so, libprotobuf.so, libc++_shared.so,
        // libandroid-*, libexpat, libpng16, etc.) ships in the SDK tarball next to the
        // aapt2 binary. aapt2's DT_RUNPATH points at a Termux-internal path that
        // doesn't exist on our device, so the linker needs this entry to resolve them.
        File(androidHome, "build-tools/36.0.0").absolutePath,
    ).joinToString(separator = ":")

    private fun buildPath(): String = listOf(
        File(fs.usrRoot, "bin").absolutePath,
        "/system/bin",
        "/system/xbin",
    ).joinToString(separator = ":")

    companion object {
        const val JDK_DIR_NAME = "jdk-17.0.13"
        const val ANDROID_SDK_DIR_NAME = "android-sdk-36.0.0"
        const val GRADLE_USER_HOME_DIR_NAME = ".gradle"
    }
}
