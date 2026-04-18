package com.vibe.build.runtime.bootstrap

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Two-leg HTTP fetch: manifest JSON + detached signature, both under
 * the same base URL. Fetches through [MirrorSelector] so a 4xx/5xx/
 * connect-fault on the primary mirror flips to the fallback (sticky
 * for the rest of the session).
 *
 * Returned bytes are unverified; caller must pass through
 * [ManifestSignature.verify] before [ManifestParser.parse].
 */
@Singleton
open class ManifestFetcher @Inject constructor(
    private val mirrors: MirrorSelector,
) {
    data class Fetched(
        val manifestBytes: ByteArray,
        val signatureBytes: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Fetched) return false
            return manifestBytes.contentEquals(other.manifestBytes) &&
                signatureBytes.contentEquals(other.signatureBytes)
        }
        override fun hashCode(): Int =
            manifestBytes.contentHashCode() * 31 + signatureBytes.contentHashCode()
    }

    open suspend fun fetch(manifestFileName: String): Fetched = withContext(Dispatchers.IO) {
        val manifest = fetchWithFallback(manifestFileName)
        val signature = fetchWithFallback("$manifestFileName.sig")
        Fetched(manifestBytes = manifest, signatureBytes = signature)
    }

    private fun fetchWithFallback(fileName: String): ByteArray {
        val firstUrl = mirrors.currentUrlFor(fileName)
        try {
            return httpGet(firstUrl)
        } catch (e: DownloadFailedException) {
            mirrors.markPrimaryFailed()
            val retryUrl = mirrors.currentUrlFor(fileName)
            if (retryUrl == firstUrl) throw e
            return httpGet(retryUrl)
        }
    }

    private fun httpGet(url: String): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
        val status: Int = try {
            conn.responseCode
        } catch (e: IOException) {
            throw DownloadFailedException("network error for $url: ${e.message}", e)
        }
        if (status !in 200..299) {
            throw DownloadFailedException("HTTP $status from $url")
        }
        return try {
            conn.inputStream.use { it.readBytes() }
        } catch (e: IOException) {
            throw DownloadFailedException("read error for $url: ${e.message}", e)
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
    }
}
