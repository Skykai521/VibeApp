package com.vibe.app.feature.project

import android.content.Context
import android.util.Log
import com.vibe.app.data.database.dao.ChatRoomV2Dao
import com.vibe.app.data.database.entity.ChatRoomV2
import com.vibe.app.data.database.entity.Project
import com.vibe.app.data.database.entity.ProjectBuildStatus
import com.vibe.app.data.repository.ProjectRepository
import com.vibe.app.feature.projectinit.ProjectInitializer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Singleton
class DefaultProjectManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val projectRepository: ProjectRepository,
    private val projectInitializer: ProjectInitializer,
    private val chatRoomV2Dao: ChatRoomV2Dao,
) : ProjectManager {

    private val tag = "ProjectManager"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

    override suspend fun createProject(
        enabledPlatforms: List<String>,
        name: String?,
    ): Project = withContext(Dispatchers.IO) {
        val projectId = generateProjectId()
        val displayName = name ?: "Demo"
        val workspacePath = workspaceDirFor(projectId).absolutePath

        // Create ChatRoomV2 first (so we have the FK)
        val chatRoomId = chatRoomV2Dao.addChatRoom(
            ChatRoomV2(
                title = displayName,
                enabledPlatform = enabledPlatforms,
            ),
        ).toInt()

        val project = Project(
            projectId = projectId,
            name = displayName,
            chatId = chatRoomId,
            workspacePath = workspacePath,
            buildStatus = ProjectBuildStatus.INITIALIZING,
        )
        projectRepository.saveProject(project)
        Log.d(tag, "Created project $projectId → chatId=$chatRoomId")

        // Background: copy template files to project workspace
        scope.launch {
            try {
                projectInitializer.prepareProjectWorkspace(projectId, displayName)
                projectRepository.updateBuildStatus(projectId, ProjectBuildStatus.READY)
                Log.d(tag, "Workspace ready for project $projectId")
            } catch (e: Exception) {
                Log.e(tag, "Failed to prepare workspace for $projectId", e)
                projectRepository.updateBuildStatus(projectId, ProjectBuildStatus.FAILED)
            }
        }

        project
    }

    override suspend fun openWorkspace(projectId: String): ProjectWorkspace {
        val project = requireNotNull(projectRepository.fetchProject(projectId)?.project) {
            "Project not found: $projectId"
        }
        return DefaultProjectWorkspace(project = project, projectInitializer = projectInitializer)
    }

    override fun observeProject(projectId: String): Flow<Project?> = flow {
        emit(projectRepository.fetchProject(projectId)?.project)
    }

    override suspend fun deleteProject(projectId: String): Unit = withContext(Dispatchers.IO) {
        val project = projectRepository.fetchProject(projectId)?.project
        projectRepository.deleteProject(projectId)
        // Clean up disk workspace
        project?.let {
            val workspaceDir = workspaceDirFor(projectId).parentFile // projects/{projectId}/
            workspaceDir?.deleteRecursively()
            Log.d(tag, "Deleted workspace for $projectId at ${workspaceDir?.absolutePath}")
        }
    }

    override suspend fun generateProjectId(date: LocalDate): String = withContext(Dispatchers.IO) {
        val base = date.format(dateFormatter)
        if (!projectRepository.projectExists(base)) return@withContext base
        var suffix = 2
        while (true) {
            val candidate = "${base}$suffix"
            if (!projectRepository.projectExists(candidate)) return@withContext candidate
            suffix++
        }
        @Suppress("UNREACHABLE_CODE")
        base
    }

    private fun workspaceDirFor(projectId: String): File =
        File(context.filesDir, "projects/$projectId/app")
}
