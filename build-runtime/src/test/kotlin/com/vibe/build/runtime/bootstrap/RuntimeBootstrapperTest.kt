package com.vibe.build.runtime.bootstrap

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RuntimeBootstrapperTest {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    private val validManifest = BootstrapManifest(
        schemaVersion = 1,
        manifestVersion = "v2.0.0-test",
        components = listOf(
            BootstrapComponent(
                id = "jdk-17.0.13",
                version = "17.0.13",
                artifacts = mapOf(
                    "arm64-v8a" to ArchArtifact(
                        fileName = "jdk.tar.zst",
                        sizeBytes = 4L,
                        sha256 = "aa".repeat(32),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `successful cycle transitions through all states and reaches Ready`() = runTest {
        val store = InMemoryBootstrapStateStore()
        val fs = BootstrapFileSystem(filesDir = temp.root)
        val collaborators = FakeCollaborators(
            manifestBytes = ByteArray(0),   // not used in this test (parse is faked)
            manifestSignature = ByteArray(0),
            abi = Abi.ARM64,
            successfulFakeDownload = true,
            successfulFakeVerify = true,
            successfulFakeExtract = true,
        )

        val bootstrapper = RuntimeBootstrapper(
            fs = fs,
            store = store,
            parser = collaborators.parser,
            signature = collaborators.signature,
            mirrors = collaborators.mirrors,
            downloader = collaborators.downloader,
            extractor = collaborators.extractor,
            abi = Abi.ARM64,
            parsedManifestOverride = validManifest,   // skip real parse
        )

        val seenStates = mutableListOf<BootstrapState>()
        bootstrapper.bootstrap(manifestUrl = "https://example.test/manifest.json") { s ->
            seenStates += s
        }

        val names = seenStates.map { it::class.simpleName }
        // Expected (ignoring progress spam): Downloading → Verifying → Unpacking → Installing → Ready
        assertTrue("Downloading" in names)
        assertTrue("Verifying" in names)
        assertTrue("Unpacking" in names)
        assertTrue("Installing" in names)
        val terminal = seenStates.last()
        assertTrue("terminal was $terminal", terminal is BootstrapState.Ready)
        assertEquals("v2.0.0-test", (terminal as BootstrapState.Ready).manifestVersion)

        // Component should be installed in usr/opt/jdk-17.0.13/
        assertTrue(fs.componentInstallDir("jdk-17.0.13").isDirectory)
    }

    @Test
    fun `signature mismatch transitions to Failed`() = runTest {
        val store = InMemoryBootstrapStateStore()
        val fs = BootstrapFileSystem(filesDir = temp.root)
        val collaborators = FakeCollaborators(
            manifestBytes = "fake".toByteArray(),
            manifestSignature = ByteArray(64),
            abi = Abi.ARM64,
            successfulFakeDownload = true,
            successfulFakeVerify = false,   // signature returns false
            successfulFakeExtract = true,
        )

        val bootstrapper = RuntimeBootstrapper(
            fs = fs,
            store = store,
            parser = collaborators.parser,
            signature = collaborators.signature,
            mirrors = collaborators.mirrors,
            downloader = collaborators.downloader,
            extractor = collaborators.extractor,
            abi = Abi.ARM64,
            parsedManifestOverride = validManifest,
        )

        val seenStates = mutableListOf<BootstrapState>()
        bootstrapper.bootstrap(manifestUrl = "https://example.test/manifest.json") { seenStates += it }

        val terminal = seenStates.last()
        assertTrue("terminal was $terminal", terminal is BootstrapState.Failed)
        val msg = (terminal as BootstrapState.Failed).reason
        assertTrue(msg.contains("signature", ignoreCase = true))
    }

    @Test
    fun `download failure switches mirrors then retries once`() = runTest {
        val store = InMemoryBootstrapStateStore()
        val fs = BootstrapFileSystem(filesDir = temp.root)
        val collaborators = FakeCollaborators(
            manifestBytes = ByteArray(0),
            manifestSignature = ByteArray(0),
            abi = Abi.ARM64,
            successfulFakeDownload = false,   // first attempt fails
            failPrimaryOnlyOnce = true,       // second attempt succeeds
            successfulFakeVerify = true,
            successfulFakeExtract = true,
        )

        val bootstrapper = RuntimeBootstrapper(
            fs = fs,
            store = store,
            parser = collaborators.parser,
            signature = collaborators.signature,
            mirrors = collaborators.mirrors,
            downloader = collaborators.downloader,
            extractor = collaborators.extractor,
            abi = Abi.ARM64,
            parsedManifestOverride = validManifest,
        )

        val seenStates = mutableListOf<BootstrapState>()
        bootstrapper.bootstrap(manifestUrl = "https://example.test/manifest.json") { seenStates += it }

        val terminal = seenStates.last()
        assertTrue("terminal was $terminal", terminal is BootstrapState.Ready)
        assertEquals(2, collaborators.downloader.attempts)   // primary failed, fallback succeeded
        assertEquals("FALLBACK", collaborators.mirrors.currentMirrorName())
    }

    @Test
    fun `unsupported abi produces Failed`() = runTest {
        val store = InMemoryBootstrapStateStore()
        val fs = BootstrapFileSystem(filesDir = temp.root)
        // Manifest only has arm64-v8a, we ask for... well, we need to
        // construct a manifest with NO matching ABI.
        val manifestNoMatch = validManifest.copy(
            components = listOf(
                validManifest.components[0].copy(
                    artifacts = mapOf(
                        "x86_64" to ArchArtifact("x86.tar.zst", 4L, "bb".repeat(32)),
                    ),
                ),
            ),
        )
        val collaborators = FakeCollaborators(
            manifestBytes = ByteArray(0),
            manifestSignature = ByteArray(0),
            abi = Abi.ARM64,
            successfulFakeDownload = true,
            successfulFakeVerify = true,
            successfulFakeExtract = true,
        )

        val bootstrapper = RuntimeBootstrapper(
            fs = fs,
            store = store,
            parser = collaborators.parser,
            signature = collaborators.signature,
            mirrors = collaborators.mirrors,
            downloader = collaborators.downloader,
            extractor = collaborators.extractor,
            abi = Abi.ARM64,
            parsedManifestOverride = manifestNoMatch,
        )

        val seenStates = mutableListOf<BootstrapState>()
        bootstrapper.bootstrap(manifestUrl = "https://example.test/manifest.json") { seenStates += it }

        val terminal = seenStates.last()
        assertTrue(terminal is BootstrapState.Failed)
        assertTrue((terminal as BootstrapState.Failed).reason.contains("ABI"))
    }
}

/**
 * Helper that bundles stubbed collaborators so the orchestrator test
 * doesn't need mocking frameworks.
 */
private class FakeCollaborators(
    val manifestBytes: ByteArray,
    val manifestSignature: ByteArray,
    val abi: Abi,
    val successfulFakeDownload: Boolean,
    val successfulFakeVerify: Boolean,
    val successfulFakeExtract: Boolean,
    val failPrimaryOnlyOnce: Boolean = false,
) {
    val parser = object : ManifestParser() {}

    val signature = object : ManifestSignature("00".repeat(32)) {
        override fun verify(manifestBytes: ByteArray, signature: ByteArray) = successfulFakeVerify
    }

    val mirrors = MirrorSelector(
        primaryBase = "https://primary.test",
        fallbackBase = "https://fallback.test",
    )

    // Named class so `attempts` is accessible via the typed field below.
    inner class FakeDownloader : BootstrapDownloader() {
        var attempts = 0
        override fun download(
            url: String,
            destination: File,
            expectedSha256: String,
            expectedSizeBytes: Long,
        ) = kotlinx.coroutines.flow.flow<DownloadEvent> {
            attempts++
            // Fail if: unconditionally failing, OR only-once-fail on first attempt.
            val fail = when {
                failPrimaryOnlyOnce -> attempts == 1   // only first attempt fails
                else -> !successfulFakeDownload         // all attempts match flag
            }
            if (fail) {
                throw DownloadFailedException("fake failure attempt=$attempts")
            }
            destination.parentFile?.mkdirs()
            destination.writeBytes(ByteArray(4))
            emit(DownloadEvent.Progress(4, expectedSizeBytes))
            emit(DownloadEvent.Done(destination))
        }
    }

    val downloader = FakeDownloader()

    val extractor = object : ZstdExtractor() {
        override fun extract(source: File, destinationDir: File) {
            if (!successfulFakeExtract) {
                throw ExtractionFailedException("fake extraction failure")
            }
            destinationDir.mkdirs()
            File(destinationDir, "bin").mkdirs()
            File(destinationDir, "bin/java").writeText("#!/bin/sh\n")
        }
    }
}
