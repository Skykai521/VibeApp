package com.vibe.app.di

import com.vibe.build.gradle.GradleBuildService
import com.vibe.build.runtime.BuildRuntime
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the `:build-gradle` service to the application graph.
 * Phase 0: trivial pass-through. Real providers (GradleHost spawner,
 * IPC channel, build event flow) land in Phase 2.
 */
@Module
@InstallIn(SingletonComponent::class)
object BuildGradleModule {

    @Provides
    @Singleton
    fun provideGradleBuildService(
        runtime: BuildRuntime,
    ): GradleBuildService = GradleBuildService(runtime)
}
