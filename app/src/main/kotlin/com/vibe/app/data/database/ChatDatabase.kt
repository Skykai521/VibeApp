package com.vibe.app.data.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.vibe.app.data.database.dao.ChatRoomDao
import com.vibe.app.data.database.dao.MessageDao
import com.vibe.app.data.database.entity.APITypeConverter
import com.vibe.app.data.database.entity.ChatRoom
import com.vibe.app.data.database.entity.Message

@Database(
    entities = [ChatRoom::class, Message::class],
    version = 2,
    autoMigrations = [AutoMigration(from = 1, to = 2)]
)
@TypeConverters(APITypeConverter::class)
abstract class ChatDatabase : RoomDatabase() {

    abstract fun chatRoomDao(): ChatRoomDao
    abstract fun messageDao(): MessageDao
}
