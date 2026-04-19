package com.vibe.build.runtime.bootstrap

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates one full toolchain install:
 *   1. Read bundled `assets/bootstrap/manifest.json`
 *   2. For each component in the manifest, stream its tar.gz artifact
 *      from assets and extract into `filesDir/usr/opt/<componentId>/`
 *   3. Emit `Ready(manifestVersion)` through the state store
 *
 * Tarballs are produced by `scripts/bootstrap/build-*.sh` and placed
 * into `app/src/main/assets/bootstrap/` by the `copyBootstrapArtifacts`
 * Gradle task — see app/build.gradle.kts.
 *
 * No HTTP, no signature check, no mirror fallback: the APK itself is
 * the source of truth (signed by Android's APK signing scheme). The
 * `parsedManifestOverride` hook remains for unit-test injection.
 */
@Singleton
class RuntimeBootstrapper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fs: BootstrapFileSystem,
    private val store: BootstrapStateStore,
    private val parser: ManifestParser,
    private val extractor: ZstdExtractor,
    private val abi: Abi,
) {
    suspend fun bootstrap(
        onState: suspend (BootstrapState) -> Unit = {},
    ) {
        try {
            fs.ensureDirectories()

            val manifest = run {
                val bytes = context.assets.open(MANIFEST_ASSET).use { it.readBytes() }
                parser.parse(bytes)
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
        // Show "Downloading" on the UI even though we're streaming from
        // assets — it's still the longest single step per component
        // (decompress + write to disk). sizeBytes is known from the
        // manifest; we emit one Progress event at start + end.
        emit(
            BootstrapState.Downloading(
                componentId = component.id,
                bytesRead = 0L,
                totalBytes = artifact.sizeBytes,
            ),
            onState,
        )

        emit(BootstrapState.Unpacking(component.id), onState)
        val stagedDir = fs.stagedExtractDir(component.id)
        stagedDir.deleteRecursively()
        stagedDir.mkdirs()

        // AGP's `compressAssets` task auto-decompresses `.gz` assets
        // during APK build — files go in as `.tar.gz` but come out as
        // bare `.tar`. Try the manifest-listed name first, then the
        // gz-stripped fallback. The extractor transparently handles
        // both gzipped and raw tar streams.
        val assetStream = openAssetWithFallback(artifact.fileName)
        assetStream.use { input ->
            extractor.extract(input, stagedDir)
        }

        emit(BootstrapState.Installing(component.id), onState)
        fs.atomicInstall(stagedDir, component.id)

        emit(
            BootstrapState.Downloading(
                componentId = component.id,
                bytesRead = artifact.sizeBytes,
                totalBytes = artifact.sizeBytes,
            ),
            onState,
        )
    }

    private fun openAssetWithFallback(fileName: String): java.io.InputStream {
        val assets = context.assets
        return try {
            assets.open("$ASSETS_ROOT/$fileName")
        } catch (e: java.io.FileNotFoundException) {
            val alt = fileName.removeSuffix(".gz")
            if (alt != fileName) assets.open("$ASSETS_ROOT/$alt") else throw e
        }
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

    companion object {
        private const val ASSETS_ROOT = "bootstrap"
        private const val MANIFEST_ASSET = "$ASSETS_ROOT/manifest.json"
    }
}
