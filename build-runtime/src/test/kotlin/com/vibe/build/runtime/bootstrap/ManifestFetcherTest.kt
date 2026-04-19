package com.vibe.build.runtime.bootstrap

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking

class ManifestFetcherTest {

    private lateinit var primary: MockWebServer
    private lateinit var fallback: MockWebServer

    @Before
    fun setUp() {
        primary = MockWebServer().apply { start() }
        fallback = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        primary.shutdown()
        fallback.shutdown()
    }

    private fun selector() = MirrorSelector(
        primaryBase = primary.url("").toString().trimEnd('/'),
        fallbackBase = fallback.url("").toString().trimEnd('/'),
    )

    @Test
    fun `fetch returns manifest and signature bytes from primary`() = runTest {
        val manifestBody = """{"schemaVersion":1}""".toByteArray()
        val sigBody = ByteArray(64) { it.toByte() }
        primary.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Length", manifestBody.size.toString())
                .setBody(Buffer().write(manifestBody)),
        )
        primary.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Length", sigBody.size.toString())
                .setBody(Buffer().write(sigBody)),
        )

        val fetcher = ManifestFetcher(selector())
        val result = fetcher.fetch(manifestFileName = "manifest.json")

        assertArrayEquals(manifestBody, result.manifestBytes)
        assertArrayEquals(sigBody, result.signatureBytes)
    }

    @Test
    fun `fetch falls back to secondary mirror on primary manifest 500`() = runTest {
        primary.enqueue(MockResponse().setResponseCode(500))
        val manifestBody = """{"schemaVersion":1}""".toByteArray()
        val sigBody = ByteArray(64)
        fallback.enqueue(
            MockResponse().setResponseCode(200).setBody(Buffer().write(manifestBody)),
        )
        fallback.enqueue(
            MockResponse().setResponseCode(200).setBody(Buffer().write(sigBody)),
        )

        val selector = selector()
        val fetcher = ManifestFetcher(selector)
        val result = fetcher.fetch(manifestFileName = "manifest.json")

        assertArrayEquals(manifestBody, result.manifestBytes)
        assertEquals("FALLBACK", selector.currentMirrorName())
    }

    @Test
    fun `fetch throws DownloadFailedException when both mirrors fail`() {
        primary.enqueue(MockResponse().setResponseCode(500))
        fallback.enqueue(MockResponse().setResponseCode(500))

        val fetcher = ManifestFetcher(selector())
        assertThrows(DownloadFailedException::class.java) {
            runBlocking { fetcher.fetch(manifestFileName = "manifest.json") }
        }
    }

    @Test
    fun `fetch throws when signature file is missing`() {
        val manifestBody = """{"schemaVersion":1}""".toByteArray()
        primary.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(manifestBody)))
        primary.enqueue(MockResponse().setResponseCode(404))
        fallback.enqueue(MockResponse().setResponseCode(404))

        val fetcher = ManifestFetcher(selector())
        assertThrows(DownloadFailedException::class.java) {
            runBlocking { fetcher.fetch(manifestFileName = "manifest.json") }
        }
    }
}
