package com.vibe.app.feature.project.memo

import com.vibe.app.feature.project.VibeProjectDirs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

interface IntentStore {
    suspend fun exists(vibeDirs: VibeProjectDirs): Boolean
    suspend fun load(vibeDirs: VibeProjectDirs): Intent?
    suspend fun save(vibeDirs: VibeProjectDirs, intent: Intent, appName: String)
    suspend fun loadRawMarkdown(vibeDirs: VibeProjectDirs): String?
    suspend fun saveRawMarkdown(vibeDirs: VibeProjectDirs, markdown: String)
}

@Singleton
class DefaultIntentStore @Inject constructor() : IntentStore {

    override suspend fun exists(vibeDirs: VibeProjectDirs): Boolean = withContext(Dispatchers.IO) {
        vibeDirs.intentFile.exists() && vibeDirs.intentFile.length() > 0
    }

    override suspend fun load(vibeDirs: VibeProjectDirs): Intent? = withContext(Dispatchers.IO) {
        if (!vibeDirs.intentFile.exists()) null
        else IntentMarkdownCodec.decode(vibeDirs.intentFile.readText())
    }

    override suspend fun save(vibeDirs: VibeProjectDirs, intent: Intent, appName: String) =
        withContext(Dispatchers.IO) {
            vibeDirs.ensureCreated()
            vibeDirs.intentFile.writeText(IntentMarkdownCodec.encode(intent, appName))
        }

    override suspend fun loadRawMarkdown(vibeDirs: VibeProjectDirs): String? =
        withContext(Dispatchers.IO) {
            if (vibeDirs.intentFile.exists()) vibeDirs.intentFile.readText() else null
        }

    override suspend fun saveRawMarkdown(vibeDirs: VibeProjectDirs, markdown: String) =
        withContext(Dispatchers.IO) {
            vibeDirs.ensureCreated()
            vibeDirs.intentFile.writeText(markdown)
        }
}
