package com.vibe.app.di

import android.content.Context
import android.os.Build
import com.vibe.build.runtime.BuildRuntime
import com.vibe.build.runtime.bootstrap.Abi
import com.vibe.build.runtime.bootstrap.BootstrapDownloader
import com.vibe.build.runtime.bootstrap.BootstrapFileSystem
import com.vibe.build.runtime.bootstrap.BootstrapStateStore
import com.vibe.build.runtime.bootstrap.ManifestParser
import com.vibe.build.runtime.bootstrap.ManifestSignature
import com.vibe.build.runtime.bootstrap.ManifestFetcher
import com.vibe.build.runtime.bootstrap.MirrorSelector
import com.vibe.build.runtime.bootstrap.RuntimeBootstrapper
import com.vibe.build.runtime.bootstrap.ZstdExtractor
import com.vibe.build.runtime.process.PreloadLibLocator
import com.vibe.build.runtime.process.ProcessEnvBuilder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

/**
 * Provides the `:build-runtime` services to the application graph.
 *
 * Phase 0: BuildRuntime placeholder.
 * Phase 1a: bootstrap subsystem (downloader, extractor, state machine).
 * Phase 1b: ProcessEnvBuilder (binding for ProcessLauncher comes from
 *   BuildRuntimeProcessModule in the :build-runtime module).
 */
@Module
@InstallIn(SingletonComponent::class)
object BuildRuntimeModule {

    @Provides
    @Singleton
    fun provideBuildRuntime(): BuildRuntime = BuildRuntime()

    @Provides
    @Singleton
    fun provideBootstrapFileSystem(
        @ApplicationContext context: Context,
    ): BootstrapFileSystem = BootstrapFileSystem(filesDir = context.filesDir)

    @Provides
    @Singleton
    fun provideAbi(): Abi =
        Abi.pickPreferred(Build.SUPPORTED_ABIS)
            ?: error(
                "Unsupported ABI set: ${Build.SUPPORTED_ABIS.joinToString()}. " +
                "VibeApp supports arm64-v8a, armeabi-v7a, x86_64 only.",
            )

    @Provides
    @Singleton
    fun provideMirrorSelector(): MirrorSelector = MirrorSelector(
        primaryBase = "https://github.com/Skykai521/VibeApp/releases/download/v2.0.0",
        fallbackBase = "https://vibeapp-cdn.oss-cn-hangzhou.aliyuncs.com/releases/v2.0.0",
    )

    /**
     * Dev-cycle Ed25519 public key for bootstrap manifest verification.
     * Matches the private seed in ~/.vibeapp/dev-bootstrap-privseed.hex on
     * the developer's workstation. See docs/bootstrap/dev-keypair-setup.md.
     *
     * Production ceremony (Phase 1d) will replace this with a CI-injected value.
     */
    const val BOOTSTRAP_PUBKEY_HEX: String = "5ce0c624f59a72ee8eb6f72c25ad905a27afcd0392998f353ef86f3247725f40"

    @Provides
    @Singleton
    fun provideManifestSignature(): ManifestSignature =
        ManifestSignature(publicKeyHex = BOOTSTRAP_PUBKEY_HEX)

    @Provides
    @Singleton
    fun provideRuntimeBootstrapper(
        fs: BootstrapFileSystem,
        store: BootstrapStateStore,
        parser: ManifestParser,
        signature: ManifestSignature,
        mirrors: MirrorSelector,
        downloader: BootstrapDownloader,
        extractor: ZstdExtractor,
        abi: Abi,
        fetcher: ManifestFetcher,
    ): RuntimeBootstrapper = RuntimeBootstrapper(
        fs = fs,
        store = store,
        parser = parser,
        signature = signature,
        mirrors = mirrors,
        downloader = downloader,
        extractor = extractor,
        abi = abi,
        fetcher = fetcher,
    )

    @Provides
    @Singleton
    fun providePreloadLibLocator(
        @ApplicationContext context: Context,
    ): PreloadLibLocator =
        PreloadLibLocator(
            nativeLibraryDir = java.io.File(context.applicationInfo.nativeLibraryDir),
        )

    @Provides
    @Singleton
    fun provideProcessEnvBuilder(
        fs: BootstrapFileSystem,
        preloadLib: PreloadLibLocator,
    ): ProcessEnvBuilder = ProcessEnvBuilder(fs, preloadLib)

    @Provides
    @Singleton
    @Named("bootstrapManifestUrl")
    fun provideBootstrapManifestUrl(
        settingDataSource: com.vibe.app.data.datastore.SettingDataSource,
    ): String {
        val override = kotlinx.coroutines.runBlocking {
            settingDataSource.getDevBootstrapManifestUrl()
        }
        return override
            ?: "https://github.com/Skykai521/VibeApp/releases/download/v2.0.0/manifest.json"
    }

    @Provides
    @Singleton
    @Named("appCacheDir")
    fun provideAppCacheDir(@ApplicationContext context: Context): java.io.File =
        context.cacheDir
}
