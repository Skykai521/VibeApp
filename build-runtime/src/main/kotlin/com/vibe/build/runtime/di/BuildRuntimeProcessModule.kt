package com.vibe.build.runtime.di

import com.vibe.build.runtime.process.NativeProcessLauncher
import com.vibe.build.runtime.process.ProcessLauncher
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Module-internal Hilt bindings for the process runtime subsystem.
 * ProcessEnvBuilder is provided by the app-layer BuildRuntimeModule
 * because it needs BootstrapFileSystem which is app-provided.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class BuildRuntimeProcessModule {

    @Binds
    @Singleton
    abstract fun bindProcessLauncher(
        impl: NativeProcessLauncher,
    ): ProcessLauncher
}
