package com.vibe.app.feature.project

import android.util.Log
import com.vibe.app.data.database.entity.Project
import com.vibe.app.feature.diagnostic.BuildTriggerSource
import com.vibe.app.feature.projectinit.ProjectInitializer
import com.vibe.build.engine.model.BuildResult
import java.io.File
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DefaultProjectWorkspace(
    override val project: Project,
    private val projectInitializer: ProjectInitializer,
) : ProjectWorkspace {

    private val tag = "ProjectWorkspace"

    override val projectId: String get() = project.projectId

    /**
     * Project file root, derived directly from the persisted workspacePath.
     * The semantic differs by engine:
     *  - LEGACY (v1):         workspacePath = `filesDir/projects/{id}/app`
     *                         (single-module project, root = the `app/` dir)
     *  - GRADLE_COMPOSE (v2): workspacePath = `filesDir/projects/{id}`
     *                         (multi-module Gradle project, root = repo root)
     *
     * read/write/list/grep tools see paths relative to this root, which
     * intentionally matches what an Android Studio user would see for that
     * project layout. Engine-specific tools (assemble_debug_v2,
     * install_apk_v2) check `project.engine` before acting.
     */
    override val rootDir: File get() = File(project.workspacePath)

    override suspend fun readTextFile(relativePath: String): String = withContext(Dispatchers.IO) {
        val file = resolveFile(relativePath)
        require(file.exists() && file.isFile) { "Project file not found: $relativePath" }
        file.readText(StandardCharsets.UTF_8)
    }

    override suspend fun writeTextFile(relativePath: String, content: String): Unit = withContext(Dispatchers.IO) {
        val file = resolveFile(relativePath)
        file.parentFile?.mkdirs()
        file.writeText(content, StandardCharsets.UTF_8)
        Log.d(tag, "Wrote $relativePath in project $projectId")
    }

    override suspend fun deleteFile(relativePath: String): Unit = withContext(Dispatchers.IO) {
        val file = resolveFile(relativePath)
        require(file.exists() && file.isFile) { "File not found: $relativePath" }
        file.delete()
        Log.d(tag, "Deleted $relativePath in project $projectId")
    }

    override suspend fun listFiles(): List<String> = withContext(Dispatchers.IO) {
        rootDir.walkTopDown()
            .onEnter { it.name != "build" }
            .filter { it.isFile }
            .map { it.toRelativeString(rootDir) }
            .sorted()
            .toList()
    }

    override suspend fun cleanBuildCache(): Unit = withContext(Dispatchers.IO) {
        val buildDir = File(rootDir, "build")
        if (buildDir.exists()) {
            buildDir.deleteRecursively()
            Log.d(tag, "Cleaned build cache for project $projectId")
        }
    }

    override suspend fun buildProject(): BuildResult =
        projectInitializer.buildProject(
            projectId = projectId,
            triggerSource = BuildTriggerSource.AGENT_TOOL,
        )

    override suspend fun resolveFile(relativePath: String): File = withContext(Dispatchers.IO) {
        require(relativePath.isNotBlank()) { "relativePath must not be blank" }
        val root = rootDir.canonicalFile
        val target = File(root, relativePath).canonicalFile
        require(target.path.startsWith(root.path + File.separator) || target == root) {
            "Path escapes project root: $relativePath"
        }
        target
    }
}
