package com.vibe.app.feature.uipattern

enum class PatternKind { BLOCK, SCREEN }

data class PatternSlot(
    val name: String,
    val description: String,
    val default: String,
)

data class PatternRecord(
    val id: String,
    val kind: PatternKind,
    val description: String,
    val keywords: List<String>,
    val slots: List<PatternSlot>,
    val dependencies: List<String>,
    val layoutXml: String,
    val notes: String,
)

data class PatternSearchHit(
    val id: String,
    val kind: PatternKind,
    val description: String,
    val keywords: List<String>,
    val slotNames: List<String>,
    val dependencies: List<String>,
)
