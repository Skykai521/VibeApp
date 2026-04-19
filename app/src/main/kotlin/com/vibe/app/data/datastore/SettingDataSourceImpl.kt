package com.vibe.app.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.vibe.app.data.model.DynamicTheme
import com.vibe.app.data.model.ThemeMode
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class SettingDataSourceImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : SettingDataSource {
    private val dynamicThemeKey = intPreferencesKey("dynamic_mode")
    private val themeModeKey = intPreferencesKey("theme_mode")
    private val debugModeKey = booleanPreferencesKey("debug_mode")
    private val devBootstrapManifestUrlKey = stringPreferencesKey("dev_bootstrap_manifest_url")

    override suspend fun updateDynamicTheme(theme: DynamicTheme) {
        dataStore.edit { pref ->
            pref[dynamicThemeKey] = theme.ordinal
        }
    }

    override suspend fun updateThemeMode(themeMode: ThemeMode) {
        dataStore.edit { pref ->
            pref[themeModeKey] = themeMode.ordinal
        }
    }

    override suspend fun getDynamicTheme(): DynamicTheme? {
        val mode = dataStore.data.map { pref ->
            pref[dynamicThemeKey]
        }.first() ?: return null

        return DynamicTheme.getByValue(mode)
    }

    override suspend fun getThemeMode(): ThemeMode? {
        val mode = dataStore.data.map { pref ->
            pref[themeModeKey]
        }.first() ?: return null

        return ThemeMode.getByValue(mode)
    }

    override suspend fun updateDebugMode(enabled: Boolean) {
        dataStore.edit { pref ->
            pref[debugModeKey] = enabled
        }
    }

    override suspend fun getDebugMode(): Boolean {
        return dataStore.data.map { pref ->
            pref[debugModeKey]
        }.first() ?: false
    }

    override suspend fun getDevBootstrapManifestUrl(): String? =
        dataStore.data.map { it[devBootstrapManifestUrlKey] }.first()

    override suspend fun updateDevBootstrapManifestUrl(url: String?) {
        dataStore.edit { prefs ->
            if (url.isNullOrBlank()) prefs.remove(devBootstrapManifestUrlKey)
            else prefs[devBootstrapManifestUrlKey] = url
        }
    }
}
