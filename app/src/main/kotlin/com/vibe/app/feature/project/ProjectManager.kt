package com.vibe.app.feature.project

import com.vibe.app.data.database.entity.Project
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

interface ProjectManager {

    /**
     * Creates a new project:
     * 1. Generates a unique projectId (YYYYMMDD, with _N suffix on conflict)
     * 2. Creates a ChatRoomV2 and Project in DB (buildStatus=INITIALIZING)
     * 3. Launches background workspace copy (template → projects/{projectId})
     * 4. Returns the newly created Project immediately for navigation
     */
    suspend fun createProject(
        enabledPlatforms: List<String>,
        name: String? = null,
    ): Project

    /**
     * Returns a [ProjectWorkspace] scoped to the given projectId.
     * Throws if project does not exist.
     */
    suspend fun openWorkspace(projectId: String): ProjectWorkspace

    /**
     * Observes a project's state (build status, name changes).
     */
    fun observeProject(projectId: String): Flow<Project?>

    /**
     * Deletes a project from the database and removes its workspace from disk.
     */
    suspend fun deleteProject(projectId: String)

    /**
     * Generates a unique projectId for the given date (default = today).
     * Returns "YYYYMMDD", or "YYYYMMDD_N" on conflict.
     */
    suspend fun generateProjectId(date: LocalDate = LocalDate.now()): String
}
