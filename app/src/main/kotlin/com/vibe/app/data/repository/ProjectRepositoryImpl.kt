package com.vibe.app.data.repository

import com.vibe.app.data.database.dao.ChatRoomV2Dao
import com.vibe.app.data.database.dao.ProjectDao
import com.vibe.app.data.database.entity.Project
import com.vibe.app.data.database.entity.ProjectBuildStatus
import com.vibe.app.data.database.entity.ProjectWithChat
import javax.inject.Inject

class ProjectRepositoryImpl @Inject constructor(
    private val projectDao: ProjectDao,
    private val chatRoomV2Dao: ChatRoomV2Dao,
) : ProjectRepository {

    override suspend fun fetchProjects(): List<ProjectWithChat> {
        val projects = projectDao.getProjects()
        return projects.mapNotNull { project ->
            val chat = chatRoomV2Dao.getChatRoomById(project.chatId) ?: return@mapNotNull null
            ProjectWithChat(project = project, chat = chat)
        }
    }

    override suspend fun fetchProject(projectId: String): ProjectWithChat? {
        val project = projectDao.getProject(projectId) ?: return null
        val chat = chatRoomV2Dao.getChatRoomById(project.chatId) ?: return null
        return ProjectWithChat(project = project, chat = chat)
    }

    override suspend fun fetchProjectByChatId(chatId: Int): Project? {
        return projectDao.getProjectByChatId(chatId)
    }

    override suspend fun projectExists(projectId: String): Boolean {
        return projectDao.projectExists(projectId)
    }

    override suspend fun saveProject(project: Project) {
        projectDao.insertProject(project)
    }

    override suspend fun updateBuildStatus(
        projectId: String,
        status: ProjectBuildStatus,
        lastBuiltAt: Long?,
    ) {
        projectDao.updateBuildStatus(
            projectId = projectId,
            status = status,
            lastBuiltAt = lastBuiltAt,
            updatedAt = System.currentTimeMillis() / 1000,
        )
    }

    override suspend fun renameProject(projectId: String, name: String) {
        projectDao.updateName(
            projectId = projectId,
            name = name,
            updatedAt = System.currentTimeMillis() / 1000,
        )
        // Keep ChatRoomV2 title in sync
        val project = projectDao.getProject(projectId) ?: return
        val chat = chatRoomV2Dao.getChatRoomById(project.chatId) ?: return
        chatRoomV2Dao.editChatRoom(chat.copy(title = name))
    }

    override suspend fun deleteProject(projectId: String) {
        // The FK is project.chatId → chats_v2.chat_id with CASCADE delete.
        // That means deleting the chat cascades to delete the project.
        // So: delete the project row directly (does NOT cascade to chat),
        // then delete the chat (which also deletes messages via existing cascade).
        val project = projectDao.getProject(projectId) ?: return
        projectDao.deleteProject(projectId)
        chatRoomV2Dao.deleteChatRooms(chatRoomV2Dao.getChatRoomById(project.chatId) ?: return)
    }

    override suspend fun searchProjects(query: String): List<ProjectWithChat> {
        val projects = projectDao.searchProjects(query)
        return projects.mapNotNull { project ->
            val chat = chatRoomV2Dao.getChatRoomById(project.chatId) ?: return@mapNotNull null
            ProjectWithChat(project = project, chat = chat)
        }
    }
}
