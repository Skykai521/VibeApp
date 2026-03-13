package com.vibe.app.feature.projectinit

import android.util.Log
import com.vibe.build.engine.model.BuildResult
import java.io.File
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class ProjectWorkspaceService @Inject constructor(
    private val projectInitializer: ProjectInitializer,
) {

    private val tag = "ProjectWorkspace"

    suspend fun ensureProject(): ProjectInitializer.TemplateProject = projectInitializer.ensureTemplateProject()

    suspend fun readTextFile(relativePath: String): String = withContext(Dispatchers.IO) {
        val file = resolveProjectFile(relativePath)
        require(file.exists() && file.isFile) { "Project file not found: $relativePath" }
        file.readText(StandardCharsets.UTF_8)
    }

    suspend fun writeTextFile(
        relativePath: String,
        content: String,
    ) = withContext(Dispatchers.IO) {
        val file = resolveProjectFile(relativePath)
        file.parentFile?.mkdirs()
        file.writeText(content, StandardCharsets.UTF_8)
        Log.d(tag, "Wrote project file ${file.absolutePath}")
    }

    suspend fun buildProject(): BuildResult = projectInitializer.buildTemplateProject()

    suspend fun resolveProjectFile(relativePath: String): File = withContext(Dispatchers.IO) {
        require(relativePath.isNotBlank()) { "relativePath must not be blank" }
        val project = ensureProject()
        val root = project.appModuleDir.canonicalFile
        val target = File(root, relativePath).canonicalFile
        require(target.path.startsWith(root.path + File.separator) || target == root) {
            "Path escapes project root: $relativePath"
        }
        target
    }
}
