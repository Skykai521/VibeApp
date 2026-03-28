package com.vibe.build.engine.shadow

import android.util.Log
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

object ShadowAndroidxTransformer {

    private const val TAG = "ShadowTransformer"
    private const val CACHE_FILE_NAME = "shadow-androidx-classes.jar"
    private const val HASH_FILE_NAME = "shadow-androidx-classes.jar.md5"

    fun getOrTransform(androidxJar: File, cacheDir: File): File {
        val cached = File(cacheDir, CACHE_FILE_NAME)
        val hashFile = File(cacheDir, HASH_FILE_NAME)

        val currentHash = md5(androidxJar)
        if (cached.exists() && hashFile.exists() && hashFile.readText() == currentHash) {
            log("Cache hit for shadow-androidx-classes.jar")
            return cached
        }

        log("Transforming androidx-classes.jar with ASM (${androidxJar.length() / 1024}KB)...")
        val startTime = System.currentTimeMillis()

        cacheDir.mkdirs()
        val tempFile = File(cacheDir, "$CACHE_FILE_NAME.tmp")
        try {
            transformJar(androidxJar, tempFile)
            tempFile.renameTo(cached)
            hashFile.writeText(currentHash)
        } finally {
            tempFile.delete()
        }

        val elapsed = System.currentTimeMillis() - startTime
        log("Transform complete in ${elapsed}ms, output ${cached.length() / 1024}KB")
        return cached
    }

    private fun transformJar(input: File, output: File) {
        JarFile(input).use { jar ->
            JarOutputStream(output.outputStream().buffered()).use { out ->
                val entries = jar.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    jar.getInputStream(entry).use { inputStream ->
                        if (entry.name.endsWith(".class")) {
                            val transformed = transformClass(inputStream)
                            out.putNextEntry(JarEntry(entry.name))
                            out.write(transformed)
                        } else {
                            out.putNextEntry(JarEntry(entry.name))
                            inputStream.copyTo(out)
                        }
                        out.closeEntry()
                    }
                }
            }
        }
    }

    private fun transformClass(input: InputStream): ByteArray {
        val reader = ClassReader(input)
        val writer = ClassWriter(0)
        val remapper = ShadowClassRemapper(writer)
        reader.accept(remapper, 0)
        return writer.toByteArray()
    }

    private fun md5(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun log(msg: String) {
        try {
            Log.d(TAG, msg)
        } catch (_: Throwable) {
            // Unit test environment — android.util.Log not available
        }
    }
}
