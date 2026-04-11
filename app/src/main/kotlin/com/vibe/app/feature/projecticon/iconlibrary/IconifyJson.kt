package com.vibe.app.feature.projecticon.iconlibrary

import kotlinx.serialization.Serializable

@Serializable
internal data class IconifyIconSet(
    val prefix: String,
    val icons: Map<String, IconifyIconEntry> = emptyMap(),
    val aliases: Map<String, IconifyAlias> = emptyMap(),
    val categories: Map<String, List<String>> = emptyMap(),
    val width: Int = 24,
    val height: Int = 24,
)

@Serializable
internal data class IconifyIconEntry(
    val body: String,
    val width: Int? = null,
    val height: Int? = null,
)

@Serializable
internal data class IconifyAlias(
    val parent: String,
)
