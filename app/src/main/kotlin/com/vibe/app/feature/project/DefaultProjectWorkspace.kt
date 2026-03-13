package com.vibe.app.feature.project

import android.util.Log
import com.vibe.app.data.database.entity.Project
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

    override suspend fun buildProject(): BuildResult =
        projectInitializer.buildProject(projectId)

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
