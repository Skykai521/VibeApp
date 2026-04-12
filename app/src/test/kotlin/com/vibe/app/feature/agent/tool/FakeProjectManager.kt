package com.vibe.app.feature.agent.tool

import com.vibe.app.data.database.entity.Project
import com.vibe.app.feature.project.ProjectManager
import com.vibe.app.feature.project.ProjectWorkspace
import com.vibe.build.engine.model.BuildResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.io.File
import java.time.LocalDate

/**
 * Minimal ProjectManager fake that returns a workspace backed by a real dir.
 * Only openWorkspace() is used by the tools under test — other methods throw.
 */
internal class FakeProjectManager(private val workspace: File) : ProjectManager {
    override suspend fun createProject(enabledPlatforms: List<String>, name: String?): Project =
        error("not used")
    override suspend fun openWorkspace(projectId: String): ProjectWorkspace =
        FakeWorkspace(projectId, workspace)
    override fun observeProject(projectId: String): Flow<Project?> = emptyFlow()
    override suspend fun deleteProject(projectId: String) = error("not used")
    override suspend fun generateProjectId(date: LocalDate): String = error("not used")
}

internal class FakeWorkspace(
    override val projectId: String,
    override val rootDir: File,
) : ProjectWorkspace {
    override val project: Project get() = error("not used")
    override suspend fun readTextFile(relativePath: String): String = error("not used")
    override suspend fun writeTextFile(relativePath: String, content: String) = error("not used")
    override suspend fun deleteFile(relativePath: String) = error("not used")
    override suspend fun listFiles(): List<String> = error("not used")
    override suspend fun cleanBuildCache() = error("not used")
    override suspend fun buildProject(): BuildResult = error("not used")
    override suspend fun resolveFile(relativePath: String): File = File(rootDir, relativePath)
}
