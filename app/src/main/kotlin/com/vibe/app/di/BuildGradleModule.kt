package com.vibe.app.di

import com.vibe.build.gradle.GradleBuildService
import com.vibe.build.gradle.GradleBuildServiceImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class BuildGradleModule {
    @Binds
    @Singleton
    abstract fun bindGradleBuildService(impl: GradleBuildServiceImpl): GradleBuildService
}
