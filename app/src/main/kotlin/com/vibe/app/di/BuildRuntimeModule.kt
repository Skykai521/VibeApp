package com.vibe.app.di

import com.vibe.build.runtime.BuildRuntime
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the `:build-runtime` service to the application graph.
 * Phase 0: trivial pass-through. Real providers (ProcessLauncher,
 * RuntimeBootstrapper, etc.) land in Phase 1.
 */
@Module
@InstallIn(SingletonComponent::class)
object BuildRuntimeModule {

    @Provides
    @Singleton
    fun provideBuildRuntime(): BuildRuntime = BuildRuntime()
}
