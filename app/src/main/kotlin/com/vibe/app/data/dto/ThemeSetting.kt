package com.vibe.app.data.dto

import com.vibe.app.data.model.DynamicTheme
import com.vibe.app.data.model.ThemeMode

data class ThemeSetting(
    val dynamicTheme: DynamicTheme = DynamicTheme.OFF,
    val themeMode: ThemeMode = ThemeMode.SYSTEM
)
