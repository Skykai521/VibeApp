package com.vibe.app.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.vibe.app.data.database.dao.ChatPlatformModelV2Dao
import com.vibe.app.data.database.dao.ChatRoomV2Dao
import com.vibe.app.data.database.dao.MessageV2Dao
import com.vibe.app.data.database.dao.PlatformV2Dao
import com.vibe.app.data.database.dao.ProjectDao
import com.vibe.app.data.database.entity.ChatPlatformModelV2
import com.vibe.app.data.database.entity.ChatRoomV2
import com.vibe.app.data.database.entity.MessageV2
import com.vibe.app.data.database.entity.PlatformV2
import com.vibe.app.data.database.entity.Project
import com.vibe.app.data.database.entity.ProjectBuildStatusConverter
import com.vibe.app.data.database.entity.ProjectEngineConverter
import com.vibe.app.data.database.entity.StringListConverter

@Database(
    entities = [ChatRoomV2::class, MessageV2::class, PlatformV2::class, ChatPlatformModelV2::class, Project::class],
    version = 4,
    exportSchema = true,
)
@TypeConverters(StringListConverter::class, ProjectBuildStatusConverter::class, ProjectEngineConverter::class)
abstract class ChatDatabaseV2 : RoomDatabase() {

    abstract fun platformDao(): PlatformV2Dao
    abstract fun chatRoomDao(): ChatRoomV2Dao
    abstract fun messageDao(): MessageV2Dao
    abstract fun chatPlatformModelDao(): ChatPlatformModelV2Dao
    abstract fun projectDao(): ProjectDao
}
