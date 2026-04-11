package com.vibe.app.di

import com.vibe.app.data.repository.ProjectRepository
import com.vibe.app.data.repository.ProjectRepositoryImpl
import com.vibe.app.feature.project.DefaultProjectManager
import com.vibe.app.feature.project.ProjectManager
import com.vibe.app.feature.project.memo.DefaultIntentStore
import com.vibe.app.feature.project.memo.IntentStore
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
}
