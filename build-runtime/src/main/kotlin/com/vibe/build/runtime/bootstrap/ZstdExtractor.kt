package com.vibe.build.runtime.bootstrap

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decompresses a `.tar.gz` into a target directory. Preserves the owner
 * `executable` bit (mode 0100 in the tar header) by calling
 * `File.setExecutable(true, ownerOnly=true)` — the rest of the POSIX
 * permission set is ignored, since Android's app-private filesystem is
 * single-user anyway.
 *
 * Rejects any tar entry whose resolved target escapes the destination
 * directory (zip-slip defense).
 *
 * Format is tar + gzip (stdlib `java.util.zip.GZIPInputStream`). The class
 * name (ZstdExtractor) is a vestige from an earlier zstd attempt; kept
 * for now to minimise blast radius. See Phase 2a log for why zstd was
 * abandoned on Android (zstd-jni only ships Linux/glibc `.so`;
 * io.airlift:aircompressor relies on `sun.misc.Unsafe` fields absent
 * from ART).
 */
@Singleton
open class ZstdExtractor @Inject constructor() {

    open fun extract(source: File, destinationDir: File) {
        source.inputStream().use { extract(it, destinationDir) }
    }

    /**
     * Stream-variant: consumes an arbitrary [InputStream] (e.g.
     * `AssetManager.open(...)` for APK-bundled artifacts) rather than
     * reading a temp file on disk.
     */
    open fun extract(source: InputStream, destinationDir: File) {
        require(destinationDir.isDirectory) {
            "destinationDir must exist: $destinationDir"
        }
        val dstCanonical = destinationDir.canonicalFile

        try {
            GZIPInputStream(source).use { gzipIn ->
                TarArchiveInputStream(gzipIn).use { tarIn ->
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

                        if (entry.isSymbolicLink) {
                            // Tar symlink entry: size is 0; the link target is
                            // in entry.linkName (may be relative, e.g. "libz.so.1.3.2").
                            // Creating the symlink exactly preserves the archive's
                            // intent. If the file already exists (e.g. re-extract),
                            // delete it first to avoid NIO's FileAlreadyExistsException.
                            target.parentFile?.mkdirs()
                            if (target.exists() || java.nio.file.Files.isSymbolicLink(target.toPath())) {
                                java.nio.file.Files.delete(target.toPath())
                            }
                            java.nio.file.Files.createSymbolicLink(
                                target.toPath(),
                                java.nio.file.Paths.get(entry.linkName),
                            )
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
        } catch (e: ExtractionFailedException) {
            throw e
        } catch (e: IOException) {
            throw ExtractionFailedException("tar.gz extraction failed: ${e.message}", e)
        } catch (e: RuntimeException) {
            // tar parsers may surface corrupt-stream errors as RuntimeException — wrap uniformly.
            throw ExtractionFailedException("tar.gz extraction failed: ${e.message}", e)
        }
    }
}
