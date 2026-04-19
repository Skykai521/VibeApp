package com.vibe.app.di

import com.vibe.build.runtime.BuildRuntime
import com.vibe.plugin.host.PluginHost
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the `:plugin-host` service to the application graph.
 * Phase 0: trivial pass-through. Real providers (PluginRunner,
 * PluginProcessSlotManager) land in Phase 5.
 */
@Module
@InstallIn(SingletonComponent::class)
object PluginHostModule {

    @Provides
    @Singleton
    fun providePluginHost(
        runtime: BuildRuntime,
    ): PluginHost = PluginHost(runtime)
}
