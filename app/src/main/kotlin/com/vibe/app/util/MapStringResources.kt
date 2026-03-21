package com.vibe.app.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vibe.app.R
import com.vibe.app.data.dto.APIModel
import com.vibe.app.data.model.ApiType
import com.vibe.app.data.model.ClientType
import com.vibe.app.data.model.DynamicTheme
import com.vibe.app.data.model.ThemeMode

@Composable
fun getPlatformTitleResources(): Map<ApiType, String> = mapOf(
    ApiType.OPENAI to stringResource(R.string.openai),
    ApiType.ANTHROPIC to stringResource(R.string.anthropic),
    ApiType.GOOGLE to stringResource(R.string.google),
    ApiType.GROQ to stringResource(R.string.groq),
    ApiType.OLLAMA to stringResource(R.string.ollama)
)

@Composable
fun getPlatformDescriptionResources(): Map<ApiType, String> = mapOf(
    ApiType.OPENAI to stringResource(R.string.openai_description),
    ApiType.ANTHROPIC to stringResource(R.string.anthropic_description),
    ApiType.GOOGLE to stringResource(R.string.google_description),
    ApiType.GROQ to stringResource(R.string.groq_description),
    ApiType.OLLAMA to stringResource(R.string.ollama_description)
)

@Composable
fun getPlatformAPILabelResources(): Map<ApiType, String> = mapOf(
    ApiType.OPENAI to stringResource(R.string.openai_api_key),
    ApiType.ANTHROPIC to stringResource(R.string.anthropic_api_key),
    ApiType.GOOGLE to stringResource(R.string.google_api_key),
    ApiType.GROQ to stringResource(R.string.groq_api_key),
    ApiType.OLLAMA to stringResource(R.string.ollama_api_key)
)

@Composable
fun getPlatformHelpLinkResources(): Map<ApiType, String> = mapOf(
    ApiType.OPENAI to stringResource(R.string.openai_api_help),
    ApiType.ANTHROPIC to stringResource(R.string.anthropic_api_help),
    ApiType.GOOGLE to stringResource(R.string.google_api_help),
    ApiType.GROQ to stringResource(R.string.groq_api_help),
    ApiType.OLLAMA to stringResource(R.string.ollama_api_help)
)

@Composable
fun generateOpenAIModelList(models: LinkedHashSet<String>) = models.mapIndexed { index, model ->
    val (name, description) = when (index) {
        0 -> stringResource(R.string.gpt_4o) to stringResource(R.string.gpt_4o_description)
        1 -> stringResource(R.string.gpt_4o_mini) to stringResource(R.string.gpt_4o_mini_description)
        2 -> stringResource(R.string.gpt_4_turbo) to stringResource(R.string.gpt_4_turbo_description)
        3 -> stringResource(R.string.gpt_4) to stringResource(R.string.gpt_4_description)
        else -> "" to ""
    }
    APIModel(name, description, model)
}

@Composable
fun generateAnthropicModelList(models: LinkedHashSet<String>) = models.mapIndexed { index, model ->
    val (name, description) = when (index) {
        0 -> stringResource(R.string.claude_3_5_sonnet) to stringResource(R.string.claude_3_5_sonnet_description)
        1 -> stringResource(R.string.claude_3_opus) to stringResource(R.string.claude_3_opus_description)
        2 -> stringResource(R.string.claude_3_sonnet) to stringResource(R.string.claude_3_sonnet_description)
        3 -> stringResource(R.string.claude_3_haiku) to stringResource(R.string.claude_3_haiku_description)
        else -> "" to ""
    }
    APIModel(name, description, model)
}

@Composable
fun generateGoogleModelList(models: LinkedHashSet<String>) = models.mapIndexed { index, model ->
    val (name, description) = when (index) {
        0 -> stringResource(R.string.gemini_1_5_pro) to stringResource(R.string.gemini_1_5_pro_description)
        1 -> stringResource(R.string.gemini_1_5_flash) to stringResource(R.string.gemini_1_5_flash_description)
        2 -> stringResource(R.string.gemini_1_0_pro) to stringResource(R.string.gemini_1_0_pro_description)
        else -> "" to ""
    }
    APIModel(name, description, model)
}

@Composable
fun generateGroqModelList(models: LinkedHashSet<String>) = models.mapIndexed { index, model ->
    val (name, description) = when (index) {
        0 -> stringResource(R.string.llama_3_2_3b) to stringResource(R.string.llama_3_2_description)
        1 -> stringResource(R.string.llama_3_2_1b) to stringResource(R.string.llama_3_2_description)
        2 -> stringResource(R.string.llama_3_1_70b_versatile) to stringResource(R.string.llama_3_1_description)
        3 -> stringResource(R.string.llama_3_1_8b_instant) to stringResource(R.string.llama_3_1_description)
        4 -> stringResource(R.string.gemma_2_9b) to stringResource(R.string.gemma2_description)
        else -> "" to ""
    }
    APIModel(name, description, model)
}

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
    ClientType.GOOGLE -> "Google"
    ClientType.GROQ -> "Groq"
    ClientType.OPENROUTER -> "OpenRouter"
    ClientType.OLLAMA -> "Ollama"
    ClientType.QWEN -> "Qwen"
    ClientType.KIMI -> "Kimi"
    ClientType.CUSTOM -> "Custom"
}
