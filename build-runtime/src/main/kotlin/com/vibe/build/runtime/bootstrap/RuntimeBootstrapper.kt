package com.vibe.build.runtime.bootstrap

import kotlinx.coroutines.flow.collect
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates one full bootstrap cycle:
 *   1. Fetch manifest bytes from primary mirror
 *   2. Verify Ed25519 signature
 *   3. Parse JSON into [BootstrapManifest]
 *   4. For each component:
 *        Downloading → Verifying → Unpacking → Installing
 *   5. Ready(manifestVersion)
 *
 * Errors at any step transition state to [BootstrapState.Failed] with a
 * human-readable reason. Downloader failures retry once via mirror
 * fallback before giving up.
 *
 * Progress is communicated two ways:
 * - The `store` sees the coarse state machine (Downloading, Verifying, ...)
 * - The `onState` callback receives every state including intra-component
 *   progress, for UI that wants finer granularity.
 */
@Singleton
class RuntimeBootstrapper @Inject constructor(
    private val fs: BootstrapFileSystem,
    private val store: BootstrapStateStore,
    private val parser: ManifestParser,
    private val signature: ManifestSignature,
    private val mirrors: MirrorSelector,
    private val downloader: BootstrapDownloader,
    private val extractor: ZstdExtractor,
    private val abi: Abi,
    private val fetcher: ManifestFetcher,
    /** Phase 1a test hook — bypasses real manifest fetch + verify + parse. */
    private val parsedManifestOverride: BootstrapManifest? = null,
) {
    suspend fun bootstrap(
        manifestUrl: String,
        onState: suspend (BootstrapState) -> Unit = {},
    ) {
        try {
            fs.ensureDirectories()

            val manifest = if (parsedManifestOverride != null) {
                parsedManifestOverride
            } else {
                val fetched = fetcher.fetch(manifestFileName = manifestUrl.substringAfterLast('/'))
                if (!signature.verify(fetched.manifestBytes, fetched.signatureBytes)) {
                    throw SignatureMismatchException(
                        "manifest signature failed verification",
                    )
                }
                parser.parse(fetched.manifestBytes)
            }

            for (component in manifest.components) {
                val artifact = manifest.findArtifact(component.id, abi)
                    ?: throw DownloadFailedException(
                        "no artifact for component=${component.id} ABI=${abi.abiId}",
                    )
                bootstrapComponent(component, artifact, onState)
            }

            emit(BootstrapState.Ready(manifest.manifestVersion), onState)
        } catch (e: BootstrapException) {
            emit(BootstrapState.Failed(e.messageOrClass()), onState)
        } catch (e: Exception) {
            emit(BootstrapState.Failed(e.messageOrClass()), onState)
        }
    }

    private suspend fun bootstrapComponent(
        component: BootstrapComponent,
        artifact: ArchArtifact,
        onState: suspend (BootstrapState) -> Unit,
    ) {
        val partFile = fs.tempDownloadFile(artifact.fileName)

        // Download with single mirror fallback retry
        val maxAttempts = 2
        for (attempt in 1..maxAttempts) {
            val url = mirrors.currentUrlFor(artifact.fileName)
            try {
                downloader.download(
                    url = url,
                    destination = partFile,
                    expectedSha256 = artifact.sha256,
                    expectedSizeBytes = artifact.sizeBytes,
                ).collect { event ->
                    if (event is DownloadEvent.Progress) {
                        emit(
                            BootstrapState.Downloading(
                                componentId = component.id,
                                bytesRead = event.bytesRead,
                                totalBytes = event.totalBytes,
                            ),
                            onState,
                        )
                    }
                }
                break   // success
            } catch (e: BootstrapException) {
                if (attempt < maxAttempts) {
                    mirrors.markPrimaryFailed()
                    continue
                }
                throw e
            }
        }

        emit(BootstrapState.Verifying(component.id), onState)
        // The downloader already verified SHA-256 while streaming; the
        // Ed25519 manifest signature is verified once at manifest load
        // (Phase 1c). Verifying here is a nominal state for the UI.

        emit(BootstrapState.Unpacking(component.id), onState)
        val stagedDir = fs.stagedExtractDir(component.id)
        stagedDir.deleteRecursively()
        stagedDir.mkdirs()
        extractor.extract(partFile, stagedDir)

        emit(BootstrapState.Installing(component.id), onState)
        fs.atomicInstall(stagedDir, component.id)

        // Clean up .part file post-install
        partFile.delete()
    }

    private suspend fun emit(
        state: BootstrapState,
        onState: suspend (BootstrapState) -> Unit,
    ) {
        store.update(state)
        onState(state)
    }

    private fun Throwable.messageOrClass(): String =
        this.message?.takeIf { it.isNotBlank() } ?: this::class.simpleName ?: "unknown error"
}
