package com.vibe.app.feature.agent.tool

import com.vibe.app.data.repository.ProjectRepository
import com.vibe.app.feature.agent.AgentTool
import com.vibe.app.feature.agent.AgentToolCall
import com.vibe.app.feature.agent.AgentToolContext
import com.vibe.app.feature.agent.AgentToolDefinition
import com.vibe.app.feature.agent.AgentToolRegistry
import com.vibe.app.feature.agent.AgentToolResult
import com.vibe.app.feature.project.ProjectManager
import com.vibe.build.engine.model.BuildResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val READ_PROJECT_FILE = "read_project_file"
private const val WRITE_PROJECT_FILE = "write_project_file"
private const val RUN_BUILD_PIPELINE = "run_build_pipeline"
private const val RENAME_PROJECT = "rename_project"

@Singleton
class ReadProjectFileTool @Inject constructor(
    private val projectManager: ProjectManager,
) : AgentTool {

    override val definition: AgentToolDefinition = AgentToolDefinition(
        name = READ_PROJECT_FILE,
        description = "Read a UTF-8 text file from the template Android project workspace by relative path.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put(
                        "path",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Relative file path under the Android app module."))
                        },
                    )
                },
            )
            put("required", JsonArray(listOf(JsonPrimitive("path"))))
        },
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val path = call.arguments.requireString("path")
        val workspace = projectManager.openWorkspace(context.projectId)
        val content = workspace.readTextFile(path)
        return AgentToolResult(
            toolCallId = call.id,
            toolName = call.name,
            output = buildJsonObject {
                put("path", JsonPrimitive(path))
                put("content", JsonPrimitive(content))
            },
        )
    }
}

@Singleton
class WriteProjectFileTool @Inject constructor(
    private val projectManager: ProjectManager,
) : AgentTool {

    override val definition: AgentToolDefinition = AgentToolDefinition(
        name = WRITE_PROJECT_FILE,
        description = "Write a UTF-8 text file into the template Android project workspace by relative path. Always send the full file content.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put("path", buildJsonObject { put("type", JsonPrimitive("string")) })
                    put("content", buildJsonObject { put("type", JsonPrimitive("string")) })
                },
            )
            put(
                "required",
                JsonArray(listOf(JsonPrimitive("path"), JsonPrimitive("content"))),
            )
        },
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val path = call.arguments.requireString("path")
        val content = call.arguments.requireString("content")
        val workspace = projectManager.openWorkspace(context.projectId)
        workspace.writeTextFile(path, content)
        return AgentToolResult(
            toolCallId = call.id,
            toolName = call.name,
            output = buildJsonObject {
                put("path", JsonPrimitive(path))
                put("bytes", JsonPrimitive(content.encodeToByteArray().size))
                put("written", JsonPrimitive(true))
            },
        )
    }
}

@Singleton
class RunBuildPipelineTool @Inject constructor(
    private val projectManager: ProjectManager,
) : AgentTool {

    override val definition: AgentToolDefinition = AgentToolDefinition(
        name = RUN_BUILD_PIPELINE,
        description = "Run the on-device Android build pipeline for the current template project and return structured logs.",
        inputSchema = buildJsonObject {},
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val workspace = projectManager.openWorkspace(context.projectId)
        val result = workspace.buildProject()
        return AgentToolResult(
            toolCallId = call.id,
            toolName = call.name,
            output = result.toJson(),
            isError = result.errorMessage != null,
        )
    }
}

@Singleton
class RenameProjectTool @Inject constructor(
    private val projectRepository: ProjectRepository,
) : AgentTool {

    override val definition: AgentToolDefinition = AgentToolDefinition(
        name = RENAME_PROJECT,
        description = "Rename the current project to a short, descriptive name that reflects what the user is building. " +
            "Call this ONCE when you understand the user's intent from their first message. " +
            "Use a concise name like 'Calculator App', 'Todo List', 'Weather App'.",
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
            put("required", JsonArray(listOf(JsonPrimitive("name"))))
        },
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val name = call.arguments.requireString("name").take(30)
        if (context.projectId.isBlank()) {
            return AgentToolResult(
                toolCallId = call.id,
                toolName = call.name,
                output = buildJsonObject { put("error", JsonPrimitive("No project context available")) },
                isError = true,
            )
        }
        projectRepository.renameProject(context.projectId, name)
        return AgentToolResult(
            toolCallId = call.id,
            toolName = call.name,
            output = buildJsonObject {
                put("renamed", JsonPrimitive(true))
                put("name", JsonPrimitive(name))
            },
        )
    }
}

@Singleton
class DefaultAgentToolRegistry @Inject constructor(
    readProjectFileTool: ReadProjectFileTool,
    writeProjectFileTool: WriteProjectFileTool,
    runBuildPipelineTool: RunBuildPipelineTool,
    renameProjectTool: RenameProjectTool,
) : AgentToolRegistry {

    private val tools = listOf(
        readProjectFileTool,
        writeProjectFileTool,
        runBuildPipelineTool,
        renameProjectTool,
    )

    override fun listDefinitions(): List<AgentToolDefinition> = tools.map { it.definition }

    override fun findTool(name: String): AgentTool? = tools.firstOrNull { it.definition.name == name }
}

private fun JsonElement.requireString(key: String): String {
    val obj = this.jsonObject
    return obj[key]?.jsonPrimitive?.content
        ?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("Missing required string field: $key")
}

private fun BuildResult.toJson(): JsonObject {
    return buildJsonObject {
        put("status", JsonPrimitive(status.name))
        errorMessage?.let { put("errorMessage", JsonPrimitive(it)) }
        put(
            "artifacts",
            buildJsonArray {
                artifacts.forEach { artifact ->
                    add(
                        buildJsonObject {
                            put("stage", JsonPrimitive(artifact.stage.name))
                            put("path", JsonPrimitive(artifact.path))
                            put("description", JsonPrimitive(artifact.description))
                        },
                    )
                }
            },
        )
        put(
            "logs",
            buildJsonArray {
                logs.forEach { log ->
                    add(
                        buildJsonObject {
                            put("stage", JsonPrimitive(log.stage.name))
                            put("level", JsonPrimitive(log.level.name))
                            put("message", JsonPrimitive(log.message))
                            log.sourcePath?.let { put("sourcePath", JsonPrimitive(it)) }
                            log.line?.let { put("line", JsonPrimitive(it)) }
                        },
                    )
                }
            },
        )
    }
}
