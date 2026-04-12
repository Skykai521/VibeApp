package com.vibe.app.di

import android.content.Context
import com.vibe.app.feature.uipattern.DesignGuideLoader
import com.vibe.app.feature.uipattern.PatternLibrary
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object UiPatternModule {

    @Provides
    @Singleton
    fun providePatternLibrary(@ApplicationContext context: Context): PatternLibrary {
        val assets = context.assets
        return PatternLibrary(object : PatternLibrary.AssetProvider {
            override fun openIndex() = assets.open("patterns/index.json")
            override fun openFile(relativePath: String) = assets.open("patterns/$relativePath")
        })
    }

    @Provides
    @Singleton
    fun provideDesignGuideLoader(@ApplicationContext context: Context): DesignGuideLoader {
        return DesignGuideLoader { context.assets.open("design/design-guide.md") }
    }
}
