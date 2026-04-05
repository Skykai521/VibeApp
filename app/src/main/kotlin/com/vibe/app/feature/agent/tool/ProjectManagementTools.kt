package com.vibe.app.feature.agent.tool

import com.vibe.app.data.repository.ProjectRepository
import com.vibe.app.feature.agent.AgentTool
import com.vibe.app.feature.agent.AgentToolCall
import com.vibe.app.feature.agent.AgentToolContext
import com.vibe.app.feature.agent.AgentToolDefinition
import com.vibe.app.feature.agent.AgentToolResult
import com.vibe.app.feature.project.ProjectManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private const val ICON_BACKGROUND_PATH = "src/main/res/drawable/ic_launcher_background.xml"
private const val ICON_FOREGROUND_PATH = "src/main/res/drawable/ic_launcher_foreground.xml"

@Singleton
class RenameProjectTool @Inject constructor(
    private val projectRepository: ProjectRepository,
) : AgentTool {

    override val definition = AgentToolDefinition(
        name = "rename_project",
        description = "Rename the current project.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put(
                        "name",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("A short descriptive project name, max 30 characters."))
                            put("maxLength", JsonPrimitive(30))
                        },
                    )
                },
            )
            put("required", requiredFields("name"))
        },
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val name = call.arguments.requireString("name").take(30)
        if (context.projectId.isBlank()) {
            return call.errorResult("No project context available")
        }
        projectRepository.renameProject(context.projectId, name)
        return call.okResult()
    }
}

@Singleton
class UpdateProjectIconTool @Inject constructor(
    private val projectManager: ProjectManager,
) : AgentTool {

    override val definition = AgentToolDefinition(
        name = "update_project_icon",
        description = "Update the launcher icon with background and foreground vector drawable XML.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put(
                        "backgroundXml",
                        stringProp("Complete XML content for src/main/res/drawable/ic_launcher_background.xml"),
                    )
                    put(
                        "foregroundXml",
                        stringProp("Complete XML content for src/main/res/drawable/ic_launcher_foreground.xml"),
                    )
                },
            )
            put("required", requiredFields("backgroundXml", "foregroundXml"))
        },
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val backgroundXml = call.arguments.requireString("backgroundXml")
        val foregroundXml = call.arguments.requireString("foregroundXml")
        val workspace = projectManager.openWorkspace(context.projectId)
        workspace.writeTextFile(ICON_BACKGROUND_PATH, backgroundXml)
        workspace.writeTextFile(ICON_FOREGROUND_PATH, foregroundXml)
        return call.okResult()
    }
}
