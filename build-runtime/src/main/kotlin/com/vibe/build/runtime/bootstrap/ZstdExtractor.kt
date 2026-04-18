package com.vibe.build.runtime.bootstrap

import com.github.luben.zstd.ZstdInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decompresses a `.tar.zst` into a target directory. Preserves the owner
 * `executable` bit (mode 0100 in the tar header) by calling
 * `File.setExecutable(true, ownerOnly=true)` — the rest of the POSIX
 * permission set is ignored, since Android's app-private filesystem is
 * single-user anyway.
 *
 * Rejects any tar entry whose resolved target escapes the destination
 * directory (zip-slip defense).
 */
@Singleton
open class ZstdExtractor @Inject constructor() {

    open fun extract(source: File, destinationDir: File) {
        require(destinationDir.isDirectory) {
            "destinationDir must exist: $destinationDir"
        }
        val dstCanonical = destinationDir.canonicalFile

        try {
            source.inputStream().use { fileIn ->
                ZstdInputStream(fileIn).use { zstdIn ->
                    TarArchiveInputStream(zstdIn).use { tarIn ->
                        while (true) {
                            val entry: TarArchiveEntry = tarIn.nextEntry ?: break
                            val target = File(destinationDir, entry.name).canonicalFile
                            if (!target.path.startsWith(dstCanonical.path + File.separator) &&
                                target.path != dstCanonical.path
                            ) {
                                throw ExtractionFailedException(
                                    "tar entry escapes destination: ${entry.name}",
                                )
                            }

                            if (entry.isDirectory) {
                                target.mkdirs()
                                continue
                            }

                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { out ->
                                tarIn.copyTo(out)
                            }
                            if ((entry.mode and 0b001_000_000) != 0) {
                                target.setExecutable(true, /* ownerOnly = */ true)
                            }
                        }
                    }
                }
            }
        } catch (e: ExtractionFailedException) {
            throw e
        } catch (e: IOException) {
            throw ExtractionFailedException("tar.zst extraction failed: ${e.message}", e)
        } catch (e: RuntimeException) {
            // zstd-jni wraps errors in RuntimeException
            throw ExtractionFailedException("tar.zst extraction failed: ${e.message}", e)
        }
    }
}
