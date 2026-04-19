package com.vibe.build.gradle

import android.content.Context
import com.vibe.build.runtime.bootstrap.BootstrapFileSystem
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Extracts the shaded GradleHost fat JAR from the APK assets into
 * `$PREFIX/opt/vibeapp-gradle-host/vibeapp-gradle-host.jar` on first
 * use. Subsequent calls are a no-op unless the asset's SHA-256 has
 * changed (e.g. after a VibeApp upgrade), in which case the cached
 * copy is replaced.
 *
 * On-disk layout (matches other bootstrap components, even though
 * this one is NOT downloaded):
 *   $PREFIX/opt/vibeapp-gradle-host/
 *     vibeapp-gradle-host.jar
 *     .sha256
 */
@Singleton
class GradleHostExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fs: BootstrapFileSystem,
) {

    /**
     * Ensures the JAR is present on disk and returns its [File].
     * Thread-safe via synchronization; the extraction is idempotent.
     */
    @Synchronized
    fun ensureExtracted(): File {
        val componentDir = fs.componentInstallDir(COMPONENT_ID)
        val jarFile = File(componentDir, JAR_NAME)
        val hashFile = File(componentDir, HASH_FILE)

        val assetHash = computeAssetHash()
        val cachedHash = if (hashFile.exists()) hashFile.readText().trim() else null

        if (jarFile.isFile && cachedHash == assetHash) {
            return jarFile
        }

        componentDir.mkdirs()
        context.assets.open(ASSET_NAME).use { input ->
            jarFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        hashFile.writeText(assetHash)
        return jarFile
    }

    private fun computeAssetHash(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        context.assets.open(ASSET_NAME).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val ASSET_NAME = "vibeapp-gradle-host.jar"
        private const val COMPONENT_ID = "vibeapp-gradle-host"
        private const val JAR_NAME = "vibeapp-gradle-host.jar"
        private const val HASH_FILE = ".sha256"
    }
}
