package com.vibe.app.data.datastore

import com.vibe.app.data.model.DynamicTheme
import com.vibe.app.data.model.ThemeMode

interface SettingDataSource {
    suspend fun updateDynamicTheme(theme: DynamicTheme)
    suspend fun updateThemeMode(themeMode: ThemeMode)
    suspend fun getDynamicTheme(): DynamicTheme?
    suspend fun getThemeMode(): ThemeMode?
    suspend fun updateDebugMode(enabled: Boolean)
    suspend fun getDebugMode(): Boolean
}
