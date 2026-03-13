package com.vibe.app.data.repository

import com.vibe.app.data.database.entity.Project
import com.vibe.app.data.database.entity.ProjectBuildStatus
import com.vibe.app.data.database.entity.ProjectWithChat

interface ProjectRepository {

    suspend fun fetchProjects(): List<ProjectWithChat>

    suspend fun fetchProject(projectId: String): ProjectWithChat?

    suspend fun fetchProjectByChatId(chatId: Int): Project?

    suspend fun projectExists(projectId: String): Boolean

    suspend fun saveProject(project: Project)

    suspend fun updateBuildStatus(
        projectId: String,
        status: ProjectBuildStatus,
        lastBuiltAt: Long? = null,
    )

    suspend fun renameProject(projectId: String, name: String)

    suspend fun deleteProject(projectId: String)

    suspend fun searchProjects(query: String): List<ProjectWithChat>
}
