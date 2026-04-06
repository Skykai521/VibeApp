package com.vibe.app.data

object ModelConstants {

    const val OPENAI_API_URL = "https://api.openai.com/"
    const val ANTHROPIC_API_URL = "https://api.anthropic.com/"

    const val QWEN_API_URL = "https://dashscope.aliyuncs.com/compatible-mode/"
    const val KIMI_API_URL = "https://api.moonshot.cn/"
    const val MINIMAX_API_URL = "https://api.minimaxi.com/anthropic/"

    const val CHAT_TITLE_GENERATE_PROMPT =
        "Create a title that summarizes the chat. " +
            "The output must match the language that the user and the opponent is using, and should be less than 50 letters. " +
            "The output should only include the sentence in plain text without bullets or double asterisks. Do not use markdown syntax.\n" +
            "[Chat Content]\n"
}
