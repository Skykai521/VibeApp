package com.vibe.app.feature.project

import com.vibe.app.data.database.entity.Project
import com.vibe.build.engine.model.BuildResult
import java.io.File

interface ProjectWorkspace {
    val projectId: String
    val project: Project
    val rootDir: File

    suspend fun readTextFile(relativePath: String): String
    suspend fun writeTextFile(relativePath: String, content: String)
    suspend fun deleteFile(relativePath: String)
    suspend fun listFiles(): List<String>
    suspend fun buildProject(): BuildResult
    suspend fun resolveFile(relativePath: String): File
}
