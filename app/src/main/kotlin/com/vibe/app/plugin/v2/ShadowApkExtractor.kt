package com.vibe.app.plugin.v2

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * Copies Shadow's loader.apk + runtime.apk out of the host APK's
 * `assets/shadow/` into `filesDir/shadow/` on first use, so Shadow's
 * DexClassLoader path can mmap them from a regular file.
 *
 * Reruns when the asset bytes change (SHA-256 match against a sidecar
 * `.sha256` file in filesDir/shadow/). Skips work otherwise.
 */
internal class ShadowApkExtractor(private val context: Context) {

    fun extractIfNeeded(): ExtractedApks {
        val outDir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
        val loader = extract(ASSET_LOADER, File(outDir, "loader.apk"))
        val runtime = extract(ASSET_RUNTIME, File(outDir, "runtime.apk"))
        return ExtractedApks(loader = loader, runtime = runtime)
    }

    private fun extract(assetPath: String, target: File): File {
        val assetBytes = context.assets.open(assetPath).use { it.readBytes() }
        val newHash = sha256(assetBytes)
        val hashFile = File(target.parentFile, "${target.name}.sha256")

        val needsWrite = !target.exists() ||
            !hashFile.exists() ||
            hashFile.readText().trim() != newHash

        if (needsWrite) {
            Log.i(TAG, "Extracting ${assetPath} -> ${target.absolutePath} (${assetBytes.size} bytes)")
            target.writeBytes(assetBytes)
            hashFile.writeText(newHash)
        }
        return target
    }

    private fun sha256(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    data class ExtractedApks(val loader: File, val runtime: File)

    companion object {
        private const val TAG = "ShadowApkExtractor"
        private const val DIR_NAME = "shadow"
        private const val ASSET_LOADER = "shadow/loader.apk"
        private const val ASSET_RUNTIME = "shadow/runtime.apk"
    }
}
