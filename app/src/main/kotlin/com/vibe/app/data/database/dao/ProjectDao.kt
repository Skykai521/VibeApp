package com.vibe.app.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.vibe.app.data.database.entity.Project
import com.vibe.app.data.database.entity.ProjectBuildStatus

@Dao
interface ProjectDao {

    @Query("SELECT * FROM projects ORDER BY created_at DESC")
    suspend fun getProjects(): List<Project>

    @Query("SELECT * FROM projects WHERE project_id = :projectId")
    suspend fun getProject(projectId: String): Project?

    /**
     * One chat can now host both a v1 LEGACY init project and a v2
     * GRADLE_COMPOSE project (added later by `create_compose_project`).
     * Pick the v2 one if it exists, otherwise the most recent — that
     * matches the user's intent: once they've upgraded the chat to a
     * Compose flow, agent + UI should treat the v2 project as active.
     *
     * Sort order:
     *   1. engine = GRADLE_COMPOSE first (alphabetically G < L)
     *   2. then created_at DESC (newest within the same engine class)
     */
    @Query(
        "SELECT * FROM projects WHERE chat_id = :chatId ORDER BY engine ASC, created_at DESC LIMIT 1"
    )
    suspend fun getProjectByChatId(chatId: Int): Project?

    @Query("SELECT EXISTS(SELECT 1 FROM projects WHERE project_id = :projectId)")
    suspend fun projectExists(projectId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProject(project: Project)

    @Update
    suspend fun updateProject(project: Project)

    @Query("""
        UPDATE projects
        SET build_status = :status, last_built_at = :lastBuiltAt, updated_at = :updatedAt
        WHERE project_id = :projectId
    """)
    suspend fun updateBuildStatus(
        projectId: String,
        status: ProjectBuildStatus,
        lastBuiltAt: Long?,
        updatedAt: Long,
    )

    @Query("UPDATE projects SET name = :name, updated_at = :updatedAt WHERE project_id = :projectId")
    suspend fun updateName(projectId: String, name: String, updatedAt: Long)

    @Query("DELETE FROM projects WHERE project_id = :projectId")
    suspend fun deleteProject(projectId: String)

    @Query("SELECT * FROM projects WHERE name LIKE '%' || :query || '%' ORDER BY created_at DESC")
    suspend fun searchProjects(query: String): List<Project>
}
