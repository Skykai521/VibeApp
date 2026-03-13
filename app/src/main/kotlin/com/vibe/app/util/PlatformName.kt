package com.vibe.app.util

import com.vibe.app.data.database.entity.PlatformV2

fun List<PlatformV2>.getPlatformName(uid: String): String = this.find { it.uid == uid }?.name ?: "Unknown"
