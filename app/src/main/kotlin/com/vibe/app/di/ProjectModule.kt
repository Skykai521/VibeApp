package com.vibe.app.di

import com.vibe.app.data.repository.ProjectRepository
import com.vibe.app.data.repository.ProjectRepositoryImpl
import com.vibe.app.feature.project.DefaultProjectManager
import com.vibe.app.feature.project.ProjectManager
import com.vibe.app.feature.project.memo.DefaultIntentStore
import com.vibe.app.feature.project.memo.IntentStore
import com.vibe.app.feature.project.snapshot.Clock
import com.vibe.app.feature.project.snapshot.DefaultSnapshotManager
import com.vibe.app.feature.project.snapshot.RandomSnapshotIdGenerator
import com.vibe.app.feature.project.snapshot.SnapshotIdGenerator
import com.vibe.app.feature.project.snapshot.SnapshotIndexIo
import com.vibe.app.feature.project.snapshot.SnapshotManager
import com.vibe.app.feature.project.snapshot.SnapshotStorage
import com.vibe.app.feature.project.snapshot.SystemClock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ProjectModule {

    @Provides
    @Singleton
    fun provideProjectRepository(impl: ProjectRepositoryImpl): ProjectRepository = impl

    @Provides
    @Singleton
    fun provideProjectManager(impl: DefaultProjectManager): ProjectManager = impl

    @Provides
    @Singleton
    fun provideIntentStore(impl: DefaultIntentStore): IntentStore = impl

    @Provides
    @Singleton
    fun provideClock(): Clock = SystemClock

    @Provides
    @Singleton
    fun provideSnapshotIdGenerator(): SnapshotIdGenerator = RandomSnapshotIdGenerator

    @Provides
    @Singleton
    fun provideSnapshotStorage(): SnapshotStorage = SnapshotStorage()

    @Provides
    @Singleton
    fun provideSnapshotIndexIo(): SnapshotIndexIo = SnapshotIndexIo()

    @Provides
    @Singleton
    fun provideSnapshotManager(impl: DefaultSnapshotManager): SnapshotManager = impl
}
