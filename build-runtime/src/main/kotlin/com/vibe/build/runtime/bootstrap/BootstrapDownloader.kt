package com.vibe.build.runtime.bootstrap

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Events emitted during a single artifact download. */
sealed interface DownloadEvent {
    data class Progress(val bytesRead: Long, val totalBytes: Long) : DownloadEvent
    data class Done(val finalFile: File) : DownloadEvent
}

/**
 * Downloads a bootstrap artifact with HTTP `Range` resume and on-the-fly
 * SHA-256 verification. Supports cold download (no `.part` file) and
 * resume (existing `.part` file → sends `Range: bytes=<size>-`).
 *
 * Emits [DownloadEvent.Progress] roughly every 64KB and a final
 * [DownloadEvent.Done] when verification passes.
 *
 * Throws:
 * - [DownloadFailedException] for HTTP errors, wrong size, or network faults.
 * - [HashMismatchException] when the assembled file's SHA-256 differs from
 *   the expected hash. The `.part` file is left on disk for inspection /
 *   user-driven retry.
 */
@Singleton
class BootstrapDownloader @Inject constructor() {

    fun download(
        url: String,
        destination: File,
        expectedSha256: String,
        expectedSizeBytes: Long,
    ): Flow<DownloadEvent> = flow {
        destination.parentFile?.mkdirs()

        val alreadyDownloaded = if (destination.exists()) destination.length() else 0L
        val digest = MessageDigest.getInstance("SHA-256")

        // If .part already has content, re-hash those bytes so the running
        // digest is consistent with what's on disk before we append more.
        if (alreadyDownloaded > 0) {
            destination.inputStream().use { input ->
                val scratch = ByteArray(BUFFER_BYTES)
                while (true) {
                    val n = input.read(scratch)
                    if (n <= 0) break
                    digest.update(scratch, 0, n)
                }
            }
        }

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            if (alreadyDownloaded > 0) {
                setRequestProperty("Range", "bytes=$alreadyDownloaded-")
            }
        }

        val status: Int = try {
            conn.responseCode
        } catch (e: IOException) {
            throw DownloadFailedException("network error: ${e.message}", e)
        }

        when (status) {
            200, 206 -> Unit
            else -> throw DownloadFailedException("HTTP $status from $url")
        }

        val contentLengthHeader = conn.getHeaderFieldLong("Content-Length", -1L)
        val remainingExpected = expectedSizeBytes - alreadyDownloaded
        if (contentLengthHeader >= 0 && contentLengthHeader != remainingExpected) {
            throw DownloadFailedException(
                "Content-Length mismatch: header=$contentLengthHeader " +
                    "expected-remaining=$remainingExpected",
            )
        }

        val out = FileOutputStream(destination, /* append = */ true)
        var totalRead = alreadyDownloaded

        conn.inputStream.use { input ->
            BufferedInputStream(input, BUFFER_BYTES).use { buffered ->
                out.use { sink ->
                    val scratch = ByteArray(BUFFER_BYTES)
                    var sinceLastEmit = 0L
                    while (true) {
                        val n = buffered.read(scratch)
                        if (n <= 0) break
                        sink.write(scratch, 0, n)
                        digest.update(scratch, 0, n)
                        totalRead += n
                        sinceLastEmit += n
                        if (sinceLastEmit >= EMIT_THRESHOLD) {
                            emit(DownloadEvent.Progress(totalRead, expectedSizeBytes))
                            sinceLastEmit = 0
                        }
                    }
                }
            }
        }

        if (totalRead != expectedSizeBytes) {
            throw DownloadFailedException(
                "Short read: got $totalRead of $expectedSizeBytes bytes",
            )
        }

        val actualSha = digest.digest().joinToString(separator = "") { "%02x".format(it) }
        if (!actualSha.equals(expectedSha256, ignoreCase = true)) {
            throw HashMismatchException(expected = expectedSha256, actual = actualSha)
        }

        emit(DownloadEvent.Progress(totalRead, expectedSizeBytes))
        emit(DownloadEvent.Done(destination))
    }.flowOn(Dispatchers.IO)

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val EMIT_THRESHOLD = 256 * 1024L
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
    }
}
