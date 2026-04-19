package com.vibe.app.feature.project

import android.util.Log
import com.vibe.app.data.database.entity.Project
import java.io.File
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DefaultProjectWorkspace(
    override val project: Project,
) : ProjectWorkspace {

    private val tag = "ProjectWorkspace"

    override val projectId: String get() = project.projectId

    /**
     * Project root derived from the persisted workspacePath. v2 projects
     * live at `filesDir/projects/{id}/` (the Gradle multi-module root);
     * file tools see paths relative to this root, matching what an
     * Android Studio user would see.
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
