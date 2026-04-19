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
    suspend fun getDevBootstrapManifestUrl(): String?
    suspend fun updateDevBootstrapManifestUrl(url: String?)

    /**
     * Whether the user has dismissed the one-time v2.0 upgrade notice.
     * False on a fresh install / first launch after upgrading from a
     * v1.x build; true once the user taps "got it" in the dialog.
     */
    suspend fun getV2UpgradeSeen(): Boolean
    suspend fun setV2UpgradeSeen(seen: Boolean)
}
