package com.vibe.app.di

import android.content.Context
import com.chuckerteam.chucker.api.ChuckerInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import com.vibe.app.data.network.AnthropicAPI
import com.vibe.app.data.network.AnthropicAPIImpl
import com.vibe.app.data.network.GoogleAPI
import com.vibe.app.data.network.GoogleAPIImpl
import com.vibe.app.data.network.NetworkClient
import com.vibe.app.data.network.OpenAIAPI
import com.vibe.app.data.network.OpenAIAPIImpl
import com.vibe.app.feature.diagnostic.ChatDiagnosticLogger
import io.ktor.client.engine.okhttp.OkHttp
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideNetworkClient(
        @ApplicationContext context: Context
    ): NetworkClient = NetworkClient(
        OkHttp.create {
            addInterceptor(ChuckerInterceptor.Builder(context).build())
        }
    )

    @Provides
    @Singleton
    fun provideOpenAIAPI(
        networkClient: NetworkClient,
        diagnosticLogger: ChatDiagnosticLogger,
    ): OpenAIAPI = OpenAIAPIImpl(networkClient, diagnosticLogger)

    @Provides
    @Singleton
    fun provideAnthropicAPI(
        networkClient: NetworkClient,
        diagnosticLogger: ChatDiagnosticLogger,
    ): AnthropicAPI = AnthropicAPIImpl(networkClient, diagnosticLogger)

    @Provides
    @Singleton
    fun provideGoogleAPI(
        networkClient: NetworkClient,
        diagnosticLogger: ChatDiagnosticLogger,
    ): GoogleAPI = GoogleAPIImpl(networkClient, diagnosticLogger)
}
