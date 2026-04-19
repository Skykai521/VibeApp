package com.vibe.app.di

import android.content.Context
import android.os.Build
import com.vibe.build.runtime.BuildRuntime
import com.vibe.build.runtime.bootstrap.Abi
import com.vibe.build.runtime.bootstrap.BootstrapFileSystem
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
 * The toolchain (JDK + Gradle + Android SDK + aapt2) is bundled in
 * `app/src/main/assets/bootstrap/` by the `copyBootstrapArtifacts`
 * Gradle task. `RuntimeBootstrapper` extracts from assets on first
 * launch — no network, no manifest fetch, no signature verification,
 * no mirror selection.
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
    @Named("appCacheDir")
    fun provideAppCacheDir(@ApplicationContext context: Context): java.io.File =
        context.cacheDir
}
