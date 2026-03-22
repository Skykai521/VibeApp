package com.vibe.app.data.repository

import com.vibe.app.data.database.entity.ChatRoomV2
import com.vibe.app.data.database.entity.MessageV2
import com.vibe.app.data.database.entity.PlatformV2
import com.vibe.app.data.dto.ApiState
import com.vibe.app.feature.diagnostic.DiagnosticContext
import kotlinx.coroutines.flow.Flow

interface ChatRepository {

    suspend fun completeChat(
        userMessages: List<MessageV2>,
        assistantMessages: List<List<MessageV2>>,
        platform: PlatformV2,
        diagnosticContext: DiagnosticContext? = null,
    ): Flow<ApiState>
    suspend fun fetchChatListV2(): List<ChatRoomV2>
    suspend fun searchChatsV2(query: String): List<ChatRoomV2>
    suspend fun fetchMessagesV2(chatId: Int): List<MessageV2>
    suspend fun fetchChatPlatformModels(chatId: Int): Map<String, String>
    suspend fun saveChatPlatformModels(chatId: Int, models: Map<String, String>)
    fun generateDefaultChatTitle(messages: List<MessageV2>): String?
    suspend fun updateChatTitle(chatRoom: ChatRoomV2, title: String)
    suspend fun saveChat(chatRoom: ChatRoomV2, messages: List<MessageV2>, chatPlatformModels: Map<String, String>): ChatRoomV2
    suspend fun deleteChatsV2(chatRooms: List<ChatRoomV2>)
}
