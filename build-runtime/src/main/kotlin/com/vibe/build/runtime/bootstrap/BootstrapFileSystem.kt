package com.vibe.build.runtime.bootstrap

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject

/**
 * Encapsulates the on-device bootstrap filesystem layout under
 * `filesDir/usr/` (design doc §3.2).
 *
 * Single source of truth for paths. Other classes must NOT hard-code
 * `filesDir/usr/...` strings.
 */
class BootstrapFileSystem @Inject constructor(
    private val filesDir: File,
) {
    /** `$filesDir/usr/` — the $PREFIX root. */
    val usrRoot: File = File(filesDir, "usr")

    /** `$filesDir/usr/opt/` — where component install directories live. */
    val optRoot: File = File(usrRoot, "opt")

    /** `$filesDir/usr/tmp/` — staging area for in-flight downloads & extractions. */
    val tmpRoot: File = File(usrRoot, "tmp")

    /** Final install directory for a component (e.g. `jdk-17.0.13`). */
    fun componentInstallDir(componentId: String): File = File(optRoot, componentId)

    /** Path for in-flight .part file during a download. */
    fun tempDownloadFile(artifactFileName: String): File =
        File(tmpRoot, "$artifactFileName.part")

    /** Staged extraction directory (before atomic install). */
    fun stagedExtractDir(componentId: String): File =
        File(tmpRoot, "staged-$componentId")

    /** Creates `usr/`, `usr/opt/`, `usr/tmp/` if missing. Idempotent. */
    fun ensureDirectories() {
        require(optRoot.mkdirs() || optRoot.isDirectory)
        require(tmpRoot.mkdirs() || tmpRoot.isDirectory)
    }

    /**
     * Atomically swap a staged directory into its final `usr/opt/{componentId}/`
     * position. If a previous install exists for this componentId, it is
     * replaced. Uses `Files.move` with `ATOMIC_MOVE` where supported, falling
     * back to non-atomic replace if the filesystem rejects atomic (rare on
     * Android ext4).
     */
    fun atomicInstall(staged: File, componentId: String) {
        require(staged.isDirectory) { "staged path is not a directory: $staged" }
        val finalDir = componentInstallDir(componentId)
        if (finalDir.exists()) finalDir.deleteRecursively()
        try {
            Files.move(
                staged.toPath(),
                finalDir.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (e: Exception) {
            if (!staged.renameTo(finalDir)) {
                throw ExtractionFailedException(
                    "Could not install component '$componentId': move failed", e,
                )
            }
        }
    }
}
