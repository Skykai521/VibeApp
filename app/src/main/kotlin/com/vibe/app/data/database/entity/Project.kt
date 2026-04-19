package com.vibe.app.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

enum class ProjectBuildStatus {
    INITIALIZING,  // Template being copied to workspace
    READY,         // Workspace ready, never built
    BUILDING,      // Build in progress
    SUCCESS,       // Last build succeeded
    FAILED,        // Last build failed
}

class ProjectBuildStatusConverter {
    @TypeConverter
    fun fromStatus(status: ProjectBuildStatus): String = status.name

    @TypeConverter
    fun toStatus(value: String): ProjectBuildStatus =
        runCatching { ProjectBuildStatus.valueOf(value) }.getOrDefault(ProjectBuildStatus.READY)
}

/**
 * Which build pipeline owns this project.
 *
 *  - [LEGACY] = v1 build-engine (Java + XML, single-module under
 *    `projects/{id}/app/`, AAPT2 + javac + d8 + AndroidApkBuilder).
 *    All pre-Phase-3a projects.
 *  - [GRADLE_COMPOSE] = v2 on-device Gradle (Kotlin + Compose,
 *    multi-module under `projects/{id}/`, GradleBuildService → Termux JDK
 *    → Gradle 9.3.1 daemon). Created by GradleProjectInitializer.
 *
 * Persisted as the string column `engine`. Migration defaults pre-existing
 * rows to LEGACY; the v2 init code sets GRADLE_COMPOSE explicitly.
 */
enum class ProjectEngine {
    LEGACY,
    GRADLE_COMPOSE,
}

class ProjectEngineConverter {
    @TypeConverter
    fun fromEngine(engine: ProjectEngine): String = engine.name

    @TypeConverter
    fun toEngine(value: String): ProjectEngine =
        runCatching { ProjectEngine.valueOf(value) }.getOrDefault(ProjectEngine.LEGACY)
}

@Entity(
    tableName = "projects",
    foreignKeys = [
        ForeignKey(
            entity = ChatRoomV2::class,
            parentColumns = ["chat_id"],
            childColumns = ["chat_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class Project(
    @PrimaryKey
    @ColumnInfo("project_id")
    val projectId: String,

    @ColumnInfo("name")
    val name: String,

    @ColumnInfo("chat_id")
    val chatId: Int,

    @ColumnInfo("workspace_path")
    val workspacePath: String,

    @ColumnInfo("build_status")
    val buildStatus: ProjectBuildStatus,

    @ColumnInfo("last_built_at")
    val lastBuiltAt: Long? = null,

    @ColumnInfo("created_at")
    val createdAt: Long = System.currentTimeMillis() / 1000,

    @ColumnInfo("updated_at")
    val updatedAt: Long = System.currentTimeMillis() / 1000,

    @ColumnInfo("engine", defaultValue = "LEGACY")
    val engine: ProjectEngine = ProjectEngine.LEGACY,
)

data class ProjectWithChat(
    val project: Project,
    val chat: ChatRoomV2,
    val lastMessageContent: String? = null,
)
