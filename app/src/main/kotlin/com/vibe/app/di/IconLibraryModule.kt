package com.vibe.app.di

import android.content.Context
import com.vibe.app.feature.projecticon.iconlibrary.IconLibrary
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object IconLibraryModule {

    @Provides
    @Singleton
    fun provideIconLibrary(@ApplicationContext context: Context): IconLibrary =
        IconLibrary { context.assets.open("icons/lucide.json") }
}
