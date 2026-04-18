package com.vibe.build.runtime.di

import com.vibe.build.runtime.bootstrap.BootstrapStateStore
import com.vibe.build.runtime.bootstrap.DataStoreBootstrapStateStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Module-internal Hilt bindings for the bootstrap subsystem.
 *
 * Collaborators with zero-arg `@Inject constructor()` (ManifestParser,
 * BootstrapDownloader, ZstdExtractor) are resolved automatically. This
 * module binds interfaces to implementations and provides things that
 * require external configuration (pubkey, mirror URLs) — both of those
 * come from the app module (see BuildRuntimeModule).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class BuildRuntimeBootstrapModule {

    @Binds
    @Singleton
    abstract fun bindBootstrapStateStore(
        impl: DataStoreBootstrapStateStore,
    ): BootstrapStateStore
}
