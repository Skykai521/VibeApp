package com.vibe.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import com.vibe.app.data.database.dao.ChatPlatformModelV2Dao
import com.vibe.app.data.database.dao.ChatRoomV2Dao
import com.vibe.app.data.database.dao.MessageV2Dao
import com.vibe.app.data.repository.ChatRepository
import com.vibe.app.data.repository.ChatRepositoryImpl
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ChatRepositoryModule {

    @Provides
    @Singleton
    fun provideChatRepository(
        chatRoomV2Dao: ChatRoomV2Dao,
        messageV2Dao: MessageV2Dao,
        chatPlatformModelV2Dao: ChatPlatformModelV2Dao,
    ): ChatRepository = ChatRepositoryImpl(
        chatRoomV2Dao,
        messageV2Dao,
        chatPlatformModelV2Dao,
    )
}
