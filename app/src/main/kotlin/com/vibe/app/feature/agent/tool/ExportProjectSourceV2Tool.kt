package com.vibe.app.feature.agent.tool

import android.content.Context
import androidx.core.content.FileProvider
import com.vibe.app.data.database.entity.ProjectEngine
import com.vibe.app.data.repository.ProjectRepository
import com.vibe.app.feature.agent.AgentTool
import com.vibe.app.feature.agent.AgentToolCall
import com.vibe.app.feature.agent.AgentToolContext
import com.vibe.app.feature.agent.AgentToolDefinition
import com.vibe.app.feature.agent.AgentToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Agent tool: zip up the current v2 project's source tree (skipping
 * generated `build/` and `.gradle/` directories) and add a top-level
 * README explaining how to build it on a desktop with Android Studio.
 *
 * The zip lands under VibeApp's externally-shared `cache/exports/`
 * dir and is exposed via [FileProvider] so the agent can pass the
 * URI back to the user (e.g. via a "share zip" UI hook later — for
 * now, the path string is just returned in the tool result).
 */
@Singleton
class ExportProjectSourceV2Tool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val projectRepository: ProjectRepository,
) : AgentTool {

    override val definition = AgentToolDefinition(
        name = "export_project_source_v2",
        description = "Zip the current v2 project's source tree and emit a sharable file path. " +
            "Excludes Gradle's generated build output (`build/`, `.gradle/`). The zip includes " +
            "a top-level README.md with instructions for opening the project in Android Studio. " +
            "Use this when the user wants to take their project off-device.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {})
        },
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val project = projectRepository.fetchProject(context.projectId)?.project
            ?: return call.errorResult("project not found: ${context.projectId}")
        if (project.engine != ProjectEngine.GRADLE_COMPOSE) {
            return call.errorResult(
                "current project is engine=${project.engine}; export_project_source_v2 only handles v2 projects.",
            )
        }
        val rootDir = File(project.workspacePath)
        if (!rootDir.isDirectory) {
            return call.errorResult("workspace_path missing on disk: $rootDir")
        }
        val exportsDir = File(this.context.cacheDir, "exports").also { it.mkdirs() }
        val zipFile = File(
            exportsDir,
            "${sanitize(project.name)}-${project.projectId}.zip",
        )

        return try {
            zipProjectTree(rootDir, zipFile, project.name, project.projectId)
            val authority = "${this.context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(this.context, authority, zipFile)
            call.result(
                buildJsonObject {
                    put("status", JsonPrimitive("EXPORTED"))
                    put("zipPath", JsonPrimitive(zipFile.absolutePath))
                    put("zipSizeBytes", JsonPrimitive(zipFile.length()))
                    put("contentUri", JsonPrimitive(uri.toString()))
                    put(
                        "hint",
                        JsonPrimitive(
                            "Zip is at zipPath. Tell the user the file path or surface the contentUri " +
                                "via a share sheet UI hook.",
                        ),
                    )
                },
            )
        } catch (t: Throwable) {
            zipFile.delete()
            call.errorResult("export failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun zipProjectTree(
        rootDir: File,
        zipFile: File,
        projectName: String,
        projectId: String,
    ) {
        val readme = """
# $projectName

Exported from VibeApp (project id `$projectId`) on
${java.text.SimpleDateFormat("yyyy-MM-dd").format(java.util.Date())}.

## Build on a desktop

1. Open Android Studio (recent version, AGP 9.x or newer).
2. Choose `Open` and select this directory.
3. Let Gradle sync, then `./gradlew :app:assembleDebug`.

The included `gradle.properties` references on-device-only paths
(`{{SDK_DIR}}` style template variables that ProjectStager filled at
generation time). For a desktop build, point your local `local.properties`
at your desktop Android SDK dir instead.
""".trimIndent()

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry("README.md"))
            zos.write(readme.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            rootDir.walkTopDown()
                .onEnter { dir -> dir.name !in EXCLUDED_DIR_NAMES }
                .filter { it.isFile }
                .forEach { file ->
                    val rel = file.relativeTo(rootDir).path.replace(File.separatorChar, '/')
                    zos.putNextEntry(ZipEntry(rel))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
        }
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "-").trim('-').ifBlank { "project" }

    companion object {
        private val EXCLUDED_DIR_NAMES = setOf("build", ".gradle", ".idea")
    }
}
