package com.vibe.app.di

import com.vibe.app.feature.agent.AgentLoopCoordinator
import com.vibe.app.feature.agent.AgentModelGateway
import com.vibe.app.feature.agent.AgentToolRegistry
import com.vibe.app.feature.agent.loop.DefaultAgentLoopCoordinator
import com.vibe.app.feature.agent.loop.ProviderAgentGatewayRouter
import com.vibe.app.feature.agent.tool.DefaultAgentToolRegistry
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AgentModule {

    @Provides
    @Singleton
    fun provideAgentModelGateway(
        router: ProviderAgentGatewayRouter,
    ): AgentModelGateway = router

    @Provides
    @Singleton
    fun provideAgentToolRegistry(
        registry: DefaultAgentToolRegistry,
    ): AgentToolRegistry = registry

    @Provides
    @Singleton
    fun provideAgentLoopCoordinator(
        coordinator: DefaultAgentLoopCoordinator,
    ): AgentLoopCoordinator = coordinator
}
