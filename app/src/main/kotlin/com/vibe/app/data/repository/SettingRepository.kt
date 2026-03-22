package com.vibe.app.data.repository

import com.vibe.app.data.database.entity.PlatformV2
import com.vibe.app.data.dto.ThemeSetting

interface SettingRepository {
    suspend fun fetchPlatformV2s(): List<PlatformV2>
    suspend fun fetchThemes(): ThemeSetting
    suspend fun updateThemes(themeSetting: ThemeSetting)

    // PlatformV2 CRUD operations
    suspend fun addPlatformV2(platform: PlatformV2)
    suspend fun updatePlatformV2(platform: PlatformV2)
    suspend fun deletePlatformV2(platform: PlatformV2)
    suspend fun getPlatformV2ById(id: Int): PlatformV2?
}
