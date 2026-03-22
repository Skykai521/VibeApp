package com.vibe.app.data

object ModelConstants {

    const val OPENAI_API_URL = "https://api.openai.com/"
    const val ANTHROPIC_API_URL = "https://api.anthropic.com/"
    const val GOOGLE_API_URL = "https://generativelanguage.googleapis.com"
    const val GROQ_API_URL = "https://api.groq.com/openai/"
    const val OPENROUTER_API_URL = "https://openrouter.ai/api/"
    const val OLLAMA_API_URL = "http://localhost:11434"

    const val QWEN_API_URL = "https://dashscope.aliyuncs.com/compatible-mode/"
    const val KIMI_API_URL = "https://api.moonshot.cn/"

    const val DEFAULT_PROMPT = "Your task is to answer my questions precisely."

    const val CHAT_TITLE_GENERATE_PROMPT =
        "Create a title that summarizes the chat. " +
            "The output must match the language that the user and the opponent is using, and should be less than 50 letters. " +
            "The output should only include the sentence in plain text without bullets or double asterisks. Do not use markdown syntax.\n" +
            "[Chat Content]\n"
}
