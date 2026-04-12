package com.vibe.app.feature.project.memo

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Outline(
    val generatedAtEpochMs: Long,
    val appName: String,
    val packageName: String,
    val activities: List<OutlineActivity> = emptyList(),
    val fileCount: Int = 0,
    val permissions: List<String> = emptyList(),
    val stringKeys: List<String> = emptyList(),
    val recentTurns: List<OutlineRecentTurn> = emptyList(),
)

@Serializable
data class OutlineActivity(
    val name: String,
    val layout: String? = null,
    val purpose: String? = null,
)

@Serializable
data class OutlineRecentTurn(
    val turnIndex: Int,
    val userPrompt: String,
    val changedFiles: Int,
    val buildOk: Boolean,
)

object OutlineJson {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(outline: Outline): String = json.encodeToString(Outline.serializer(), outline)
    fun decode(text: String): Outline = json.decodeFromString(Outline.serializer(), text)
}

/**
 * LLM-maintained intent layer. Each list capped at 5 items, each item ≤ 60 chars,
 * purpose ≤ 80 chars. Limits are enforced at write time (not parse time).
 * Used by a later task — defined here so MemoModels.kt isn't churned twice.
 */
data class Intent(
    val purpose: String,
    val keyDecisions: List<String>,
    val knownLimits: List<String>,
) {
    companion object {
        const val PURPOSE_MAX = 80
        const val LINE_MAX = 60
        const val LIST_MAX = 5
    }
}

data class ProjectMemo(
    val outline: Outline?,
    val intent: Intent?,
)
