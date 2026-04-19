package com.vibe.app.plugin.v2

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Extracts `assets/shadow/plugin-repo.zip` — a bundled local Maven
 * repo containing Shadow's Gradle plugin + its vendored transform deps
 * (produced by `:app:copyShadowPluginRepo`) — into
 * `filesDir/shadow/plugin-repo/` so on-device Gradle builds of v2
 * project templates can resolve `com.tencent.shadow.plugin` from it.
 *
 * Sidecar `.sha256` gate: repeat calls are cheap; re-extracts only
 * when the asset bytes change (app upgrade with a new Shadow build).
 */
@Singleton
class ShadowPluginRepoExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Returns the extracted repo directory, freshening if needed. */
    fun extractIfNeeded(): File {
        val outDir = File(context.filesDir, REPO_DIR).apply { mkdirs() }
        val hashFile = File(outDir, ".sha256")
        val zipBytes = context.assets.open(ASSET_ZIP).use { it.readBytes() }
        val newHash = sha256(zipBytes)

        val needsExtract = !hashFile.exists() ||
            hashFile.readText().trim() != newHash ||
            !repoLooksComplete(outDir)

        if (needsExtract) {
            Log.i(TAG, "Extracting shadow plugin-repo (${zipBytes.size} bytes) -> ${outDir.absolutePath}")
            // Wipe stale repo state but keep the parent dir.
            outDir.listFiles()?.forEach { it.deleteRecursively() }
            ZipInputStream(zipBytes.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val file = File(outDir, entry.name)
                    if (entry.isDirectory) {
                        file.mkdirs()
                    } else {
                        file.parentFile?.mkdirs()
                        file.outputStream().use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                }
            }
            hashFile.writeText(newHash)
        }
        return outDir
    }

    private fun repoLooksComplete(repoDir: File): Boolean =
        File(repoDir, MARKER_POM_PATH).exists()

    private fun sha256(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "ShadowPluginRepoExtractor"
        private const val REPO_DIR = "shadow/plugin-repo"
        private const val ASSET_ZIP = "shadow/plugin-repo.zip"
        // Sanity marker; presence implies a complete extraction.
        private const val MARKER_POM_PATH =
            "com/tencent/shadow/plugin/com.tencent.shadow.plugin.gradle.plugin/1.0/com.tencent.shadow.plugin.gradle.plugin-1.0.pom"
    }
}
