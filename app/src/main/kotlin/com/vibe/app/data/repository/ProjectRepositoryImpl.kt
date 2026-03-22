package com.vibe.app.data.repository

import android.util.Log
import com.vibe.app.data.database.dao.ChatRoomV2Dao
import com.vibe.app.data.database.dao.MessageV2Dao
import com.vibe.app.data.database.dao.ProjectDao
import com.vibe.app.data.database.entity.Project
import com.vibe.app.data.database.entity.ProjectBuildStatus
import com.vibe.app.data.database.entity.ProjectWithChat
import java.io.File
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProjectRepositoryImpl @Inject constructor(
    private val projectDao: ProjectDao,
    private val chatRoomV2Dao: ChatRoomV2Dao,
    private val messageV2Dao: MessageV2Dao,
) : ProjectRepository {

    override suspend fun fetchProjects(): List<ProjectWithChat> {
        val projects = projectDao.getProjects()
        return projects.mapNotNull { project ->
            val chat = chatRoomV2Dao.getChatRoomById(project.chatId) ?: return@mapNotNull null
            val lastMessage = messageV2Dao.getLastMessageContent(project.chatId)
            ProjectWithChat(project = project, chat = chat, lastMessageContent = lastMessage)
        }
    }

    override suspend fun fetchProject(projectId: String): ProjectWithChat? {
        val project = projectDao.getProject(projectId) ?: return null
        val chat = chatRoomV2Dao.getChatRoomById(project.chatId) ?: return null
        val lastMessage = messageV2Dao.getLastMessageContent(project.chatId)
        return ProjectWithChat(project = project, chat = chat, lastMessageContent = lastMessage)
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

    override suspend fun renameProject(projectId: String, name: String) = withContext(Dispatchers.IO) {
        val normalizedName = name.trim()
        if (normalizedName.isBlank()) return@withContext

        val project = projectDao.getProject(projectId) ?: return@withContext
        val updatedAt = System.currentTimeMillis() / 1000

        projectDao.updateName(
            projectId = projectId,
            name = normalizedName,
            updatedAt = updatedAt,
        )
        updateWorkspaceAppName(project.workspacePath, normalizedName)

        val chat = chatRoomV2Dao.getChatRoomById(project.chatId) ?: return@withContext
        chatRoomV2Dao.editChatRoom(chat.copy(title = normalizedName))
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

    private fun updateWorkspaceAppName(workspacePath: String, projectName: String) {
        val stringsFile = File(workspacePath, APP_STRINGS_RELATIVE_PATH)
        if (!stringsFile.exists()) {
            Log.d(TAG, "Skip app_name update because ${stringsFile.absolutePath} does not exist")
            return
        }

        val original = stringsFile.readText(StandardCharsets.UTF_8)
        val escapedName = projectName.toXmlString()
        val updated = if (APP_NAME_REGEX.containsMatchIn(original)) {
            original.replaceFirst(APP_NAME_REGEX, "$1$escapedName$3")
        } else {
            original.replace(
                "</resources>",
                "    <string name=\"app_name\">$escapedName</string>\n</resources>",
            )
        }

        if (updated != original) {
            stringsFile.writeText(updated, StandardCharsets.UTF_8)
        }
    }

    private fun String.toXmlString(): String {
        return buildString(length) {
            for (char in this@toXmlString) {
                append(
                    when (char) {
                        '&' -> "&amp;"
                        '<' -> "&lt;"
                        '>' -> "&gt;"
                        '"' -> "&quot;"
                        '\'' -> "&apos;"
                        else -> char
                    },
                )
            }
        }
    }

    private companion object {
        const val TAG = "ProjectRepository"
        const val APP_STRINGS_RELATIVE_PATH = "src/main/res/values/strings.xml"
        val APP_NAME_REGEX = Regex(
            "(<string\\s+name=\"app_name\"[^>]*>)(.*?)(</string>)",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
    }
}
