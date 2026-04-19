package com.vibe.app.plugin.v2

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Packages the user's standalone plugin APK together with Shadow's
 * loader + runtime APKs (already extracted into `filesDir/shadow/`)
 * into the zip format Shadow's `UnpackManager` expects.
 *
 * Zip layout:
 *   config.json
 *   loader.apk
 *   runtime.apk
 *   plugin.apk
 *
 * `config.json` structure (parseable by `PluginConfig.parseFromJson`):
 * ```
 * {
 *   "version": 4,
 *   "UUID": "<stable uuid per project>",
 *   "UUID_NickName": "<human-readable id>",
 *   "pluginLoader": { "apkName": "loader.apk", "hash": "<sha256>" },
 *   "runtime":      { "apkName": "runtime.apk", "hash": "<sha256>" },
 *   "plugins": [
 *     {
 *       "partKey": "<partKey>",
 *       "businessName": "<pkg>",
 *       "apkName": "plugin.apk",
 *       "hash": "<sha256>",
 *       "dependsOn": [],
 *       "hostWhiteList": []
 *     }
 *   ]
 * }
 * ```
 *
 * Shadow's current zip config format version is 4 (`PluginConfig.version`
 * field asserted by `parseFromJson`).
 */
internal object ShadowPluginZipBuilder {

    private const val TAG = "ShadowPluginZipBuilder"
    private const val CONFIG_VERSION = 4

    fun build(
        outputZip: File,
        loaderApk: File,
        runtimeApk: File,
        pluginApk: File,
        partKey: String,
        packageName: String,
        uuid: String = UUID.randomUUID().toString(),
        uuidNickName: String = partKey,
    ): BuiltZip {
        val loaderHash = sha256(loaderApk)
        val runtimeHash = sha256(runtimeApk)
        val pluginHash = sha256(pluginApk)

        val config = JSONObject().apply {
            put("version", CONFIG_VERSION)
            put("UUID", uuid)
            put("UUID_NickName", uuidNickName)
            put("pluginLoader", JSONObject().apply {
                put("apkName", "loader.apk")
                put("hash", loaderHash)
            })
            put("runtime", JSONObject().apply {
                put("apkName", "runtime.apk")
                put("hash", runtimeHash)
            })
            put("plugins", JSONArray().apply {
                put(JSONObject().apply {
                    put("partKey", partKey)
                    put("businessName", packageName)
                    put("apkName", "plugin.apk")
                    put("hash", pluginHash)
                    put("dependsOn", JSONArray())
                    put("hostWhiteList", JSONArray())
                })
            })
        }

        Log.d(TAG, "Writing plugin zip $outputZip for $packageName (uuid=$uuid)")
        outputZip.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(outputZip)).use { zip ->
            zip.putEntry("config.json", config.toString(2).toByteArray())
            zip.putEntry("loader.apk", loaderApk)
            zip.putEntry("runtime.apk", runtimeApk)
            zip.putEntry("plugin.apk", pluginApk)
        }

        return BuiltZip(zip = outputZip, uuid = uuid, pluginHash = pluginHash)
    }

    data class BuiltZip(val zip: File, val uuid: String, val pluginHash: String)

    private fun ZipOutputStream.putEntry(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }

    private fun ZipOutputStream.putEntry(name: String, file: File) {
        putNextEntry(ZipEntry(name))
        FileInputStream(file).use { it.copyTo(this) }
        closeEntry()
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
