package com.vibe.app.feature.project

import android.content.Context
import android.util.Log
import com.vibe.app.data.database.dao.ChatRoomV2Dao
import com.vibe.app.data.database.entity.ChatRoomV2
import com.vibe.app.data.database.entity.Project
import com.vibe.app.data.database.entity.ProjectBuildStatus
import com.vibe.app.data.database.entity.ProjectEngine
import com.vibe.app.data.repository.ProjectRepository
import com.vibe.app.plugin.v2.ShadowPluginRepoExtractor
import com.vibe.build.gradle.GradleProjectInitializer
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
    private val chatRoomV2Dao: ChatRoomV2Dao,
    private val gradleProjectInitializer: GradleProjectInitializer,
    private val shadowPluginRepoExtractor: ShadowPluginRepoExtractor,
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
        // New projects default to v2 GRADLE_COMPOSE (Phase 6). Same
        // package-name shape `createV2GradleComposeProject` validates —
        // keyed off projectId so it's stable + unique per device.
        val packageName = defaultPackageNameFor(projectId)
        val rootDir = v2RootDirFor(projectId)
        rootDir.mkdirs()

        // Create ChatRoomV2 first (so we have the FK for both the
        // Project row and the chat-entry the user lands in).
        val chatRoomId = chatRoomV2Dao.addChatRoom(
            ChatRoomV2(
                title = displayName,
                enabledPlatform = enabledPlatforms,
            ),
        ).toInt()

        val sdkDir = File(context.filesDir, "usr/opt/android-sdk-36.0.0")
        val gradleUserHome = File(context.filesDir, ".gradle")
        val shadowPluginRepo = shadowPluginRepoExtractor.extractIfNeeded()
        gradleProjectInitializer.initialize(
            GradleProjectInitializer.Input(
                templateName = "KotlinComposeApp",
                projectName = displayName,
                packageName = packageName,
                sdkDir = sdkDir,
                gradleUserHome = gradleUserHome,
                destinationDir = rootDir,
                shadowPluginRepo = shadowPluginRepo,
            ),
        )

        val project = Project(
            projectId = projectId,
            name = displayName,
            chatId = chatRoomId,
            workspacePath = rootDir.absolutePath,
            buildStatus = ProjectBuildStatus.READY,
            engine = ProjectEngine.GRADLE_COMPOSE,
        )
        projectRepository.saveProject(project)
        Log.d(tag, "Created v2 project $projectId (pkg=$packageName, chatId=$chatRoomId)")
        project
    }

    private fun defaultPackageNameFor(projectId: String): String =
        DEFAULT_PACKAGE_NAME(projectId)

    companion object {
        /**
         * Auto-generated applicationId for projects created from the
         * home "+" button. Single source of truth — the agent loop
         * coordinator reads this same function to fill
         * `{{PACKAGE_NAME}}` in the system prompt. If the two sites
         * drift, the agent sees template sources in one package and
         * prompt copy in another and starts "fixing" files to match.
         */
        val DEFAULT_PACKAGE_NAME: (projectId: String) -> String =
            { "com.vibe.generated.p$it" }
    }

    override suspend fun openWorkspace(projectId: String): ProjectWorkspace {
        val project = requireNotNull(projectRepository.fetchProject(projectId)?.project) {
            "Project not found: $projectId"
        }
        return DefaultProjectWorkspace(project = project)
    }

    override fun observeProject(projectId: String): Flow<Project?> = flow {
        emit(projectRepository.fetchProject(projectId)?.project)
    }

    override suspend fun deleteProject(projectId: String): Unit = withContext(Dispatchers.IO) {
        val project = projectRepository.fetchProject(projectId)?.project
        projectRepository.deleteProject(projectId)
        project?.let {
            val workspaceDir = v2RootDirFor(projectId)
            workspaceDir.deleteRecursively()
            Log.d(tag, "Deleted workspace for $projectId at ${workspaceDir.absolutePath}")
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

    /** v2 projects live at `filesDir/projects/{id}/` (Gradle multi-module root). */
    private fun v2RootDirFor(projectId: String): File =
        File(context.filesDir, "projects/$projectId")

    override suspend fun createV2GradleComposeProject(
        chatId: Int,
        projectName: String,
        packageName: String,
    ): Project = withContext(Dispatchers.IO) {
        require(packageName.matches(Regex("[a-z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+"))) {
            "invalid packageName '$packageName' — must look like com.example.foo"
        }
        // Idempotency guard. The home "+" button always pre-creates a
        // GRADLE_COMPOSE Project tied to the new chat (see `createProject`
        // above), so by the time the agent decides to call
        // `create_compose_project` from inside that chat, a project already
        // exists. Without this short-circuit each agent call would insert a
        // SECOND project row sharing the same chatId — Home queries
        // by-project, so the chat would render twice in the list with
        // identical content.
        projectRepository.fetchProjectByChatId(chatId)?.let { existing ->
            Log.d(
                tag,
                "Reusing existing v2 project ${existing.projectId} for chatId=$chatId " +
                    "(agent re-requested with name='$projectName' pkg='$packageName')",
            )
            return@withContext existing
        }
        val projectId = generateProjectId()
        val rootDir = v2RootDirFor(projectId)
        rootDir.mkdirs()

        // Lay down the KotlinComposeApp template. SDK_DIR + GRADLE_USER_HOME
        // are filled here so the project can be built without further wiring;
        // they're absolute device paths that line up with the bootstrapped
        // toolchain layout.
        val sdkDir = File(context.filesDir, "usr/opt/android-sdk-36.0.0")
        val gradleUserHome = File(context.filesDir, ".gradle")
        val shadowPluginRepo = shadowPluginRepoExtractor.extractIfNeeded()
        gradleProjectInitializer.initialize(
            GradleProjectInitializer.Input(
                templateName = "KotlinComposeApp",
                projectName = projectName,
                packageName = packageName,
                sdkDir = sdkDir,
                gradleUserHome = gradleUserHome,
                destinationDir = rootDir,
                shadowPluginRepo = shadowPluginRepo,
            ),
        )

        val project = Project(
            projectId = projectId,
            name = projectName,
            chatId = chatId,
            workspacePath = rootDir.absolutePath,
            buildStatus = ProjectBuildStatus.READY,
            engine = ProjectEngine.GRADLE_COMPOSE,
        )
        projectRepository.saveProject(project)
        Log.d(tag, "Created v2 GRADLE_COMPOSE project $projectId at ${rootDir.absolutePath}")
        project
    }
}
