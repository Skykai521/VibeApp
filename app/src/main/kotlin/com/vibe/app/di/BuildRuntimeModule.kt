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
import com.vibe.build.runtime.bootstrap.MirrorSelector
import com.vibe.build.runtime.bootstrap.RuntimeBootstrapper
import com.vibe.build.runtime.bootstrap.ZstdExtractor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the `:build-runtime` service to the application graph.
 *
 * Real providers (ProcessLauncher lands in Phase 1b). In Phase 1a we
 * added the bootstrap subsystem: BootstrapFileSystem, MirrorSelector,
 * ManifestSignature (with placeholder pubkey — real pubkey injected
 * via BuildConfig in Phase 1c), ABI detection, and RuntimeBootstrapper.
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

    @Provides
    @Singleton
    fun provideManifestSignature(): ManifestSignature {
        // Phase 1a placeholder pubkey (all zeros). Replaced with real
        // pubkey via BuildConfig in Phase 1c.
        return ManifestSignature(publicKeyHex = "00".repeat(32))
    }

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
    ): RuntimeBootstrapper = RuntimeBootstrapper(
        fs = fs,
        store = store,
        parser = parser,
        signature = signature,
        mirrors = mirrors,
        downloader = downloader,
        extractor = extractor,
        abi = abi,
    )
}
