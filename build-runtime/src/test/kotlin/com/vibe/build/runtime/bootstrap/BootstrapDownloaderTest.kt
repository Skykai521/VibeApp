package com.vibe.build.runtime.bootstrap

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class BootstrapDownloaderTest {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var downloader: BootstrapDownloader

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        downloader = BootstrapDownloader()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(separator = "") { "%02x".format(it) }

    @Test
    fun `download writes bytes and emits progress events`() = runTest {
        val payload = ByteArray(4096) { (it % 256).toByte() }
        val sha = sha256Hex(payload)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", payload.size.toString())
                .setBody(Buffer().write(payload)),
        )

        val outFile = File(temp.root, "part")
        val events = downloader.download(
            url = server.url("/jdk.tar.zst").toString(),
            destination = outFile,
            expectedSha256 = sha,
            expectedSizeBytes = payload.size.toLong(),
        ).toList()

        assertTrue(outFile.exists())
        assertEquals(payload.size.toLong(), outFile.length())
        assertTrue(events.any { it is DownloadEvent.Done })
        val progressEvents = events.filterIsInstance<DownloadEvent.Progress>()
        assertTrue(progressEvents.isNotEmpty())
        assertEquals(payload.size.toLong(), progressEvents.last().bytesRead)
    }

    @Test
    fun `download resumes from existing part file using Range header`() = runTest {
        val fullPayload = ByteArray(4096) { (it % 256).toByte() }
        val sha = sha256Hex(fullPayload)
        val alreadyDownloaded = 1024

        // Pre-populate .part with first 1024 bytes
        val outFile = File(temp.root, "part")
        outFile.writeBytes(fullPayload.copyOfRange(0, alreadyDownloaded))

        server.enqueue(
            MockResponse()
                .setResponseCode(206)   // Partial Content
                .setHeader(
                    "Content-Range",
                    "bytes $alreadyDownloaded-${fullPayload.size - 1}/${fullPayload.size}",
                )
                .setHeader(
                    "Content-Length",
                    (fullPayload.size - alreadyDownloaded).toString(),
                )
                .setBody(
                    Buffer().write(
                        fullPayload.copyOfRange(alreadyDownloaded, fullPayload.size),
                    ),
                ),
        )

        downloader.download(
            url = server.url("/jdk.tar.zst").toString(),
            destination = outFile,
            expectedSha256 = sha,
            expectedSizeBytes = fullPayload.size.toLong(),
        ).toList()

        // Server must have received Range: bytes=1024-
        val recorded = server.takeRequest()
        assertEquals("bytes=$alreadyDownloaded-", recorded.getHeader("Range"))
        assertEquals(fullPayload.size.toLong(), outFile.length())
    }

    @Test
    fun `hash mismatch throws and leaves part file`() = runTest {
        val payload = ByteArray(128) { 0xAA.toByte() }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", payload.size.toString())
                .setBody(Buffer().write(payload)),
        )

        val outFile = File(temp.root, "part")
        val wrongSha = "0".repeat(64)

        assertThrows(HashMismatchException::class.java) {
            runBlocking {
                downloader.download(
                    url = server.url("/jdk.tar.zst").toString(),
                    destination = outFile,
                    expectedSha256 = wrongSha,
                    expectedSizeBytes = payload.size.toLong(),
                ).toList()
            }
        }
        // .part file should still exist so a user-driven retry can resume / inspect
        assertTrue(outFile.exists())
    }

    @Test
    fun `http 500 throws DownloadFailedException`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val outFile = File(temp.root, "part")
        assertThrows(DownloadFailedException::class.java) {
            runBlocking {
                downloader.download(
                    url = server.url("/jdk.tar.zst").toString(),
                    destination = outFile,
                    expectedSha256 = "0".repeat(64),
                    expectedSizeBytes = 1,
                ).toList()
            }
        }
    }

    @Test
    fun `content-length mismatch throws DownloadFailedException`() = runTest {
        val payload = ByteArray(128) { 0xBB.toByte() }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", payload.size.toString())
                .setBody(Buffer().write(payload)),
        )

        val outFile = File(temp.root, "part")
        assertThrows(DownloadFailedException::class.java) {
            runBlocking {
                downloader.download(
                    url = server.url("/jdk.tar.zst").toString(),
                    destination = outFile,
                    expectedSha256 = sha256Hex(payload),
                    expectedSizeBytes = 9999L,   // wrong expected size
                ).toList()
            }
        }
    }
}
