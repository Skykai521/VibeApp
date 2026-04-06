package com.vibe.app.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vibe.app.R
import com.vibe.app.data.model.ClientType
import com.vibe.app.data.model.DynamicTheme
import com.vibe.app.data.model.ThemeMode

@Composable
fun getDynamicThemeTitle(theme: DynamicTheme) = when (theme) {
    DynamicTheme.ON -> stringResource(R.string.on)
    DynamicTheme.OFF -> stringResource(R.string.off)
}

@Composable
fun getThemeModeTitle(theme: ThemeMode) = when (theme) {
    ThemeMode.SYSTEM -> stringResource(R.string.system_default)
    ThemeMode.DARK -> stringResource(R.string.on)
    ThemeMode.LIGHT -> stringResource(R.string.off)
}

fun getClientTypeDisplayName(clientType: ClientType): String = when (clientType) {
    ClientType.OPENAI -> "OpenAI"
    ClientType.ANTHROPIC -> "Anthropic"
    ClientType.QWEN -> "Qwen"
    ClientType.KIMI -> "Kimi"
    ClientType.MINIMAX -> "MiniMax"
    ClientType.DEEPSEEK -> "DeepSeek"
}
