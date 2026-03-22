package com.vibe.app.di

import com.vibe.app.feature.diagnostic.ChatDiagnosticLogger
import com.vibe.app.feature.diagnostic.ChatDiagnosticLoggerImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DiagnosticModule {

    @Binds
    @Singleton
    abstract fun bindChatDiagnosticLogger(
        impl: ChatDiagnosticLoggerImpl,
    ): ChatDiagnosticLogger
}
